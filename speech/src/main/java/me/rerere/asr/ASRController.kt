/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.asr

import kotlinx.coroutines.flow.StateFlow

interface ASRController {
    val state: StateFlow<ASRState>
    fun start(onTranscriptChange: (String) -> Unit)
    fun stop()
    fun dispose()

    /**
     * 清空已累积的转写文本.
     *
     * 语音通话场景下, ASR 全程不停止 (start() 只调一次), 但每一轮对话
     * (用户说完 → AI 回复 → 回到聆听) 之间需要把上一轮的转写清掉,
     * 否则下一轮用户说话时, publishTranscript 会把历史文字一起拼出来,
     * 导致"我说2但显示12"的累积 bug.
     */
    fun resetTranscript()
}
