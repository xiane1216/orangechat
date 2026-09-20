/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.tts.controller

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting

private const val TAG = "TtsController"

/**
 * TTS 控制器（重构版）
 * - 负责文本分片、预取合成、排队播放与状态上报
 * - 对外 API 与原版兼容
 */
class TtsController(
    context: Context,
    private val ttsManager: TTSManager
) {
    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 组件
    private val chunker = TextChunker(maxChunkLength = 160)
    private val synthesizer = TtsSynthesizer(ttsManager)
    private val audio = AudioPlayer(context)

    // Provider & 作业
    private var currentProvider: TTSProviderSetting? = null
    private var workerJob: Job? = null
    private var isPaused = false

    // 队列与缓存
    // 缓存 key 为 "provider id + 文本内容": 同一段文字无论被 enqueue 多少次只合成一次,
    // 且切换 provider 后不会错误命中旧 provider 的音频.
    private val queue: java.util.concurrent.ConcurrentLinkedQueue<TtsChunk> = java.util.concurrent.ConcurrentLinkedQueue()
    private val allChunks: MutableList<TtsChunk> = mutableListOf()
    private val cache = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<TTSResponse>>()
    private var lastPrefetchedIndex: Int = -1

    // 行为参数
    // 预取窗口: 流式场景下按句批量入队, 窗口太小会导致 worker 现场等网络合成 → 播放静音间隙
    private val prefetchCount = 8

    // 状态流（保留与旧版兼容的 StateFlow）
    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _currentChunk = MutableStateFlow(0)
    val currentChunk: StateFlow<Int> = _currentChunk.asStateFlow()

    private val _totalChunks = MutableStateFlow(0)
    val totalChunks: StateFlow<Int> = _totalChunks.asStateFlow()

    // 统一播放状态（融合音频播放 + 分片进度）
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    init {
        // 同步底层播放器状态到统一状态，并补充分片信息
        scope.launch {
            audio.playbackState.collectLatest { audioState ->
                _playbackState.update {
                    audioState.copy(
                        currentChunkIndex = _currentChunk.value,
                        totalChunks = _totalChunks.value,
                        status = if (!_isAvailable.value) PlaybackStatus.Idle else audioState.status
                    )
                }
            }
        }
        // 播放器推进到第 N 个分片时同步"当前分片" (1-based)
        audio.onItemStarted = { index -> _currentChunk.update { index + 1 } }
    }

    /** 选择/取消选择 Provider */
    fun setProvider(provider: TTSProviderSetting?) {
        currentProvider = provider
        _isAvailable.update { provider != null }
        if (provider == null) stop()
    }

    /**
     * 朗读文本
     * - flush=true: 清空当前进度并重新开始
     * - flush=false: 继续队列，追加朗读
     * - dedupe=true: 只对"尚未播放的排队内容"去重, 已播过的文字允许再次入队
     *   (重播场景传 dedupe=false)
     */
    fun speak(text: String, flush: Boolean = true, dedupe: Boolean = true) {
        if (text.isBlank()) return
        val provider = currentProvider
        if (provider == null) {
            _error.update { "No TTS provider selected" }
            return
        }

        val newChunks = chunker.split(text)
        if (newChunks.isEmpty()) return

        if (flush) {
            internalReset()
        }

        // 内容级去重: 只跳过"文本与队列中尚未播放的 chunk 重复"的分片,
        // 防止流式场景同一段文字被重复加入播放队列.
        // 注意不去重"已播放过"的内容 — 否则同一轮里 AI 重复的句子第二遍会无声,
        // 字幕历史的"重播语音"也会被吞掉.
        val pendingTexts = HashSet<String>()
        queue.forEach { pendingTexts.add(it.text) }
        val dedupedChunks = if (dedupe) {
            newChunks.filter { chunk -> pendingTexts.add(chunk.text) }
        } else {
            newChunks
        }
        if (dedupedChunks.isEmpty()) return

        // 追加时，重映射 index 以保持全局顺序
        val startIndex = (allChunks.lastOrNull()?.index ?: -1) + 1
        val remapped = dedupedChunks.mapIndexed { i, c -> c.copy(index = startIndex + i) }
        allChunks.addAll(remapped)
        queue.addAll(remapped)

        if (flush) {
            _currentChunk.update { 0 }
        }
        // totalChunks 语义为"本会话累计入队分片数", 只增不减,
        // 修复旧版 worker 里用 queue.size 反复改小 total 导致 current > total 的显示错乱
        _totalChunks.update { allChunks.size }
        _error.update { null }

        _playbackState.update {
            it.copy(
                currentChunkIndex = _currentChunk.value,
                totalChunks = _totalChunks.value,
                status = PlaybackStatus.Buffering
            )
        }

        if (workerJob?.isActive != true) startWorker()
        prefetchFrom(0)
    }

    private fun internalReset() {
        // Reset current session while keeping provider availability
        workerJob?.cancel()
        audio.stop()
        audio.clear()
        isPaused = false
        queue.clear()
        allChunks.clear()
        cache.values.forEach { it.cancel(CancellationException("Reset")) }
        cache.clear()
        lastPrefetchedIndex = -1
        _isSpeaking.update { false }
        _currentChunk.update { 0 }
        _totalChunks.update { 0 }
        _error.update { null }
        _playbackState.update { PlaybackState(status = PlaybackStatus.Idle) }
    }

    /** 暂停播放（保留进度） */
    fun pause() {
        isPaused = true
        audio.pause()
        _playbackState.update { it.copy(status = PlaybackStatus.Paused) }
    }

    /** 恢复播放 */
    fun resume() {
        isPaused = false
        audio.resume()
        _playbackState.update { it.copy(status = PlaybackStatus.Playing) }
    }

    /** 快进当前音频 */
    fun fastForward(ms: Long = 5_000) {
        audio.seekBy(ms)
    }

    /** 设置播放速度 */
    fun setSpeed(speed: Float) {
        audio.setSpeed(speed)
    }

    /** 外放路由开关 (true=扬声器, false=听筒) */
    fun setSpeakerphone(enabled: Boolean) {
        audio.setSpeakerphone(enabled)
    }

    /** 跳过下一段（不打断当前正在播放） */
    fun skipNext() {
        if (queue.isNotEmpty()) {
            queue.poll()
        }
    }

    /** 停止并清空状态 */
    fun stop() {
        workerJob?.cancel()
        audio.stop()
        audio.clear()
        isPaused = false
        queue.clear()
        allChunks.clear()
        cache.values.forEach { it.cancel(CancellationException("Stopped")) }
        cache.clear()
        lastPrefetchedIndex = -1
        _isSpeaking.update { false }
        _currentChunk.update { 0 }
        _totalChunks.update { 0 }
        _playbackState.update { PlaybackState(status = PlaybackStatus.Idle) }
    }

    /** 释放资源 */
    fun dispose() {
        stop()
        scope.cancel()
        audio.release()
    }

    // region 内部：播放调度
    private fun startWorker() {
        val provider = currentProvider
        if (provider == null) {
            _error.update { "No TTS provider selected" }
            return
        }

        workerJob = scope.launch {
            _isSpeaking.update { true }
            try {
                while (isActive) {
                    if (isPaused) {
                        delay(80)
                        continue
                    }

                    val chunk = queue.poll()
                    if (chunk == null) {
                        // 合成队列空了, 但播放列表里可能还有音频在放:
                        // 等它排空再退出, 避免提前置 Ended 把后续内容误判成"播完了"
                        if (audio.hasPendingAudio()) {
                            delay(100)
                            continue
                        }
                        // 流式场景 (语音通话) 下一批句子可能稍后才 enqueue,
                        // 立即退出会导致 worker 反复重启, 且 finally 里上报的
                        // Ended 是假信号. 宽限 600ms 后再确认一次才真正退出.
                        delay(600)
                        if (queue.isNotEmpty() || audio.hasPendingAudio()) continue
                        break
                    }

                    val response = try {
                        awaitOrCreate(chunk, provider)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // 合成失败: 清掉失败的缓存结果并重试一次,
                        // 之前直接 continue 会静默丢掉这段内容 ("没读完就停了"的主因之一)
                        Log.e(TAG, "Synthesis error (first attempt), retrying", e)
                        cache.remove(cacheKey(provider, chunk.text))
                        try {
                            delay(300)
                            awaitOrCreate(chunk, provider)
                        } catch (retryErr: Exception) {
                            if (retryErr is CancellationException) throw retryErr
                            Log.e(TAG, "Synthesis retry failed, skip chunk", retryErr)
                            _error.update { retryErr.message ?: "TTS synthesis error" }
                            continue
                        }
                    }

                    // 入队播放 (fire-and-forget), 由 AudioPlayer 的播放列表无缝衔接.
                    // 不再等当前分片播完才处理下一个, 消除分片间的 re-prepare 停顿.
                    try {
                        audio.play(response)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Playback error", e)
                        _error.update { e.message ?: "Audio playback error" }
                    }
                }
            } finally {
                _isSpeaking.update { false }
                // 只有"自然播完退出"才上报 Ended; 被 stop()/internalReset() cancel 的
                // worker 不能写 Ended, 否则会晚于 stop() 的 Idle 写入, 把状态覆盖掉,
                // 下一轮 waitForTtsToFinish 看到 Ended 会误判"已经播完"提前收尾.
                if (isActive && queue.isEmpty() && !audio.hasPendingAudio()) {
                    _playbackState.update { it.copy(status = PlaybackStatus.Ended) }
                }
            }
        }
    }

    private fun prefetchFrom(startIndex: Int) {
        val provider = currentProvider ?: return
        val begin = startIndex.coerceAtLeast(lastPrefetchedIndex + 1)
        val endExclusive = (begin + prefetchCount).coerceAtMost(allChunks.size)
        if (begin >= endExclusive) return

        for (i in begin until endExclusive) {
            val chunk = allChunks.getOrNull(i) ?: continue
            // 缓存 key 用 "provider id + 文本内容": 只合成一次, 且不受 provider 切换影响
            cache.computeIfAbsent(cacheKey(provider, chunk.text)) {
                scope.async(Dispatchers.IO) { synthesizer.synthesize(provider, chunk) }
            }
        }
        lastPrefetchedIndex = endExclusive - 1
    }

    private fun cacheKey(provider: TTSProviderSetting, text: String): String = "${provider.id}:$text"

    private suspend fun awaitOrCreate(chunk: TtsChunk, provider: TTSProviderSetting): TTSResponse {
        val deferred = cache.computeIfAbsent(cacheKey(provider, chunk.text)) {
            scope.async(Dispatchers.IO) { synthesizer.synthesize(provider, chunk) }
        }
        return deferred.await()
    }
    // endregion
}
