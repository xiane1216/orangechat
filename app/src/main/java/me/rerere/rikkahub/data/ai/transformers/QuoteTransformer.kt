/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 引用回复转换器:
 * 发送前把用户消息里的 Quote 部分合并为文本前缀,
 * 避免把 Quote 直接发送给模型
 */
object QuoteTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages.map { message ->
            if (message.role != MessageRole.USER) return@map message
            val quote = message.parts.filterIsInstance<UIMessagePart.Quote>().firstOrNull()
                ?: return@map message
            val originalText = message.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
            val mergedText = buildString {
                append("引用")
                append(quote.quotedAuthor)
                append("的消息：\n")
                append(quote.quotedText)
                append("\n\n我的回复：")
                append(originalText)
            }
            val otherParts = message.parts.filter { part ->
                part !is UIMessagePart.Text && part !is UIMessagePart.Quote
            }
            message.copy(parts = listOf(UIMessagePart.Text(mergedText)) + otherParts)
        }
    }
}
