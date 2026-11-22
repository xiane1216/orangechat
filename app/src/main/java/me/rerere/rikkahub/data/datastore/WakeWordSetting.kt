/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

/**
 * 语音唤醒设置.
 *
 * 全天候离线监听唤醒词 (sherpa-onnx KWS, 纯本地识别不联网):
 * 用户喊昵称 -> 弹到聊天页 -> 以用户身份插入昵称消息 -> AI 自动回复.
 */
@Serializable
data class WakeWordSetting(
    val enabled: Boolean = false,
    /** 唤醒词 (昵称), 仅支持汉字, 默认"小橘" */
    val wakeWord: String = "小橘",
    /** 触发冷却秒数, 防止连喊/误触连发消息, 默认 30 */
    val cooldownSeconds: Int = 30,
    /**
     * 识别灵敏度档位: 0=高灵敏(易唤醒也易误触), 1=均衡(默认), 2=抗噪(防误触)
     * 映射到 KWS 的 keywordsThreshold/keywordsScore, 档位越高要求匹配置信度越高
     */
    val sensitivity: Int = 1,
)
