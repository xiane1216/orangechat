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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.ui.theme.LocalDarkMode

@Composable
fun AssistantBackground(setting: Settings, hazeState: HazeState? = null) {
    val assistant = setting.getCurrentAssistant()
    // 背景色读取深浅色颜色方案生效后的值
    val chatBackgroundColor = LocalDisplaySettings.current.chatBackgroundColor?.let { it.toComposeColor() }
    // 注册为 haze 模糊源: 气泡磨砂玻璃需要从背景图/背景色取色
    val sourceModifier = if (hazeState != null) Modifier.hazeSource(hazeState) else Modifier
    // 深浅色状态: RikkahubTheme 内提供, 跟随系统与手动深色模式均会生效
    val isDarkMode = LocalDarkMode.current
    // 深色模式聊天背景图压暗滤镜 (模仿微信: 明显变暗但壁纸仍隐约可见)
    // 饱和度约 0.65 + 亮度约 0.6, 高光随亮度一并压低; 浅色模式不应用, 背景保持原图
    val darkImageColorFilter = remember {
        val matrix = ColorMatrix().apply { setToSaturation(0.65f) }
        // 亮度矩阵为对角阵, 等价于缩放 RGB 三行的全部元素, 组合出"先降饱和再压亮度"的矩阵
        for (row in 0 until 3) {
            for (col in 0 until 5) {
                matrix.values[row * 5 + col] *= 0.6f
            }
        }
        ColorFilter.colorMatrix(matrix)
    }

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
                // 在 Image 层直接应用压暗滤镜, haze 磨砂采样到的也是压暗后的背景, 气泡与背景视觉统一
                colorFilter = if (isDarkMode) darkImageColorFilter else null,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(backgroundOpacity)
                    .then(sourceModifier)
            )
        }

        chatBackgroundColor != null -> {
            // 用户设置了自定义纯色背景
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(chatBackgroundColor)
                    .then(sourceModifier)
            )
        }
    }
}
