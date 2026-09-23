/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.data.datastore.getCurrentAssistant

@Composable
fun AssistantBackground(setting: Settings) {
    val assistant = setting.getCurrentAssistant()
    val chatBackgroundColor = setting.displaySetting.chatBackgroundColor?.let { it.toComposeColor() }

    when {
        assistant.background != null -> {
            // 用户手动为助手设置的背景图，优先级最高
            // 直接显示原图，不再叠加主题色渐变遮罩（浅色模式会导致背景图发白、深色模式发黑）
            // 如需调节背景浓淡，使用助手设置里的"背景不透明度"滑条
            val backgroundOpacity = assistant.backgroundOpacity.coerceIn(0f, 1f)
            AsyncImage(
                model = assistant.background,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(backgroundOpacity)
            )
        }

        chatBackgroundColor != null -> {
            // 用户设置了自定义纯色背景
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(chatBackgroundColor)
            )
        }
    }
}
