/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.ExternalMemory
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.service.ExternalMemoryMessageInsert
import me.rerere.rikkahub.data.service.ExternalMemoryService
import kotlin.uuid.Uuid

enum class ExternalMemoryOpType {
    TestWrite,
    Backfill,
}

/**
 * 进阶记忆操作（测试写入/补传）的状态。
 * running = true 时 UI 显示进度；message 非空且 !running 时弹出结果。
 */
data class ExternalMemoryOpState(
    val memoryId: Uuid,
    val type: ExternalMemoryOpType,
    val running: Boolean,
    val message: String? = null,
)

class ExternalMemoriesVM(
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
) : ViewModel() {
    val settings = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    private val _opState = MutableStateFlow<ExternalMemoryOpState?>(null)
    val opState = _opState.asStateFlow()

    fun clearOp() {
        _opState.value = null
    }

    /**
     * 一键测试写入：向 Supabase 消息表写入一条测试数据并读回验证
     */
    fun testWrite(memory: ExternalMemory) {
        if (_opState.value?.running == true) return
        _opState.value = ExternalMemoryOpState(memory.id, ExternalMemoryOpType.TestWrite, running = true)
        viewModelScope.launch {
            val message = try {
                val result = ExternalMemoryService(memory).testWrite()
                if (result.isSuccess) {
                    "测试成功：写入和读取均正常（表 ${memory.tableName}）"
                } else {
                    "测试失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                }
            } catch (e: Exception) {
                "测试失败：${e.message ?: "未知错误"}"
            }
            _opState.value = ExternalMemoryOpState(
                memory.id, ExternalMemoryOpType.TestWrite, running = false, message = message
            )
        }
    }

    /**
     * 补传历史消息：把所选日期范围内、关联此记忆库的助手的本地聊天记录补传到 Supabase。
     *
     * - 保留消息原始时间戳（created_at），远端时间顺序与本地一致
     * - 按（助手+会话+角色+内容）与远端已有记录去重，重复补传/已同步过的不会重复插入
     * - 分批插入，每批之间短暂停顿，避免压垮网络
     */
    fun backfillHistory(memory: ExternalMemory, startDate: String, endDate: String) {
        if (_opState.value?.running == true) return
        _opState.value = ExternalMemoryOpState(
            memory.id, ExternalMemoryOpType.Backfill, running = true,
            message = "正在读取本地聊天记录…",
        )
        viewModelScope.launch {
            try {
                val settings = settingsStore.settingsFlow.first()
                val assistantIds = settings.assistants
                    .filter { memory.id in it.externalMemoryIds }
                    .map { it.id.toString() }
                    .toSet()
                if (assistantIds.isEmpty()) {
                    finishOp(memory.id, "该记忆库未关联任何助手，请先在助手设置中关联后再补传")
                    return@launch
                }

                val messages = conversationRepo.getBackfillMessages(startDate, endDate)
                    .filter { it.assistantId in assistantIds }
                if (messages.isEmpty()) {
                    finishOp(memory.id, "所选日期范围内没有找到关联助手的聊天记录")
                    return@launch
                }

                _opState.value = ExternalMemoryOpState(
                    memory.id, ExternalMemoryOpType.Backfill, running = true,
                    message = "正在查询远端已有记录…",
                )
                val service = ExternalMemoryService(memory)

                // 远端已存在的记录（查询窗口前后各扩 1 天：旧记录的 created_at 是"保存时刻"，
                // 与消息原始时间可能相差几秒~几分钟，扩窗避免去重漏配）
                val existing = mutableSetOf<String>()
                val padStart = shiftDate(startDate, -1)
                val padEnd = shiftDate(endDate, 1)
                var offset = 0
                while (true) {
                    val page = service.queryMessagesInRange(
                        "$padStart 00:00:00",
                        "$padEnd 23:59:59",
                        offset = offset,
                        limit = PAGE_SIZE,
                    ).getOrElse { e ->
                        finishOp(memory.id, "补传失败：查询远端记录时出错 — ${e.message ?: "未知错误"}")
                        return@launch
                    }
                    page.forEach {
                        existing.add("${it.assistantId}|${it.conversationId}|${it.role}|${it.content}")
                    }
                    if (page.size < PAGE_SIZE || offset >= MAX_QUERY_ROWS) break
                    offset += PAGE_SIZE
                }

                val missing = messages.filter {
                    "${it.assistantId}|${it.conversationId}|${it.role}|${it.content}" !in existing
                }
                val skipped = messages.size - missing.size
                if (missing.isEmpty()) {
                    finishOp(
                        memory.id,
                        "补传完成：范围内共 ${messages.size} 条记录，远端均已存在，无需补传"
                    )
                    return@launch
                }

                // 按原始时间升序分批插入
                var inserted = 0
                val batches = missing.chunked(BATCH_SIZE)
                for ((index, batch) in batches.withIndex()) {
                    _opState.value = ExternalMemoryOpState(
                        memory.id, ExternalMemoryOpType.Backfill, running = true,
                        message = "正在补传 ${inserted + 1}~${inserted + batch.size} / ${missing.size} 条…",
                    )
                    val batchResult = service.saveMessageBatch(
                        batch.map {
                            ExternalMemoryMessageInsert(
                                assistantId = it.assistantId,
                                conversationId = it.conversationId,
                                role = it.role,
                                content = it.content,
                                createdAt = it.createdAt,
                            )
                        }
                    )
                    if (batchResult.isFailure) {
                        val err = batchResult.exceptionOrNull()?.message ?: "未知错误"
                        finishOp(
                            memory.id,
                            "补传中断：已补传 $inserted/${missing.size} 条，错误：$err\n" +
                                "可直接重新补传，已传过的部分会自动跳过。"
                        )
                        return@launch
                    }
                    inserted += batch.size
                    if (index < batches.lastIndex) delay(300)
                }
                finishOp(memory.id, "补传完成：新增 $inserted 条，跳过 $skipped 条（远端已存在）")
            } catch (e: Exception) {
                finishOp(memory.id, "补传失败：${e.message ?: "未知错误"}")
            }
        }
    }

    private fun finishOp(memoryId: Uuid, message: String) {
        _opState.value = ExternalMemoryOpState(
            memoryId, ExternalMemoryOpType.Backfill,
            running = false, message = message,
        )
    }

    private fun shiftDate(date: String, days: Long): String = try {
        java.time.LocalDate.parse(date).plusDays(days).toString()
    } catch (e: Exception) {
        date
    }

    companion object {
        private const val PAGE_SIZE = 1000
        private const val MAX_QUERY_ROWS = 20_000
        private const val BATCH_SIZE = 50
    }

    fun addExternalMemory(
        name: String,
        supabaseUrl: String,
        supabaseKey: String,
        tableName: String,
        summariesTableName: String,
        autoSaveMessages: Boolean,
        autoSaveDiarySummary: Boolean,
        recallCount: Int,
        embeddingModelId: Uuid?,
    ) {
        updateExternalMemories(
            settings.value.externalMemories + ExternalMemory(
                name = name,
                supabaseUrl = supabaseUrl,
                supabaseKey = supabaseKey,
                tableName = tableName.ifBlank { "chat_messages" },
                summariesTableName = summariesTableName.ifBlank { "memory_summaries" },
                autoSaveMessages = autoSaveMessages,
                autoSaveDiarySummary = autoSaveDiarySummary,
                recallCount = recallCount,
                embeddingModelId = embeddingModelId,
            )
        )
    }

    fun updateExternalMemory(updated: ExternalMemory) {
        updateExternalMemories(
            settings.value.externalMemories.map { memory ->
                if (memory.id == updated.id) updated else memory
            }
        )
    }

    fun deleteExternalMemory(id: Uuid) {
        updateExternalMemories(
            settings.value.externalMemories.filterNot { memory ->
                memory.id == id
            }
        )
    }

    private fun updateExternalMemories(externalMemories: List<ExternalMemory>) {
        val validIds = externalMemories.map { it.id }.toSet()
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    externalMemories = externalMemories,
                    assistants = settings.assistants.map { assistant ->
                        assistant.copy(
                            externalMemoryIds = assistant.externalMemoryIds.filter { it in validIds }.toSet()
                        )
                    }
                )
            }
        }
    }
}
