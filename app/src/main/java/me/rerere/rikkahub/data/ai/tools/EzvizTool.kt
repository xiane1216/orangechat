/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SystemToolsSetting
import me.rerere.rikkahub.utils.ImageUtils
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 萤石云监控工具.
 *
 * 调用萤石开放平台 "设备抓拍图片" 接口 (/api/lapp/device/capture),
 * 抓取摄像头当前画面的实时截图, 下载到本地并压缩后以图片形式返回给 AI,
 * AI 可通过视觉模型识别画面内容 (看宝宝在干嘛).
 *
 * 节流: 每个摄像头最小调用间隔 4 秒 (萤石官方建议), 超时直接返回上一张截图.
 */
fun createEzvizTool(context: Context, setting: SystemToolsSetting): Tool = Tool(
    name = "ezviz_capture",
    description = """
        Capture a real-time snapshot from the user's Ezviz (萤石) security camera and return the image for visual analysis.
        Use this to see what the user is doing right now, check on them, or observe their surroundings.
        The AI can describe what it sees in the photo. You can call this proactively whenever you want to look at the user.
        Throttled to at most once every few seconds per camera.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {})
    },
    execute = { _ ->
        try {
            val appKey = setting.ezvizAppKey.trim()
            val appSecret = setting.ezvizAppSecret.trim()
            val deviceSerial = setting.ezvizDeviceSerial.trim()

            if (appKey.isEmpty() || appSecret.isEmpty()) {
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", "萤石监控未配置 appKey/appSecret, 请在设置中填写.")
                    }.toString()
                ))
            }

            // 1. 获取 accessToken (appKey + appSecret)
            val accessToken = getEzvizAccessToken(appKey, appSecret)
                ?: return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", "获取萤石 accessToken 失败, 请检查 appKey/appSecret 是否正确.")
                    }.toString()
                ))

            // 2. 若未指定设备序列号, 取账号下第一个设备
            val serial = deviceSerial.ifEmpty {
                getFirstDeviceSerial(accessToken) ?: return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", "未找到萤石摄像头设备, 请确认设备已添加到开发者账号.")
                    }.toString()
                ))
            }

            // 3. 节流: 同一设备最小间隔 4 秒
            val now = System.currentTimeMillis()
            val lastCall = lastCaptureTimestamps[serial] ?: 0L
            if (now - lastCall < MIN_INTERVAL_MS) {
                // 返回上一张截图
                lastCaptureFiles[serial]?.let { cached ->
                    return@Tool listOf(
                        UIMessagePart.Text(buildJsonObject {
                            put("success", true)
                            put("message", "截图获取成功(使用缓存). 图片已附加用于视觉分析.")
                        }.toString()),
                        UIMessagePart.Image(url = "file://${cached.absolutePath}")
                    )
                }
            }
            lastCaptureTimestamps[serial] = now

            // 4. 调用抓拍接口
            val picUrl = captureDevice(accessToken, serial)
                ?: return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", "萤石抓拍失败, 设备可能离线或不支持抓拍.")
                    }.toString()
                ))

            // 5. 下载图片
            val imageBytes = downloadImage(picUrl)
                ?: return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", "截图下载失败.")
                    }.toString()
                ))

            // 6. 保存原图 + 压缩给 AI
            val ezvizDir = File(context.filesDir, "ezviz_captures").apply { mkdirs() }
            val originalFile = File(ezvizDir, "capture_${now}_original.jpg")
            originalFile.outputStream().use { it.write(imageBytes) }

            val compressedForAI = try {
                val originalBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                if (originalBitmap != null) {
                    val compressed = ImageUtils.compressBitmapForAI(originalBitmap, maxSize = 2048, quality = 85)
                    val compressedFile = File(ezvizDir, "capture_${now}_ai.jpg")
                    compressedFile.outputStream().use { it.write(compressed) }
                    if (!originalBitmap.isRecycled) originalBitmap.recycle()
                    "file://${compressedFile.absolutePath}"
                } else {
                    "file://${originalFile.absolutePath}"
                }
            } catch (e: Exception) {
                "file://${originalFile.absolutePath}"
            }

            lastCaptureFiles[serial] = File(compressedForAI.removePrefix("file://"))

            listOf(
                UIMessagePart.Text(buildJsonObject {
                    put("success", true)
                    put("message", "萤石监控截图获取成功. 图片已附加用于视觉分析, 请描述你看到的内容.")
                }.toString()),
                UIMessagePart.Image(url = compressedForAI)
            )
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", "萤石监控工具异常: ${e.message}")
                }.toString()
            ))
        }
    }
)

private const val MIN_INTERVAL_MS = 4000L
private const val EZVIZ_HOST = "https://open.ys7.com"

private val lastCaptureTimestamps = mutableMapOf<String, Long>()
private val lastCaptureFiles = mutableMapOf<String, File>()

private fun getEzvizAccessToken(appKey: String, appSecret: String): String? {
    return try {
        val body = "appKey=${URLEncoder.encode(appKey, "UTF-8")}" +
            "&appSecret=${URLEncoder.encode(appSecret, "UTF-8")}"
        val conn = (URL("$EZVIZ_HOST/api/lapp/token/get").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val resp = conn.inputStream.bufferedReader().use { it.readText() }
        val json = kotlinx.serialization.json.Json.parseToJsonElement(resp).jsonObject
        if (json["code"]?.jsonPrimitive?.contentOrNull == "200") {
            json["data"]?.jsonObject?.get("accessToken")?.jsonPrimitive?.contentOrNull
        } else null
    } catch (e: Exception) {
        null
    }
}

private fun getFirstDeviceSerial(accessToken: String): String? {
    return try {
        val body = "accessToken=${URLEncoder.encode(accessToken, "UTF-8")}&pageStart=0&pageSize=5"
        val conn = (URL("$EZVIZ_HOST/api/lapp/device/list").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val resp = conn.inputStream.bufferedReader().use { it.readText() }
        val json = kotlinx.serialization.json.Json.parseToJsonElement(resp).jsonObject
        if (json["code"]?.jsonPrimitive?.contentOrNull == "200") {
            val devices = json["data"]?.jsonObject?.get("data")
                ?: json["data"] // 兼容不同版本结构
            // data 可能是数组
            val arr = devices?.let {
                kotlin.runCatching { it.jsonArray }.getOrNull()
            }
            arr?.firstOrNull()?.jsonObject?.get("deviceSerial")?.jsonPrimitive?.contentOrNull
        } else null
    } catch (e: Exception) {
        null
    }
}

private fun captureDevice(accessToken: String, deviceSerial: String): String? {
    return try {
        val body = "accessToken=${URLEncoder.encode(accessToken, "UTF-8")}" +
            "&deviceSerial=${URLEncoder.encode(deviceSerial.uppercase(), "UTF-8")}" +
            "&channelNo=1"
        val conn = (URL("$EZVIZ_HOST/api/lapp/device/capture").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val resp = conn.inputStream.bufferedReader().use { it.readText() }
        val json = kotlinx.serialization.json.Json.parseToJsonElement(resp).jsonObject
        if (json["code"]?.jsonPrimitive?.contentOrNull == "200") {
            json["data"]?.jsonObject?.get("picUrl")?.jsonPrimitive?.contentOrNull
        } else null
    } catch (e: Exception) {
        null
    }
}

private fun downloadImage(url: String): ByteArray? {
    return try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        if (conn.responseCode in 200..299) {
            conn.inputStream.use { it.readBytes() }
        } else null
    } catch (e: Exception) {
        null
    }
}
