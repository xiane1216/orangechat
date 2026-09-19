/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.asr.providers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.appendAmplitude
import me.rerere.asr.calculateRmsAmplitude
import me.rerere.asr.stripTrailingEmoji
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Collections

private const val TAG = "SiliconFlowASR"

// 分段时长: 每 0.8 秒上传一次当前缓冲区, 实现"边说边出字"的近实时效果.
// 太短会增加请求频率和带宽, 太长则实时性差. 0.8s 是中文语音场景的折中值.
private const val SEGMENT_DURATION_MS = 800L
// 单个分段最大字节数 (兜底, 防止异常时长录太多)
private const val MAX_SEGMENT_BYTES = 4 * 1024 * 1024
// 缓冲区上限: 服务器慢时音频会堆积, 超过这个值就丢弃最旧的部分,
// 避免单次上传几十秒音频导致进一步超时 (30s timeout 都救不回来).
// 约 15 秒 @16kHz/16bit/mono.
private const val MAX_BUFFER_BYTES = 15 * 16_000 * 2
// 静音检测: 连续 250ms 音量低于阈值就提前 flush, 确保用户停顿时最后一句话
// 能被快速识别, 不必等满 SEGMENT_DURATION_MS.
private const val SILENCE_FLUSH_MS = 250L
private const val SILENCE_AMPLITUDE_THRESHOLD = 0.03f

/**
 * SiliconFlow ASR Controller (分段流式版本)
 *
 * 原版是"录一段→停→整段上传"的一次性模式, 用户必须等说完话才能看到转写文字,
 * 体验很差. 本版本改为:
 * - 全程持续录音, 不自动停止 (由调用方 / VoiceCallService 的 VAD 控制发送时机)
 * - 每 [SEGMENT_DURATION_MS] 毫秒把当前缓冲区里的 PCM 转 WAV 上传一次
 * - 每个分段的识别结果追加到 completedTranscripts, 实时通过 onTranscriptChange 推送
 * - stop() 时把剩余缓冲区做最后一次 flush, 然后切回 Idle
 *
 * 这样用户说话时屏幕上会持续出现文字, 接近豆包语音通话的实时转写体验.
 */
class SiliconFlowASRController(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val provider: ASRProviderSetting.SiliconFlow
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ASR 专用 OkHttpClient: 用短超时避免服务器慢/挂起时阻塞整个转写流程.
    // 全局 client 的 readTimeout=10min 是给 AI 流式聊天用的, ASR 不能复用.
    // readTimeout 设为 30s: 硅基流动服务器偶尔较慢, 15s 容易误超时;
    // 30s 能覆盖大部分正常请求, 同时不至于让用户等太久.
    private val asrHttpClient = httpClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var recorderJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var onTranscriptChange: ((String) -> Unit)? = null

    // 用 conflated Channel 替代原来的 flushJob 串行守卫.
    // 原来的实现: flushJob.isActive 时直接丢弃 triggerFlush, 导致 HTTP 请求慢时
    // 音频无限堆积、最终几分钟才出一批字甚至不出字.
    // 现在: 录音循环只管往 channel 发信号, 消费者串行处理; 处理期间来的多个信号
    // 被 conflate 成一个, 处理完立即再 flush 一次 (把堆积的音频发出去), 不会丢失.
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)
    private var flushConsumerJob: Job? = null

    private val bufferLock = Any()
    private var currentBuffer = ByteArrayOutputStream()
    private var segmentStartElapsedMs = 0L
    private val completedTranscripts = Collections.synchronizedList(mutableListOf<String>())

    override fun start(onTranscriptChange: (String) -> Unit) {
        if (state.value.isRecording) return
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            setError("Microphone permission is required")
            return
        }

        this.onTranscriptChange = onTranscriptChange
        synchronized(bufferLock) {
            currentBuffer = ByteArrayOutputStream()
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
        }
        completedTranscripts.clear()

        // 启动 flush 消费者: 串行处理 channel 中的信号, 处理完一个立即处理下一个
        // (conflated channel 保证处理期间来的多个信号只保留一个, 不会无限堆积).
        flushConsumerJob?.cancel()
        flushConsumerJob = scope.launch(Dispatchers.IO) {
            for (signal in flushSignal) {
                runCatching { flushSegment() }
                    .onFailure { Log.e(TAG, "Segment flush failed", it) }
            }
        }

        // 分段流式模式: 直接进入 Listening, 全程不自动停止
        _state.update {
            ASRState(
                status = ASRStatus.Listening,
                isAvailable = true,
                transcript = ""
            )
        }
        startRecorder()
    }

    override fun stop() {
        recorderJob?.cancel()
        releaseRecorder()
        _state.update { it.copy(status = ASRStatus.Stopping) }

        // 录音已停, 缓冲区不会再增长. 直接 flush 最后一段, 然后取消消费者.
        scope.launch(Dispatchers.IO) {
            try {
                flushSegment()
            } catch (e: Exception) {
                Log.e(TAG, "Final flush failed", e)
            } finally {
                flushConsumerJob?.cancel()
                flushConsumerJob = null
                _state.update { it.copy(status = ASRStatus.Idle) }
            }
        }
    }

    override fun dispose() {
        recorderJob?.cancel()
        flushConsumerJob?.cancel()
        flushSignal.close()
        releaseRecorder()
        scope.cancel()
    }

    override fun resetTranscript() {
        completedTranscripts.clear()
        _state.update { it.copy(transcript = "") }
        scope.launch { onTranscriptChange?.invoke("") }
    }

    @SuppressLint("MissingPermission")
    private fun startRecorder() {
        recorderJob?.cancel()
        recorderJob = scope.launch(Dispatchers.IO) {
            val minBufferSize = AudioRecord.getMinBufferSize(
                provider.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = minBufferSize
                .coerceAtLeast(provider.sampleRate / 10 * 2)
                .coerceAtLeast(4096)

            val recorder: AudioRecord
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    provider.sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 2
                )
                audioRecord = recorder
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException(
                        "AudioRecord 初始化失败, state=${recorder.state}, 请检查录音权限或音频参数"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord 构造/初始化失败", e)
                setError(e.message ?: "麦克风初始化失败")
                return@launch
            }

            try {
                recorder.startRecording()
                val buffer = ByteArray(bufferSize)
                var lastSpeechTime = SystemClock.elapsedRealtime()
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val amplitude = calculateRmsAmplitude(buffer, read)
                        _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) }

                        val now = SystemClock.elapsedRealtime()
                        if (amplitude > SILENCE_AMPLITUDE_THRESHOLD) {
                            lastSpeechTime = now
                        }

                        val shouldFlush = synchronized(bufferLock) {
                            currentBuffer.write(buffer, 0, read)
                            // 缓冲区上限保护: 服务器慢导致堆积时, 丢弃最旧的音频,
                            // 只保留最近 MAX_BUFFER_BYTES, 防止单次上传超大片段.
                            if (currentBuffer.size() > MAX_BUFFER_BYTES) {
                                val kept = currentBuffer.toByteArray()
                                    .takeLast(MAX_BUFFER_BYTES)
                                    .toByteArray()
                                currentBuffer = ByteArrayOutputStream()
                                currentBuffer.write(kept)
                            }
                            val elapsed = now - segmentStartElapsedMs
                            val silentFor = now - lastSpeechTime
                            currentBuffer.size() >= MAX_SEGMENT_BYTES ||
                                elapsed >= SEGMENT_DURATION_MS ||
                                (silentFor >= SILENCE_FLUSH_MS && currentBuffer.size() > 0)
                        }

                        if (shouldFlush) {
                            // 立即重置分段计时, 避免消费者处理期间 shouldFlush 持续为 true
                            // 导致重复发信号 (conflated channel 会自动去重, 但重置计时更高效).
                            synchronized(bufferLock) {
                                segmentStartElapsedMs = SystemClock.elapsedRealtime()
                            }
                            triggerFlush()
                            // flush 后重置静音计时, 避免连续触发
                            lastSpeechTime = SystemClock.elapsedRealtime()
                        }
                    } else if (read < 0) {
                        throw IllegalStateException("AudioRecord read error: $read")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio recording failed", e)
                setError(e.message ?: "Audio recording failed")
            } finally {
                releaseRecorder()
            }
        }
    }

    private fun triggerFlush() {
        // 往 conflated channel 发信号. 如果消费者正在处理, 信号会被合并;
        // 消费者处理完当前 flush 后会立即再处理一次 (把堆积的音频发出去).
        // 不会像旧实现那样直接丢弃, 避免音频无限堆积.
        flushSignal.trySend(Unit)
    }

    /**
     * 取出当前缓冲区里的 PCM, 转 WAV 后 POST 到 SiliconFlow;
     * 把识别结果加到 completedTranscripts 并实时推送.
     * 在 bufferLock 内拷贝出 PCM 并立刻重置缓冲区, 不持有锁等待网络, 避免阻塞录音写.
     */
    private suspend fun flushSegment() {
        val pcmBytes = synchronized(bufferLock) {
            if (currentBuffer.size() == 0) return
            val bytes = currentBuffer.toByteArray()
            currentBuffer = ByteArrayOutputStream()
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
            bytes
        }

        val wavData = pcmToWav(pcmBytes, provider.sampleRate)

        // 保存到持久化文件 (供调试 / 后续复用)
        val voiceDir = File(context.filesDir, "voice_messages")
        voiceDir.mkdirs()
        val audioFile = File(voiceDir, "voice_${System.currentTimeMillis()}.wav")
        FileOutputStream(audioFile).use { it.write(wavData) }

        val text = withContext(Dispatchers.IO) {
            try {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("model", provider.model)
                    .addFormDataPart(
                        "file",
                        "audio.wav",
                        audioFile.asRequestBody("audio/wav".toMediaType())
                    )
                    .apply {
                        if (provider.language.isNotBlank()) {
                            addFormDataPart("language", provider.language)
                        }
                    }
                    .build()

                val request = Request.Builder()
                    .url(provider.baseUrl.trim())
                    .addHeader("Authorization", "Bearer ${provider.apiKey}")
                    .post(requestBody)
                    .build()

                val response = asrHttpClient.newCall(request).execute()
                val responseBody = response.body?.string()

                if (responseBody == null) {
                    audioFile.delete()
                    Log.w(TAG, "API error: empty response")
                    return@withContext ""
                }

                Log.d(TAG, "API response: ${response.code} $responseBody")
                val json = JSONObject(responseBody)

                val code = json.optInt("code", -1)
                val message = json.optString("message", "")

                if (code != -1 && code != 0) {
                    audioFile.delete()
                    Log.w(TAG, "API error code=$code, message=$message")
                    return@withContext ""
                }

                val rawText = json.optString("data", "").trim().ifEmpty {
                    json.optString("text", "").trim()
                }
                rawText.stripTrailingEmoji()
            } catch (e: Exception) {
                Log.e(TAG, "Segment transcription failed", e)
                ""
            }
        }

        audioFile.delete()

        if (text.isNotEmpty()) {
            completedTranscripts.add(text)
            publishTranscript()
        }
    }

    private fun publishTranscript() {
        val transcript = completedTranscripts
            .filter { it.isNotBlank() }
            .joinToString(" ")
        _state.update { it.copy(transcript = transcript, errorMessage = null) }
        scope.launch { onTranscriptChange?.invoke(transcript) }
    }

    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val dataSize = pcmData.size
        val totalSize = 36 + dataSize

        val wav = ByteArrayOutputStream(44 + dataSize)
        // RIFF header
        wav.write("RIFF".toByteArray())
        writeIntLE(wav, totalSize)
        wav.write("WAVE".toByteArray())
        // fmt chunk
        wav.write("fmt ".toByteArray())
        writeIntLE(wav, 16) // chunk size
        writeShortLE(wav, 1) // PCM format
        writeShortLE(wav, numChannels.toShort())
        writeIntLE(wav, sampleRate)
        writeIntLE(wav, byteRate)
        writeShortLE(wav, blockAlign.toShort())
        writeShortLE(wav, bitsPerSample.toShort())
        // data chunk
        wav.write("data".toByteArray())
        writeIntLE(wav, dataSize)
        wav.write(pcmData)

        return wav.toByteArray()
    }

    private fun writeIntLE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 24) and 0xFF)
    }

    private fun writeShortLE(out: ByteArrayOutputStream, value: Short) {
        out.write(value.toInt() and 0xFF)
        out.write((value.toInt() shr 8) and 0xFF)
    }

    private fun setError(message: String) {
        _state.update {
            it.copy(
                status = ASRStatus.Error,
                errorMessage = message
            )
        }
    }

    private fun releaseRecorder() {
        recorderJob = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }
}
