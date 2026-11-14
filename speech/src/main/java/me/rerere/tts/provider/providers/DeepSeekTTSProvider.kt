/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "DeepSeekTTSProvider"

/**
 * DeepSeek TTS Provider
 *
 * POST {baseUrl}/tools/tts, JSON body:
 * { text (≤10000字符/64KiB), voice_id (stella/tide), format (wav/pcm), filter_markdown }
 * 鉴权 Bearer key。返回流式 audio/wav (16bit mono 24kHz) 或裸 PCM。
 * 分片文本 (TtsChunker 160 字/片) 远小于接口上限, 这里整体读回后单 chunk 发射。
 * 官方文档: https://api-docs.deepseek.com/zh-cn/api/tts
 */
class DeepSeekTTSProvider : TTSProvider<TTSProviderSetting.DeepSeek> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.DeepSeek,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val isPcm = providerSetting.audioFormat.equals("pcm", ignoreCase = true)
        val requestBody = JSONObject().apply {
            put("text", request.text)
            put("voice_id", providerSetting.voiceId)
            put("format", if (isPcm) "pcm" else "wav")
            put("filter_markdown", providerSetting.filterMarkdown)
        }

        Log.i(TAG, "generateSpeech: voice=${providerSetting.voiceId}, format=${providerSetting.audioFormat}")

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl.trimEnd('/')}/tools/tts")
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(httpRequest).execute()

        if (!response.isSuccessful) {
            val errBody = response.body?.string()
            Log.e(TAG, "generateSpeech: ${response.code} $errBody")
            throw Exception("DeepSeek TTS request failed: ${response.code} ${errBody ?: response.message}")
        }

        val audioData = response.body.bytes()
        if (audioData.isEmpty()) {
            throw Exception("DeepSeek TTS returned empty audio")
        }

        emit(
            AudioChunk(
                data = audioData,
                format = if (isPcm) AudioFormat.PCM else AudioFormat.WAV,
                // DeepSeek TTS 输出 16bit mono 24kHz
                sampleRate = 24000,
                isLast = true,
                metadata = mapOf(
                    "provider" to "deepseek",
                    "voice_id" to providerSetting.voiceId,
                    "format" to providerSetting.audioFormat
                )
            )
        )
    }
}
