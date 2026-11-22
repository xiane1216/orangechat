/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * Transforms VoiceMessage parts into Text parts for AI providers.
 * Voice messages are displayed as voice bubbles in the UI but sent as text to the AI.
 */
object VoiceMessageTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages.map { message ->
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.VoiceMessage -> {
                            UIMessagePart.Text(text = transformVoicePart(part))
                        }
                        else -> part
                    }
                }
            )
        }
    }

    /**
     * 语音条 -> 注入文本: 让 AI 明确知道这是语音消息而非打字, 并带上本地识别的语气与声音事件。
     * 格式示例:
     *   [语音消息 · 5″]
     *   内容：老公……我今天好难过啊
     *   语气：难过
     *   声音：哭声,叹气
     * 语气/声音由本地 SenseVoice 识别, 没有识别结果时相应行省略。
     */
    private fun transformVoicePart(part: UIMessagePart.VoiceMessage): String {
        val sb = StringBuilder()
        sb.append("[语音消息").append(if (part.duration > 0) " · ${part.duration}″" else "").append("]")
        sb.append("\n内容：").append(part.transcript.ifBlank { "（无法转写）" })
        if (part.emotion.isNotBlank()) {
            sb.append("\n语气：").append(part.emotion).append("（本地识别）")
        }
        if (part.voiceEvents.isNotBlank()) {
            sb.append("\n声音：").append(part.voiceEvents.replace(",", "、"))
        }
        return sb.toString()
    }
}