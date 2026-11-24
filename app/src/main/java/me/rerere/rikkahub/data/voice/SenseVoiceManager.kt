package me.rerere.rikkahub.data.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * 本地 SenseVoice 语音情绪识别管理器
 *
 * 用途: 语音条发送后, 用本地模型对录音做离线识别, 提取 转写文字 + 语气(情绪) + 声音事件(哭声/笑声/叹气等),
 * 一并注入给 AI, 让对方"听得出"用户说话时的状态。识别完全在本地进行, 不上传任何音频。
 *
 * 模型: sherpa-onnx-sense-voice-zh-en-ja-ko-yue (int8, ~228MB), 首次使用时下载到 filesDir, 只下这一次。
 * 下载源优先 hf-mirror.com (国内直连), 失败回退 huggingface.co。
 */
class SenseVoiceManager(
    private val context: Context,
) {
    data class DownloadState(
        val isDownloading: Boolean = false,
        val receivedBytes: Long = 0,
        val totalBytes: Long = 0,
        val done: Boolean = false,
        val error: String? = null,
    )

    data class SenseVoiceResult(
        val text: String,
        val emotion: String,
        val voiceEvents: String,
    )

    private val _downloadState = MutableStateFlow(DownloadState())
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val modelDir: File get() = File(context.filesDir, "sensevoice")
    private val modelFile: File get() = File(modelDir, "model.int8.onnx")
    private val tokensFile: File get() = File(modelDir, "tokens.txt")

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    /** 模型文件是否已就绪, 可以直接识别 */
    fun isModelReady(): Boolean = modelFile.length() > 10_000_000 && tokensFile.length() > 1000

    /** 模型文件体积(字节), 设置/下载进度展示用 */
    fun modelBytes(): Long = modelFile.length()

    /**
     * 触发模型下载 (非挂起, 内部在 IO 协程执行)。
     * 进度/结果通过 downloadState StateFlow 观察者获取。
     * 已就绪或正在下载时重复调用直接忽略。
     */
    @Volatile
    private var downloadStarted = false
    private val downloadScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    fun startDownload() {
        if (isModelReady() || downloadStarted) return
        downloadStarted = true
        downloadScope.launch {
            _downloadState.value = DownloadState(isDownloading = true)
            modelDir.mkdirs()
            try {
                // tokens.txt 很小, 先拿; 大文件走 .part, 写完重命名, 避免半截文件被误判就绪
                downloadFile(TOKENS_URLS, tokensFile, quiet = true)
                downloadFile(MODEL_URLS, File(modelDir, "model.int8.onnx.part"), quiet = false)
                File(modelDir, "model.int8.onnx.part").renameTo(modelFile)
                _downloadState.value = DownloadState(done = true)
            } catch (e: Exception) {
                Log.w(TAG, "downloadModel failed", e)
                _downloadState.value = DownloadState(error = e.message ?: "download failed")
            } finally {
                downloadStarted = false
            }
        }
    }

    private fun downloadFile(urls: List<String>, target: File, quiet: Boolean) {
        var lastError: Exception? = null
        for (url in urls) {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(java.time.Duration.ofSeconds(20))
                    .readTimeout(java.time.Duration.ofMinutes(10))
                    .build()
                val call = client.newCall(Request.Builder().url(url).build())
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                    val body = resp.body ?: throw RuntimeException("empty body")
                    val total = body.contentLength()
                    target.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        var received = 0L
                        while (true) {
                            val n = body.byteStream().read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            received += n
                            if (!quiet) {
                                _downloadState.value = DownloadState(
                                    isDownloading = true,
                                    receivedBytes = received,
                                    totalBytes = if (total > 0) total else 0,
                                )
                            }
                        }
                    }
                }
                return
            } catch (e: Exception) {
                lastError = e
                target.delete()
                Log.w(TAG, "download failed from $url", e)
            }
        }
        throw lastError ?: RuntimeException("all mirrors failed")
    }

    /** 读取 WAV 时长(秒), 语音条发送时用 (ASRState.durationMs 从未赋值恒为 0, 这里兜底) */
    fun wavDurationSec(file: File): Long {
        if (!file.exists()) return 0L
        return runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(44)
                raf.readFully(header)
                fun le16(off: Int) = ((header[off].toInt() and 0xFF) or ((header[off + 1].toInt() and 0xFF) shl 8))
                fun le32(off: Int) = (le16(off) or (le16(off + 2) shl 16))
                val rate = le32(24)
                val dataLen = raf.length() - 44
                if (rate <= 0 || dataLen <= 0) 0L else (dataLen / 2 / rate)
            }
        }.getOrDefault(0L)
    }

    /**
     * 识别一段 WAV 录音 (本地, 阻塞约 0.3~2s, 请在后台线程调用)。
     * 返回 转写 + 语气 + 声音事件。
     */
    suspend fun recognize(wavFile: File): SenseVoiceResult = withContext(Dispatchers.Default) {
        if (!isModelReady()) return@withContext SenseVoiceResult("", "", "")
        val rec = ensureRecognizer()
        val samples = runCatching { readWavAsMono16k(wavFile) }.getOrElse { return@withContext SenseVoiceResult("", "", "") }
        if (samples.isEmpty()) return@withContext SenseVoiceResult("", "", "")

        val stream = rec.createStream()
        runCatching {
            stream.acceptWaveform(samples, sampleRate = 16000)
            rec.decode(stream)
        }
        val raw = runCatching { rec.getResult(stream).text }.getOrElse { "" }
        stream.release()

        parseSenseVoiceText(raw)
    }

    private fun ensureRecognizer(): OfflineRecognizer {
        recognizer?.let { return it }
        synchronized(this) {
            recognizer?.let { return it }
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = modelFile.absolutePath,
                        language = "",
                        useInverseTextNormalization = true,
                    ),
                    tokens = tokensFile.absolutePath,
                    numThreads = 2,
                    modelType = "sense-voice",
                ),
            )
            val rec = OfflineRecognizer(assetManager = null, config = config)
            recognizer = rec
            return rec
        }
    }

    /**
     * 解析 SenseVoice 输出: 形如 "<|zh|><|NEUTRAL|><|Speech|><|withitn|>你好呀"
     * 情绪 token 与 事件 token 映射为中文; 剩余部分为转写文字。
     */
    private fun parseSenseVoiceText(raw: String): SenseVoiceResult {
        // 闭合 token 形如 <|/Speech|>, 正则需兼容可选的 '/', 否则会残留在转写文本里
        val tokenRegex = Regex("<\\|(/?[A-Za-z_]+)\\|>")
        val emotions = mutableListOf<String>()
        val events = mutableListOf<String>()
        for (m in tokenRegex.findAll(raw)) {
            when (m.groupValues[1]) {
                "HAPPY" -> emotions.add("开心")
                "ANGRY" -> emotions.add("生气")
                "SAD" -> emotions.add("难过")
                "SURPRISED" -> emotions.add("惊讶")
                "FEARFUL" -> emotions.add("害怕")
                "DISGUSTED" -> emotions.add("厌恶")
                // NEUTRAL/EMO_UNKNOWN/OTHER = 模型没测出情绪, 保持空, 注入侧不硬说"平静"
                "Laughter" -> events.add("笑声")
                "Cry" -> events.add("哭声") // 词表 token 是 Cry 不是 Crying, 旧映射永远匹配不上导致哭声被丢弃
                "Sneeze" -> events.add("喷嚏")
                "Breath" -> events.add("呼吸声") // 词表无叹气标签, Breath 是最接近的信号(叹气/抽泣常表现为明显呼吸声)
                "Cough" -> events.add("咳嗽")
                "Applause" -> events.add("掌声")
                "Sing" -> events.add("唱歌")
                "BGM" -> events.add("背景音乐")
            }
        }
        val text = tokenRegex.replace(raw, "").trim()
        var emotion = emotions.firstOrNull() ?: ""
        // 兜底: 检出哭声时情绪档却给 NEUTRAL 是模型常见误判, 哭声是更硬的证据
        if (emotion.isBlank() && events.contains("哭声")) emotion = "难过"
        return SenseVoiceResult(
            text = text,
            emotion = emotion,
            voiceEvents = events.joinToString(","),
        )
    }

    /**
     * 读 WAV (16-bit PCM) -> 单声道 16kHz FloatArray。
     * 兼容任意采样率/声道数 (线性重采样 + 立体声平均), 录音源是 ASRController 的 voice_*.wav。
     */
    private fun readWavAsMono16k(file: File): FloatArray {
        java.io.RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(44)
            raf.readFully(header)
            fun le16(off: Int) = ((header[off].toInt() and 0xFF) or ((header[off + 1].toInt() and 0xFF) shl 8))
            fun le32(off: Int) = (le16(off) or (le16(off + 2) shl 16))
            val channels = le16(22)
            val sampleRate = le32(24)
            val bitsPerSample = le16(34)
            require(bitsPerSample == 16) { "unsupported bits: $bitsPerSample" }

            // 数据区: 简化处理, 44 字节头后全部按 PCM 读 (ASRController 生成标准 WAV)
            val len = (raf.length() - 44).toInt()
            val bytes = ByteArray(len)
            raf.seek(44)
            raf.readFully(bytes)

            val frameCount = len / 2 / channels
            val mono = FloatArray(frameCount)
            val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frameCount) {
                var acc = 0f
                for (c in 0 until channels) {
                    acc += bb.short.toFloat() / 32768f
                }
                mono[i] = acc / channels
            }

            if (sampleRate == 16000) return mono
            // 线性重采样到 16k
            val ratio = sampleRate.toFloat() / 16000f
            val outLen = (frameCount / ratio).toInt().coerceAtLeast(1)
            val out = FloatArray(outLen)
            for (i in 0 until outLen) {
                val pos = i * ratio
                val i0 = pos.toInt().coerceAtMost(frameCount - 1)
                val i1 = (i0 + 1).coerceAtMost(frameCount - 1)
                val frac = pos - i0
                out[i] = mono[i0] * (1 - frac) + mono[i1] * frac
            }
            return out
        }
    }

    companion object {
        private const val TAG = "SenseVoiceManager"

        private const val MODEL_DIR_REMOTE = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"
        // 国内优先 hf-mirror, 失败回退官方
        private val MODEL_URLS = listOf(
            "https://hf-mirror.com/$MODEL_DIR_REMOTE/model.int8.onnx",
            "https://huggingface.co/$MODEL_DIR_REMOTE/model.int8.onnx",
        )
        private val TOKENS_URLS = listOf(
            "https://hf-mirror.com/$MODEL_DIR_REMOTE/tokens.txt",
            "https://huggingface.co/$MODEL_DIR_REMOTE/tokens.txt",
        )
    }
}
