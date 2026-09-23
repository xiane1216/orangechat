/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.rikkahub.data.model.ExternalMemory
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 外置记忆库服务
 * 基于 ExternalMemory 配置操作 Supabase 数据库
 */
class ExternalMemoryService(
    private val config: ExternalMemory
) {
    companion object {
        private const val TAG = "ExternalMemoryService"

        // 一键测试写入使用的 assistant_id（独立于真实助手，避免污染召回结果）
        const val TEST_WRITE_ASSISTANT_ID = "orangechat_test_write"
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /**
     * 保存聊天消息到外置记忆库
     *
     * @param originalCreatedAt 消息的原始时间戳（"yyyy-MM-dd HH:mm:ss"，本地时区）。
     * 实时保存/失败重试/补传历史时都应传入消息自己的创建时间，保证远端时间顺序与本地一致；
     * 为空时退回旧行为（用当前保存时刻）。
     */
    suspend fun saveMessage(
        assistantId: String,
        conversationId: String,
        role: String,
        content: String,
        originalCreatedAt: String? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val createdAt = originalCreatedAt?.takeIf { it.isNotBlank() }
                ?: java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date())
            val jsonString = buildJsonObject {
                put("assistant_id", JsonPrimitive(assistantId))
                put("conversation_id", JsonPrimitive(conversationId))
                put("role", JsonPrimitive(role))
                put("content", JsonPrimitive(content))
                put("created_at", JsonPrimitive(createdAt))
            }.toString()

            postToMessagesTable(jsonString)
            Log.d(TAG, "Saved message to ${config.tableName} for assistant $assistantId (createdAt=$createdAt)")
        }.map { }
    }

    /**
     * 批量保存消息（用于补传历史消息），一次 POST 插入多行
     */
    suspend fun saveMessageBatch(
        rows: List<ExternalMemoryMessageInsert>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(rows.isNotEmpty()) { "rows must not be empty" }
            val jsonArray = rows.joinToString(",", "[", "]") { row ->
                buildJsonObject {
                    put("assistant_id", JsonPrimitive(row.assistantId))
                    put("conversation_id", JsonPrimitive(row.conversationId))
                    put("role", JsonPrimitive(row.role))
                    put("content", JsonPrimitive(row.content))
                    put("created_at", JsonPrimitive(row.createdAt))
                }.toString()
            }
            postToMessagesTable(jsonArray)
            Log.d(TAG, "Saved ${rows.size} messages to ${config.tableName} (batch)")
        }.map { }
    }

    /**
     * 一键测试写入：向消息表写入一条测试数据并读回验证（写+读都通过才算成功）。
     * 测试数据使用独立的 assistant_id，不会影响真实助手的记忆召回。
     */
    suspend fun testWrite(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val marker = "OrangeChat-Test-${System.currentTimeMillis()}"
            saveMessage(
                assistantId = TEST_WRITE_ASSISTANT_ID,
                conversationId = "test",
                role = "user",
                content = "【测试写入】$marker 连接正常，本条为测试数据，可删除",
            ).getOrThrow()
            // 读回验证（同时验证 SELECT 权限，召回功能依赖它）
            val verified = queryLatestMessages(TEST_WRITE_ASSISTANT_ID, 1)
                .getOrDefault(emptyList())
                .any { it.content.contains(marker) }
            if (!verified) {
                throw IllegalStateException("写入成功，但读取验证失败（可能缺少查询权限）")
            }
            Log.i(TAG, "testWrite ok for ${config.name}")
        }.map { }
    }

    /**
     * 查询时间范围内已存在的消息（用于补传前去重，带分页）。
     * 返回的行按 created_at 升序。
     *
     * @param startAt "yyyy-MM-dd HH:mm:ss"（含）
     * @param endAt "yyyy-MM-dd HH:mm:ss"（含）
     */
    suspend fun queryMessagesInRange(
        startAt: String,
        endAt: String,
        offset: Int = 0,
        limit: Int = 1000,
    ): Result<List<ExternalMemoryMessage>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.supabaseUrl.trimEnd('/')
            val query = "select=assistant_id,conversation_id,role,content" +
                "&created_at=gte.${encodeQueryValue(startAt)}" +
                "&created_at=lte.${encodeQueryValue(endAt)}" +
                "&order=created_at.asc&limit=$limit&offset=$offset"
            val endpoint = URL("$url/rest/v1/${config.tableName}?$query")

            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("apikey", config.supabaseKey)
                setRequestProperty("Authorization", "Bearer ${config.supabaseKey}")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "queryMessagesInRange HTTP $responseCode body=$errorBody")
                throw SupabaseHttpException(responseCode, "Supabase API error ($responseCode): $errorBody")
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            parseMessages(responseText)
        }
    }

    /**
     * 向消息表发送 POST（单行 JSON 或 JSON 数组均可），失败抛出带状态码的异常
     */
    private fun postToMessagesTable(body: String) {
        val url = config.supabaseUrl.trimEnd('/')
        val endpoint = URL("$url/rest/v1/${config.tableName}")

        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("apikey", config.supabaseKey)
            setRequestProperty("Authorization", "Bearer ${config.supabaseKey}")
            setRequestProperty("Prefer", "return=minimal")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
        }

        connection.outputStream.bufferedWriter().use { writer ->
            writer.write(body)
            writer.flush()
        }

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            Log.e(TAG, "POST ${config.tableName} HTTP $responseCode body=$errorBody")
            throw SupabaseHttpException(responseCode, "Supabase API error ($responseCode): $errorBody")
        }
    }

    private fun encodeQueryValue(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /**
     * 查询最新 N 条消息
     */
    suspend fun queryLatestMessages(
        assistantId: String,
        limit: Int = 10,
    ): Result<List<ExternalMemoryMessage>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.supabaseUrl.trimEnd('/')
            val query = "assistant_id=eq.${URLEncoder.encode(assistantId, "UTF-8")}&order=created_at.desc&limit=$limit"
            val endpoint = URL("$url/rest/v1/${config.tableName}?$query")

            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("apikey", config.supabaseKey)
                setRequestProperty("Authorization", "Bearer ${config.supabaseKey}")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "queryLatestMessages HTTP $responseCode body=$errorBody")
                throw SupabaseHttpException(responseCode, "Supabase API error ($responseCode): $errorBody")
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            parseMessages(responseText)
        }
    }

    /**
     * 关键词搜索消息
     */
    suspend fun searchMessages(
        assistantId: String,
        keyword: String,
        limit: Int = 10,
    ): Result<List<ExternalMemoryMessage>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.supabaseUrl.trimEnd('/')
            val encodedKeyword = URLEncoder.encode("%$keyword%", "UTF-8")
            val query = "assistant_id=eq.${URLEncoder.encode(assistantId, "UTF-8")}&content=ilike.$encodedKeyword&order=created_at.desc&limit=$limit"
            val endpoint = URL("$url/rest/v1/${config.tableName}?$query")

            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("apikey", config.supabaseKey)
                setRequestProperty("Authorization", "Bearer ${config.supabaseKey}")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "searchMessages HTTP $responseCode body=$errorBody")
                throw SupabaseHttpException(responseCode, "Supabase API error ($responseCode): $errorBody")
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            parseMessages(responseText)
        }
    }

    /**
     * 保存日记摘要（可选带 embedding 向量）
     *
     * 若表缺少 embedding 列导致失败，自动降级为不带 embedding 重试，
     * 保证日记内容一定能写入表（向量检索能力需用户在 Supabase 补列后才有）。
     */
    suspend fun saveDiarySummary(
        assistantId: String,
        content: String,
        embedding: List<Float>? = null,
        /**
         * 这篇日记对应的日期（"yyyy-MM-dd"）。
         * 会作为 created_at 写入（设为该日 00:00:00），这样去重查询 querySummariesByDate
         * 才能按"日记对应日"命中，而不是按写入时刻（可能落在次日）导致每次都重复生成。
         * 为空时退回旧逻辑（用当前写入时刻）。
         */
        targetDate: String? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            val createdAt = if (!targetDate.isNullOrBlank()) {
                "$targetDate 00:00:00"
            } else {
                sdf.format(java.util.Date())
            }

            // 首次尝试：若提供了 embedding，则带上 embedding 字段
            val firstJson = buildSummaryJson(assistantId, content, createdAt, embedding)
            try {
                postSummaries(firstJson)
                Log.i(
                    TAG,
                    "Saved diary summary to ${config.summariesTableName} for assistant $assistantId " +
                        "(with embedding=${embedding != null})"
                )
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                // 表缺少 embedding 列（PostgREST PGRST204 / 列名错误）时，降级为不带 embedding 重试
                val missingEmbeddingColumn = embedding != null &&
                    (
                        msg.contains("PGRST204", ignoreCase = true) ||
                            msg.contains("Could not find the", ignoreCase = true) ||
                            msg.contains("'embedding'", ignoreCase = true) ||
                            msg.contains("embedding", ignoreCase = true)
                        )
                if (missingEmbeddingColumn) {
                    Log.w(
                        TAG,
                        "Column 'embedding' not found in ${config.summariesTableName}, retrying without embedding",
                        e
                    )
                    val fallbackJson = buildSummaryJson(assistantId, content, createdAt, null)
                    postSummaries(fallbackJson)
                    Log.i(
                        TAG,
                        "Saved diary summary to ${config.summariesTableName} for assistant $assistantId " +
                            "(without embedding, fallback)"
                    )
                } else {
                    throw e
                }
            }
        }.map { }
    }

    /**
     * 构建日记摘要的 JSON body
     */
    private fun buildSummaryJson(
        assistantId: String,
        content: String,
        createdAt: String,
        embedding: List<Float>?
    ): String = buildJsonObject {
        put("assistant_id", JsonPrimitive(assistantId))
        put("content", JsonPrimitive(content))
        put("created_at", JsonPrimitive(createdAt))
        if (embedding != null) {
            // pgvector 接受 "[1.0,2.0]" 字符串形式
            put("embedding", JsonPrimitive(embedding.joinToString(",", "[", "]")))
        }
    }.toString()

    /**
     * 向 memory_summaries 表发送 POST 请求，失败抛出带详细错误体的异常
     */
    private fun postSummaries(jsonString: String) {
        val url = config.supabaseUrl.trimEnd('/')
        val endpoint = URL("$url/rest/v1/${config.summariesTableName}")

        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("apikey", config.supabaseKey)
            setRequestProperty("Authorization", "Bearer ${config.supabaseKey}")
            setRequestProperty("Prefer", "return=minimal")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
        }

        connection.outputStream.bufferedWriter().use { writer ->
            writer.write(jsonString)
            writer.flush()
        }

        val responseCode = connection.responseCode
        Log.d(TAG, "saveDiarySummary POST ${config.summariesTableName} responseCode=$responseCode")
        if (responseCode !in 200..299) {
            val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            Log.e(TAG, "saveDiarySummary HTTP $responseCode body=$errorBody")
            throw SupabaseHttpException(responseCode, "Supabase API error ($responseCode): $errorBody")
        }
    }

    /**
     * 按日期查询消息（用于日记总结）
     */
    suspend fun queryMessagesByDate(
        dateStr: String,
    ): Result<List<ExternalMemoryMessage>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.supabaseUrl.trimEnd('/')
            val startOfDay = "${dateStr} 00:00:00"
            val endOfDay = "${dateStr} 23:59:59"
            val query = "created_at=gte.${URLEncoder.encode(startOfDay, "UTF-8")}&created_at=lte.${URLEncoder.encode(endOfDay, "UTF-8")}&order=created_at.asc"
            val endpoint = URL("$url/rest/v1/${config.tableName}?$query")

            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("apikey", config.supabaseKey)
                setRequestProperty("Authorization", "Bearer ${config.supabaseKey}")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "queryMessagesByDate HTTP $responseCode body=$errorBody")
                throw SupabaseHttpException(responseCode, "Supabase API error ($responseCode): $errorBody")
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            parseMessages(responseText)
        }
    }

    /**
     * 查询指定日期是否有日记摘要（用于去重）
     */
    suspend fun querySummariesByDate(
        assistantId: String,
        dateStr: String,
    ): Result<List<ExternalMemorySummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.supabaseUrl.trimEnd('/')
            val startOfDay = "${dateStr} 00:00:00"
            val endOfDay = "${dateStr} 23:59:59"
            val query = "assistant_id=eq.${URLEncoder.encode(assistantId, "UTF-8")}&created_at=gte.${URLEncoder.encode(startOfDay, "UTF-8")}&created_at=lte.${URLEncoder.encode(endOfDay, "UTF-8")}&order=created_at.desc"
            val endpoint = URL("$url/rest/v1/${config.summariesTableName}?$query")

            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("apikey", config.supabaseKey)
                setRequestProperty("Authorization", "Bearer ${config.supabaseKey}")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "querySummariesByDate HTTP $responseCode body=$errorBody")
                throw SupabaseHttpException(responseCode, "Supabase API error ($responseCode): $errorBody")
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            parseSummaries(responseText)
        }
    }

    /**
     * 查询最新日记摘要
     */
    suspend fun queryLatestSummaries(
        assistantId: String,
        limit: Int = 5,
    ): Result<List<ExternalMemorySummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.supabaseUrl.trimEnd('/')
            val query = "assistant_id=eq.${URLEncoder.encode(assistantId, "UTF-8")}&order=created_at.desc&limit=$limit"
            val endpoint = URL("$url/rest/v1/${config.summariesTableName}?$query")

            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("apikey", config.supabaseKey)
                setRequestProperty("Authorization", "Bearer ${config.supabaseKey}")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "queryLatestSummaries HTTP $responseCode body=$errorBody")
                throw SupabaseHttpException(responseCode, "Supabase API error ($responseCode): $errorBody")
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            parseSummaries(responseText)
        }
    }

    /**
     * 查询某助手的所有日记摘要（用于向量召回）
     */
    suspend fun queryAllSummaries(
        assistantId: String,
    ): Result<List<ExternalMemorySummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.supabaseUrl.trimEnd('/')
            val query = "assistant_id=eq.${URLEncoder.encode(assistantId, "UTF-8")}&order=created_at.desc"
            val endpoint = URL("$url/rest/v1/${config.summariesTableName}?$query")

            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("apikey", config.supabaseKey)
                setRequestProperty("Authorization", "Bearer ${config.supabaseKey}")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "queryAllSummaries HTTP $responseCode body=$errorBody")
                throw SupabaseHttpException(responseCode, "Supabase API error ($responseCode): $errorBody")
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            parseSummaries(responseText)
        }
    }

    /**
     * 向量召回日记摘要（本地计算余弦相似度）
     */
    suspend fun vectorRecallSummaries(
        queryEmbedding: List<Float>,
        assistantId: String,
        count: Int = 5,
    ): Result<List<ExternalMemorySummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val allSummaries = queryAllSummaries(assistantId).getOrDefault(emptyList())
                .filter { it.embedding.isNotEmpty() }

            val scored = allSummaries.mapNotNull { summary ->
                val similarity = cosineSimilarity(queryEmbedding, summary.embedding)
                summary to similarity
            }

            scored.sortedByDescending { it.second }
                .take(count)
                .map { it.first }
        }
    }

    private fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return if (normA == 0f || normB == 0f) 0f else dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))
    }

    private fun parseMessages(jsonText: String): List<ExternalMemoryMessage> {
        val result = mutableListOf<ExternalMemoryMessage>()
        try {
            val array = JSONArray(jsonText)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                result.add(
                    ExternalMemoryMessage(
                        id = obj.optInt("id", 0),
                        assistantId = obj.optString("assistant_id", ""),
                        conversationId = obj.optString("conversation_id", ""),
                        role = obj.optString("role", ""),
                        content = obj.optString("content", ""),
                        createdAt = obj.optString("created_at", ""),
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse messages", e)
        }
        return result
    }

    private fun parseSummaries(jsonText: String): List<ExternalMemorySummary> {
        val result = mutableListOf<ExternalMemorySummary>()
        try {
            val array = JSONArray(jsonText)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val embeddingStr = obj.optString("embedding", "")
                val embedding = if (embeddingStr.isNotBlank() && embeddingStr.startsWith("[")) {
                    embeddingStr.trim('[', ']').split(",").mapNotNull { it.trim().toFloatOrNull() }
                } else emptyList()
                result.add(
                    ExternalMemorySummary(
                        id = obj.optInt("id", 0),
                        assistantId = obj.optString("assistant_id", ""),
                        content = obj.optString("content", ""),
                        createdAt = obj.optString("created_at", ""),
                        embedding = embedding,
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse summaries", e)
        }
        return result
    }
}

data class ExternalMemoryMessage(
    val id: Int = 0,
    val assistantId: String = "",
    val conversationId: String = "",
    val role: String = "",
    val content: String = "",
    val createdAt: String = "",
)

/**
 * 待插入外置记忆库的一行消息（补传历史消息用）
 */
data class ExternalMemoryMessageInsert(
    val assistantId: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val createdAt: String,
)

data class ExternalMemorySummary(
    val id: Int = 0,
    val assistantId: String = "",
    val content: String = "",
    val createdAt: String = "",
    val embedding: List<Float> = emptyList(),
)

/**
 * Supabase REST API 返回非 2xx 状态码。
 * 4xx（除 429 限流）视为永久性错误，重试无意义；网络异常/5xx/429 可重试。
 */
class SupabaseHttpException(val statusCode: Int, message: String) : Exception(message)

/**
 * 将消息的本地时间格式化为 Supabase 表使用的 "yyyy-MM-dd HH:mm:ss" 字符串。
 * UIMessage.createdAt 本身就是系统默认时区的墙上时间，直接按该格式输出即可，
 * 与 saveMessage 内部 SimpleDateFormat 的输出格式保持一致。
 */
fun formatSupabaseTimestamp(createdAt: kotlinx.datetime.LocalDateTime): String =
    createdAt.toJavaLocalDateTime()
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))