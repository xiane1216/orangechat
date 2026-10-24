/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.context

import dev.chrisbanes.haze.HazeState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 聊天页的 haze 模糊状态, 供消息气泡/思考链卡片等子组件做"磨砂玻璃"背景效果。
 * null = 当前页面没有可用的模糊源 (磨砂降级为普通半透明)。
 */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }
