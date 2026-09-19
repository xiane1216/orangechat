/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.voice

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 浅粉色语音圆球
 *
 * - 低饱和度浅粉色，边缘模糊渐变
 * - AI 说话时边缘有波纹扩散动画
 * - 聆听时呼吸缩放
 * - 支持浅色/深色模式
 */
@Composable
fun VoiceOrb(
    modifier: Modifier = Modifier,
    amplitudes: List<Float> = emptyList(),
    status: VoiceCallStatus = VoiceCallStatus.Idle,
    isDarkMode: Boolean = false,
    size: Dp = 180.dp,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "voice_orb")

    // 呼吸缩放
    val breatheDurationMs = when (status) {
        VoiceCallStatus.Listening -> 2000
        VoiceCallStatus.Speaking -> 1200
        VoiceCallStatus.Processing -> 1500
        else -> 4000
    }
    val breathe by infiniteTransition.animateFloat(
        initialValue = 0.90f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(breatheDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )

    // 波纹扩散相位 (AI 说话时)
    val ripplePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple"
    )

    // 振幅 -> 强度 (增强音波起伏幅度)
    val currentAmplitude = if (amplitudes.isNotEmpty()) {
        amplitudes.takeLast(4).average().toFloat()
    } else {
        0f
    }
    val intensity = when (status) {
        VoiceCallStatus.Listening -> (currentAmplitude * 1.0f + 0.15f).coerceIn(0.15f, 0.8f)
        VoiceCallStatus.Speaking -> 0.35f + (currentAmplitude * 0.6f)
        VoiceCallStatus.Processing -> 0.25f
        VoiceCallStatus.Working -> 0.3f
        VoiceCallStatus.Error -> 0.08f
        VoiceCallStatus.Idle -> 0.06f
    }

    val scale = breathe + intensity * 0.3f

    // 浅粉色调色板
    val orbColor = if (isDarkMode) Color(0xFFE8B5B5) else Color(0xFFE8A5A5)
    val glowColor = if (isDarkMode) Color(0xFFD4A0A0) else Color(0xFFF0C0C0)
    val rippleColor = if (isDarkMode) Color(0xFFD4A0A0) else Color(0xFFE8B5B5)

    Canvas(
        modifier = modifier.size(size)
    ) {
        val canvasSize = this.size.minDimension
        val center = Offset(canvasSize / 2, canvasSize / 2)
        val baseRadius = canvasSize / 2 * 0.62f

        // AI 说话时的波纹扩散 (增强: 更多波纹, 更大扩散范围)
        if (status == VoiceCallStatus.Speaking || status == VoiceCallStatus.Listening) {
            val rippleCount = 4
            for (i in 0 until rippleCount) {
                val phase = (ripplePhase + i.toFloat() / rippleCount) % 1f
                val rippleRadius = baseRadius * scale * (1f + phase * 1.2f)
                val rippleAlpha = (1f - phase) * 0.4f * intensity
                drawCircle(
                    color = rippleColor.copy(alpha = rippleAlpha),
                    radius = rippleRadius,
                    center = center,
                    style = Stroke(width = 4f)
                )
            }
        }

        // 外层模糊光晕 (多层半透明圆, 制造边缘模糊渐变)
        for (i in 6 downTo 1) {
            val layerRadius = baseRadius * scale * (1f + i * 0.18f)
            val alpha = (0.04f / i) * (1f + intensity * 1.5f)
            drawCircle(
                color = glowColor.copy(alpha = alpha.coerceAtMost(0.2f)),
                radius = layerRadius,
                center = center
            )
        }

        // 主球体: 径向渐变 (中心更亮, 边缘渐淡)
        val mainRadius = baseRadius * scale
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    orbColor.copy(alpha = 0.9f),
                    orbColor.copy(alpha = 0.6f),
                    orbColor.copy(alpha = 0.3f),
                    orbColor.copy(alpha = 0.1f),
                ),
                center = center,
                radius = mainRadius * 1.3f
            ),
            radius = mainRadius,
            center = center
        )

        // 中心高亮
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.5f * (0.5f + intensity)),
                    Color.White.copy(alpha = 0.15f),
                    Color.Transparent
                ),
                center = Offset(
                    center.x - mainRadius * 0.15f,
                    center.y - mainRadius * 0.15f
                ),
                radius = mainRadius * 0.5f
            ),
            radius = mainRadius * 0.5f,
            center = Offset(
                center.x - mainRadius * 0.15f,
                center.y - mainRadius * 0.15f
            )
        )
    }
}
