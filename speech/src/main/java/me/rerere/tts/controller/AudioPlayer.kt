/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.tts.controller

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus
import me.rerere.tts.model.TTSResponse
import java.io.ByteArrayOutputStream
import java.util.Collections

/**
 * 无缝播放版 AudioPlayer.
 *
 * 旧实现每播一个分片都 setMediaSource 替换媒体源并 re-prepare, 分片之间存在
 * 可感知的停顿 (TTS "一句一卡" 的根源), 且每个分片播完都会对外发出一次 Ended
 * 状态, 上层 (如语音通话的 waitForTtsToFinish) 会把它误判成"全部播完"而提前
 * 打断后续内容.
 *
 * 新实现:
 * - 分片通过 addMediaSource 追加到同一个播放列表, ExoPlayer 自动无缝衔接;
 * - STATE_ENDED 只在整个播放队列播完时上报, 分片之间不再出现假的 Ended;
 * - play() 改为"入队即返回", 由播放器回调驱动分片完成事件;
 * - hasPendingAudio() 供上层判断是否还有音频没播完.
 */
class AudioPlayer(context: Context) {
    private val player = ExoPlayer.Builder(context).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        /** 分片之间的句间换气静音时长 (微秒), 120ms */
        private const val INTER_CHUNK_SILENCE_US = 120_000L

        /** 振幅计算窗口: 每 50ms 一个 RMS 值 */
        private const val AMPLITUDE_WINDOW_MS = 50L
    }

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var positionJob: Job? = null

    // region 播放振幅 (语音通话 AI 说话时音波起伏的数据源)
    //
    // 之前 Speaking 状态下音波冻住: TTS 侧没有任何实时振幅输出, UI 只能拿 ASR
    // 的旧数据或模拟波形. 现在每个分片入队时对音频预计算 RMS 振幅表 (50ms 一窗),
    // 播放时按 player.currentPosition 实时查出当前振幅.
    //
    // 时间轴按入队顺序累计: 音频分片与句间静音各占一个条目, startMs 顺次累加,
    // 与 ExoPlayer 播放列表的时间线一一对应.
    private class TimelineEntry(
        val startMs: Long,
        val durationMs: Long,
        val amplitudes: List<Float>? // null = 句间静音占位
    )

    private val timeline = Collections.synchronizedList(mutableListOf<TimelineEntry>())
    private var timelineDurationMs = 0L

    private val _amplitude = MutableStateFlow(0f)

    /** 当前播放位置的实时振幅 (0f~1f), 未播放/播完时为 0f */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private class PcmInfo(
        val data: ByteArray,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int
    )

    /** 解析音频为 PCM 信息: PCM 格式直接用, WAV 解析 RIFF 头, 失败时按裸 PCM 兜底 */
    private fun extractPcm(bytes: ByteArray, format: AudioFormat, fallbackSampleRate: Int): PcmInfo {
        if (format == AudioFormat.PCM) {
            return PcmInfo(bytes, fallbackSampleRate, 1, 16)
        }
        return parseWavPcm(bytes) ?: PcmInfo(bytes, fallbackSampleRate, 1, 16)
    }

    /** 解析 RIFF WAV, 取 fmt 与 data chunk; 解析失败返回 null */
    private fun parseWavPcm(wav: ByteArray): PcmInfo? {
        return try {
            if (wav.size < 44) return null
            if (String(wav, 0, 4, Charsets.US_ASCII) != "RIFF") return null
            var offset = 12
            var sampleRate = 24000
            var channels = 1
            var bitsPerSample = 16
            var pcm: ByteArray? = null
            while (offset + 8 <= wav.size) {
                val chunkId = String(wav, offset, 4, Charsets.US_ASCII)
                val chunkSize = readIntLE(wav, offset + 4)
                if (chunkSize < 0) return null // 损坏的 chunk 头, 防止死循环
                val dataStart = offset + 8
                when (chunkId) {
                    "fmt " -> {
                        channels = readShortLE(wav, dataStart + 2).toInt().coerceAtLeast(1)
                        sampleRate = readIntLE(wav, dataStart + 4)
                        bitsPerSample = readShortLE(wav, dataStart + 14).toInt()
                    }
                    "data" -> {
                        val end = minOf(dataStart + chunkSize, wav.size)
                        if (end > dataStart) pcm = wav.copyOfRange(dataStart, end)
                    }
                }
                if (pcm != null && sampleRate > 0) break
                offset = dataStart + chunkSize + (chunkSize % 2)
            }
            PcmInfo(
                data = pcm ?: return null,
                sampleRate = sampleRate.coerceIn(4000, 192000),
                channels = channels,
                bitsPerSample = bitsPerSample.takeIf { it in setOf(8, 16, 24, 32) } ?: 16
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun readIntLE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLE(data: ByteArray, offset: Int): Short {
        return ((data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)).toShort()
    }

    /** 与 asr.calculateRmsAmplitude 相同的映射: -60dB~0dB -> 0f~1f (仅支持 16bit) */
    private fun rmsAmplitude(data: ByteArray, offset: Int, length: Int): Float {
        val shorts = java.nio.ByteBuffer.wrap(data, offset, length)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        val count = shorts.remaining()
        if (count == 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            val s = shorts[i].toDouble()
            sum += s * s
        }
        val rms = kotlin.math.sqrt(sum / count)
        val linear = (rms / Short.MAX_VALUE).toFloat()
        if (linear < 1e-6f) return 0f
        val db = 20f * kotlin.math.log10(linear)
        return ((db + 60f) / 60f).coerceIn(0f, 1f)
    }

    private fun computeWindowAmplitudes(pcm: PcmInfo): List<Float> {
        if (pcm.bitsPerSample != 16) return emptyList()
        val frameBytes = 2 * pcm.channels
        val windowBytes = (pcm.sampleRate * frameBytes * AMPLITUDE_WINDOW_MS / 1000).toInt()
            .coerceAtLeast(frameBytes)
        val amps = ArrayList<Float>(pcm.data.size / windowBytes + 1)
        var offset = 0
        while (offset < pcm.data.size) {
            val len = minOf(windowBytes, pcm.data.size - offset)
            amps.add(rmsAmplitude(pcm.data, offset, len))
            offset += len
        }
        return amps
    }

    private fun appendTimelineEntry(response: TTSResponse) {
        val pcm = extractPcm(response.audioData, response.format, response.sampleRate ?: 24000)
        val amps = computeWindowAmplitudes(pcm)
        val frameBytes = pcm.bitsPerSample / 8 * pcm.channels
        val durationMs = if (frameBytes > 0) {
            pcm.data.size * 1000L / (pcm.sampleRate * frameBytes)
        } else 0L
        synchronized(timeline) {
            timeline.add(TimelineEntry(timelineDurationMs, durationMs, amps))
            timelineDurationMs += durationMs
        }
    }

    private fun appendSilenceTimelineEntry(durationMs: Long) {
        synchronized(timeline) {
            timeline.add(TimelineEntry(timelineDurationMs, durationMs, null))
            timelineDurationMs += durationMs
        }
    }

    private fun amplitudeAt(positionMs: Long): Float {
        synchronized(timeline) {
            for (i in timeline.indices.reversed()) {
                val entry = timeline[i]
                if (positionMs >= entry.startMs) {
                    val amps = entry.amplitudes ?: return 0f
                    if (amps.isEmpty()) return 0f
                    val idx = ((positionMs - entry.startMs) / AMPLITUDE_WINDOW_MS)
                        .toInt().coerceIn(0, amps.size - 1)
                    return amps[idx]
                }
            }
            return 0f
        }
    }

    // endregion

    // 与播放列表中的 media item 一一对应的完成信号
    private val pendingItems = Collections.synchronizedList(mutableListOf<CompletableDeferred<Unit>>())
    private var completedCount = 0

    /** 播放推进到某个 media item 时回调 (index 从 0 开始), 供上层同步"当前分片" */
    var onItemStarted: ((mediaItemIndex: Int) -> Unit)? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        _playbackState.update { it.copy(status = PlaybackStatus.Buffering) }
                        stopPositionUpdates()
                    }
                    Player.STATE_READY -> {
                        val isPlaying = player.isPlaying
                        val duration = if (player.duration > 0) player.duration else playbackState.value.durationMs
                        _playbackState.update {
                            it.copy(
                                status = if (isPlaying) PlaybackStatus.Playing else PlaybackStatus.Paused,
                                durationMs = duration,
                                positionMs = player.currentPosition
                            )
                        }
                        if (isPlaying) startPositionUpdates() else stopPositionUpdates()
                    }
                    Player.STATE_ENDED -> {
                        // 整个播放队列播完: 完成所有剩余分片信号
                        completeAllPending()
                        stopPositionUpdates()
                        _playbackState.update {
                            it.copy(
                                status = PlaybackStatus.Ended,
                                positionMs = player.duration.coerceAtLeast(it.positionMs),
                                durationMs = if (player.duration > 0) player.duration else it.durationMs
                            )
                        }
                    }
                    Player.STATE_IDLE -> {
                        stopPositionUpdates()
                        _playbackState.update { it.copy(status = PlaybackStatus.Idle) }
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // 切到第 N 个 item 说明 [completedCount, N) 的分片已经播完
                val idx = player.currentMediaItemIndex
                completeUpTo(idx)
                if (idx >= 0) onItemStarted?.invoke(idx)
            }

            override fun onPlayerError(error: PlaybackException) {
                completeAllPending()
                stopPositionUpdates()
                _playbackState.update { it.copy(status = PlaybackStatus.Error, errorMessage = error.message) }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val status = if (isPlaying) PlaybackStatus.Playing else PlaybackStatus.Paused
                _playbackState.update { it.copy(status = status) }
                if (isPlaying) startPositionUpdates() else stopPositionUpdates()
            }
        })
    }

    /** 是否还有未播完的音频分片 */
    fun hasPendingAudio(): Boolean = synchronized(pendingItems) {
        completedCount < pendingItems.size
    }

    /**
     * 追加一段音频到播放列表 (fire-and-forget, 不阻塞等待播放完成).
     * 若播放器处于 IDLE/ENDED (队列为空), 会重新 prepare 开始播放.
     *
     * 分片之间插入一小段静音 (句间换气):
     * 分段合成的音频头尾都带语速上限的起音/收音, 无缝直拼会显得
     * "上一句尾音还没落, 下一句已经抢上来" — 120ms 静音模拟自然换气.
     * 静音也占用一个 pending item 槽位, 与媒体 item 一一对应.
     */
    @OptIn(UnstableApi::class)
    fun play(response: TTSResponse) {
        val bytes = if (response.format == AudioFormat.PCM) {
            pcmToWav(response.audioData, response.sampleRate ?: 24000)
        } else response.audioData

        val dataSourceFactory = DataSource.Factory { ByteArrayDataSource(bytes) }
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(Uri.EMPTY))

        val state = player.playbackState
        val startingNewQueue = state == Player.STATE_IDLE || state == Player.STATE_ENDED

        // 先登记 pending 信号再操作播放器: 避免播放器回调先于登记触发,
        // 导致完成计数与 pendingItems 错位 (hasPendingAudio 误判)
        if (startingNewQueue) {
            synchronized(pendingItems) { pendingItems.add(CompletableDeferred()) }
            // 上一个队列已经放完/被清空, 重新开一个队列
            player.clearMediaItems()
            resetItemTracking()
            player.addMediaSource(mediaSource)
            player.prepare()
            player.play()
            // 新队列从头播, 振幅时间轴从 0 重新累计
            appendTimelineEntry(response)
        } else {
            // 正在播放: 先垫一段静音再追加音频, 自然换气
            synchronized(pendingItems) {
                pendingItems.add(CompletableDeferred())
                pendingItems.add(CompletableDeferred())
            }
            player.addMediaSource(
                SilenceMediaSource.Factory()
                    .setDurationUs(INTER_CHUNK_SILENCE_US)
                    .createMediaSource()
            )
            player.addMediaSource(mediaSource)
            player.play()
            // 振幅时间轴同步登记: 静音占位 + 本分片
            appendSilenceTimelineEntry(INTER_CHUNK_SILENCE_US / 1000)
            appendTimelineEntry(response)
        }

        _playbackState.update {
            it.copy(
                status = PlaybackStatus.Buffering,
                durationMs = (response.duration?.times(1000))?.toLong() ?: it.durationMs
            )
        }
    }

    fun pause() = player.pause()
    fun resume() = player.play()

    /**
     * 外放路由开关.
     * true  → USAGE_MEDIA (扬声器, 与默认媒体流路由一致);
     * false → USAGE_VOICE_COMMUNICATION (听筒, 语音通话式私密路由).
     * 只切路由, 不接管音频焦点 (保持与旧行为一致).
     */
    fun setSpeakerphone(enabled: Boolean) {
        if (speakerphone == enabled) return
        speakerphone = enabled
        val attrs = AudioAttributes.Builder()
            .setUsage(if (enabled) C.USAGE_MEDIA else C.USAGE_VOICE_COMMUNICATION)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        player.setAudioAttributes(attrs, /* handleAudioFocus = */ false)
    }

    private var speakerphone = true

    fun stop() {
        runCatching { player.stop() }
        player.clearMediaItems()
        resetItemTracking()
        _playbackState.update { it.copy(status = PlaybackStatus.Idle) }
    }

    fun clear() {
        player.clearMediaItems()
        resetItemTracking()
    }

    fun release() {
        resetItemTracking()
        player.release()
    }

    fun seekBy(ms: Long) = player.seekTo(player.currentPosition + ms)
    fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
        _playbackState.update { it.copy(speed = speed) }
    }

    // region item 完成信号维护

    private fun completeUpTo(exclusiveIndex: Int) {
        synchronized(pendingItems) {
            while (completedCount < exclusiveIndex && completedCount < pendingItems.size) {
                pendingItems[completedCount].complete(Unit)
                completedCount++
            }
        }
    }

    private fun completeAllPending() {
        synchronized(pendingItems) {
            while (completedCount < pendingItems.size) {
                pendingItems[completedCount].complete(Unit)
                completedCount++
            }
        }
    }

    private fun resetItemTracking() {
        synchronized(pendingItems) {
            while (completedCount < pendingItems.size) {
                pendingItems[completedCount].complete(Unit)
                completedCount++
            }
            pendingItems.clear()
            completedCount = 0
        }
        // 振幅时间轴一并清空 (新队列/停止/清空/释放都会走到这里)
        synchronized(timeline) {
            timeline.clear()
            timelineDurationMs = 0L
        }
        _amplitude.value = 0f
    }

    // endregion

    private fun startPositionUpdates() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch(Dispatchers.Main.immediate) {
            while (true) {
                val position = player.currentPosition
                _playbackState.update {
                    it.copy(
                        positionMs = position,
                        durationMs = if (player.duration > 0) player.duration else it.durationMs
                    )
                }
                // 同步刷新当前振幅 (50ms 一拍, 与振幅窗口一致)
                _amplitude.value = amplitudeAt(position)
                delay(50)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
        _amplitude.value = 0f
    }

    private fun pcmToWav(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val out = ByteArrayOutputStream()
        with(out) {
            write("RIFF".toByteArray())
            write(intToBytes(36 + pcm.size))
            write("WAVE".toByteArray())
            write("fmt ".toByteArray())
            write(intToBytes(16))
            write(shortToBytes(1))
            write(shortToBytes(channels.toShort()))
            write(intToBytes(sampleRate))
            write(intToBytes(byteRate))
            write(shortToBytes((channels * bitsPerSample / 8).toShort()))
            write(shortToBytes(bitsPerSample.toShort()))
            write("data".toByteArray())
            write(intToBytes(pcm.size))
            write(pcm)
        }
        return out.toByteArray()
    }

    private fun intToBytes(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun shortToBytes(value: Short) = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        ((value.toInt() shr 8) and 0xFF).toByte()
    )
}
