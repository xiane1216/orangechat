/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.notification

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.cancelNotification
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "DirectReplyReceiver"

/** RemoteInput 的 key，通知栏直接回复的文本从该 key 取出 */
const val DIRECT_REPLY_TEXT_KEY = "direct_reply_text"

/** 直接回复 Action 的 action 标识 */
const val ACTION_DIRECT_REPLY = "me.rerere.orangechat.DIRECT_REPLY"

/** intent extra：目标会话 id（由通知构建方塞入） */
const val EXTRA_DIRECT_REPLY_CONVERSATION_ID = "direct_reply_conversation_id"

/** intent extra：回复后要取消的通知 id（由通知构建方塞入） */
const val EXTRA_DIRECT_REPLY_NOTIFICATION_ID = "direct_reply_notification_id"

/**
 * 构建通知的"回复"按钮 Action：
 * 带 RemoteInput 的广播 PendingIntent，点击后可直接在通知栏输入文字回复。
 *
 * RemoteInput 要求 PendingIntent 可变（FLAG_MUTABLE），否则系统无法把输入内容填进 intent。
 *
 * @param conversationId 回复要写入的会话
 * @param notificationId 发送成功后由 DirectReplyReceiver 取消的通知 id
 */
fun buildDirectReplyAction(
    context: Context,
    conversationId: Uuid,
    notificationId: Int,
): NotificationCompat.Action {
    val remoteInput = RemoteInput.Builder(DIRECT_REPLY_TEXT_KEY)
        .setLabel("回复")
        .build()

    val intent = Intent(context, DirectReplyReceiver::class.java).apply {
        action = ACTION_DIRECT_REPLY
        putExtra(EXTRA_DIRECT_REPLY_CONVERSATION_ID, conversationId.toString())
        putExtra(EXTRA_DIRECT_REPLY_NOTIFICATION_ID, notificationId)
    }
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        conversationId.hashCode(),
        intent,
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    return NotificationCompat.Action.Builder(
        android.R.drawable.ic_menu_send,
        "回复",
        pendingIntent
    ).addRemoteInput(remoteInput).build()
}

/**
 * 通知栏"直接回复"的接收器：
 * 拿到 RemoteInput 里的文本后，走与聊天页 UI 相同的 ChatService.sendMessage 路径，
 * 把文本作为用户消息写入对应会话并触发生成，然后取消原通知避免残留。
 */
class DirectReplyReceiver : BroadcastReceiver(), KoinComponent {
    private val chatService: ChatService by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DIRECT_REPLY) return

        val conversationId = intent.getStringExtra(EXTRA_DIRECT_REPLY_CONVERSATION_ID)
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(DIRECT_REPLY_TEXT_KEY)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (conversationId == null || text.isEmpty()) {
            Log.w(TAG, "Direct reply ignored: missing conversationId or empty text")
            return
        }
        val notificationId = intent.getIntExtra(EXTRA_DIRECT_REPLY_NOTIFICATION_ID, -1)

        // goAsync 保证异步协程期间进程不被回收，写库+触发生成收尾后再 finish
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 与聊天页 UI 完全相同的发送入口：写用户消息并触发生成（answer 默认 true）
                chatService.sendMessage(conversationId, listOf(me.rerere.ai.ui.UIMessagePart.Text(text)))

                // 等待生成收尾（generationDone 在生成结束/失败时发出），
                // 后台场景下生成完成会由 ChatService 既有逻辑弹出带 AI 回复内容的通知；
                // 长时间生成则超时放行，生成仍在 appScope 中继续，不会被中断
                withTimeoutOrNull(15_000) {
                    chatService.generationDoneFlow.first { it == conversationId }
                }

                // 取消原通知，避免回复后残留旧内容
                if (notificationId != -1) {
                    context.cancelNotification(notificationId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Direct reply failed, conversationId=$conversationId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
