/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.voice

/**
 * 语音通话状态机
 *
 * 状态流转:
 * Idle -> Listening -> Processing -> Speaking -> Listening -> ...
 *                                    |-> Error -> Idle
 */
enum class VoiceCallStatus {
    Idle,
    Listening,
    Processing,
    Speaking,
    /** AI 正在调用工具 */
    Working,
    Error
}

/**
 * 字幕历史条目
 */
data class SubtitleEntry(
    // 唯一 id: LazyColumn 的 key 使用, 避免 timestamp+hashCode 拼接碰撞导致崩溃
    val id: Long = System.nanoTime(),
    val role: SubtitleRole,
    val text: String,
    val translation: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    /** 是否为 AI 回复 (决定是否显示"重播语音"按钮) */
    val isAssistant: Boolean = false,
)

enum class SubtitleRole {
    User,
    Assistant,
}

/**
 * 语音通话 UI 状态
 */
data class VoiceCallUiState(
    val status: VoiceCallStatus = VoiceCallStatus.Idle,
    val userTranscript: String = "",
    val assistantText: String = "",
    val assistantTranslation: String = "",
    val errorMessage: String? = null,
    val amplitudes: List<Float> = emptyList(),
    val isMuted: Boolean = false,
    val autoSendEnabled: Boolean = true,
    val toolCallInfo: String? = null,
    /** 通话开始时间戳 (用于实时计时) */
    val callStartTime: Long = 0L,
    /** 字幕历史记录 (滚动显示) */
    val subtitleHistory: List<SubtitleEntry> = emptyList(),
    /** 字幕翻译开关 (关闭后不再调用翻译接口) */
    val translationEnabled: Boolean = true,
    /** 扬声器外放开关 (默认 true, 与 TTS 媒体流默认路由一致; 关闭走听筒) */
    val speakerOn: Boolean = true,
) {
    val isActive: Boolean
        get() = status != VoiceCallStatus.Idle
}