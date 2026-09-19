/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

/**
 * 心跳机制设置.
 *
 * 与"主动消息"独立: 心跳只在用户闲置 (最后一条消息后) 达到 idleMinutes 时触发一次,
 * 正常聊天过程中不会触发. 触发后 AI 可自主决定调用工具/发消息/保持沉默.
 */
@Serializable
data class HeartbeatSetting(
    val enabled: Boolean = false,
    /** 用户闲置多少分钟后触发心跳, 默认 30 */
    val idleMinutes: Int = 30,
)
