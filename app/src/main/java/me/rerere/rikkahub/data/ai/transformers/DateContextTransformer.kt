/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import java.time.LocalDate
import java.time.ZoneId
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.dates.AnniversaryCalendar

/**
 * 时间感知注入转换器
 *
 * 把纪念日/生日/农历/节日/节气卡片 (<date_context>) 注入到最后一条消息末尾,
 * 只动末尾一条消息, 不碰 system 消息和更早历史, 保持 prompt 前缀稳定。
 */
object DateContextTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        // 总开关关闭时不注入
        if (!ctx.settings.displaySetting.anniversaryEnabled) return messages
        val today = LocalDate.now(ZoneId.systemDefault())
        val card = AnniversaryCalendar.buildDateCard(ctx.settings.displaySetting, today) ?: return messages

        // 幂等保护: 最后一条消息已带卡片时不重复追加 (防同一条消息被转换两次)
        if (messages.lastOrNull()?.toText()?.contains("<date_context>") == true) return messages

        val last = messages.lastOrNull()
            ?: return listOf(UIMessage.user(card))
        return if (last.role == MessageRole.USER) {
            // 正常聊天: 卡片追加到最后一条 USER 消息的最后一个 Text part 末尾
            val textIndex = last.parts.indexOfLast { it is UIMessagePart.Text }
            if (textIndex >= 0) {
                messages.dropLast(1) + last.copy(
                    parts = last.parts.mapIndexed { index, part ->
                        if (index == textIndex) {
                            val textPart = part as UIMessagePart.Text
                            textPart.copy(text = textPart.text + "\n\n" + card)
                        } else {
                            part
                        }
                    }
                )
            } else {
                // 最后一条 USER 消息没有 Text part (如纯图片), 补一个 Text part
                messages.dropLast(1) + last.copy(parts = last.parts + UIMessagePart.Text(card))
            }
        } else {
            // 心跳/主动消息等场景: 最后一条不是 USER, 在列表末尾加一条 USER 卡片消息
            messages + UIMessage.user(card)
        }
    }
}
