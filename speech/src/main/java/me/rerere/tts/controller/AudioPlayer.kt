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
    }

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var positionJob: Job? = null

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
    }

    // endregion

    private fun startPositionUpdates() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch(Dispatchers.Main.immediate) {
            while (true) {
                _playbackState.update {
                    it.copy(
                        positionMs = player.currentPosition,
                        durationMs = if (player.duration > 0) player.duration else it.durationMs
                    )
                }
                delay(100)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
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
