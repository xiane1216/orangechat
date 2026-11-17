/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.ImageProvider
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.Image
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextOverflow
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.repository.ConversationRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.context.GlobalContext
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max

// 深灰文字 / 玫瑰粉点缀
private val WIDGET_TEXT_MAIN = Color(0xFF3B3735)
private val WIDGET_TEXT_ACCENT = Color(0xFFD3A9A3)

// 头像位图渲染边长(px)
private const val AVATAR_BITMAP_SIZE = 128

/**
 * 小组件渲染所需状态
 */
internal data class AnniversaryWidgetState(
    val avatarBitmap: Bitmap,
    val dayCount: Int?,
    val lastMessage: String?,
    val conversationId: String?,
)

/**
 * 点击小组件跳转会话用的 Intent (与 ChatService 通知的跳转写法一致):
 * RouteActivity 为 singleTop 语义配合 CLEAR_TOP/SINGLE_TOP, 活着时走 onNewIntent 读取 conversationId
 */
internal fun buildLaunchIntent(context: Context, conversationId: String?): Intent =
    Intent(context, RouteActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        conversationId?.let { putExtra("conversationId", it) }
    }

/**
 * 组装小组件数据: 助手头像位图 / 在一起天数 / 最新会话最后一条消息
 */
internal suspend fun loadWidgetState(context: Context): AnniversaryWidgetState =
    withContext(Dispatchers.IO) {
        val koin = GlobalContext.get()
        val settingsStore = koin.get<SettingsStore>()
        val conversationRepo = koin.get<ConversationRepository>()

        val settings = settingsStore.settingsFlowRaw.first()
        val assistant = settings.getCurrentAssistant()

        // 天数 = 今天 - 在一起开始日期 + 1 (第1天=当天); 未设置或解析失败为 null
        val dayCount = settings.displaySetting.anniversaryStartDate?.let { raw ->
            runCatching {
                val days = ChronoUnit.DAYS.between(LocalDate.parse(raw), LocalDate.now()) + 1L
                days.coerceAtLeast(1L).toInt()
            }.getOrNull()
        }

        // 最新会话: id 用于点击跳转; 最后一条 USER/ASSISTANT 消息文本用于中条预览
        val latestConversation = runCatching {
            conversationRepo.getRecentConversations(assistant.id, limit = 1).firstOrNull()
        }.getOrNull()

        val lastMessage = latestConversation?.messageNodes
            ?.asReversed()
            ?.firstNotNullOfOrNull { node ->
                node.messages.getOrNull(node.selectIndex)
                    ?.takeIf { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
                    ?.let { message ->
                        val text = if (message.role == MessageRole.USER) {
                            message.parts.filterIsInstance<UIMessagePart.Text>()
                                .joinToString("\n") { it.text }
                        } else {
                            message.toText()
                        }
                        stripMarkdown(text).ifBlank { null }
                    }
            }

        val conversationId = latestConversation?.id?.toString()

        AnniversaryWidgetState(
            avatarBitmap = renderAvatarBitmap(assistant),
            dayCount = dayCount,
            lastMessage = lastMessage,
            conversationId = conversationId,
        )
    }

/**
 * 去掉常见 markdown 符号, 压成一行便于小组件预览
 */
internal fun stripMarkdown(text: String): String {
    if (text.isBlank()) return ""
    val stripped = text
        .replace(Regex("```[\\s\\S]*?```"), " [代码] ")
        .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), " [图片] ")
        .replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("[*_~`#>|]"), "")
        .replace(Regex("^\\s*\\d+\\.\\s"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    return stripped.take(120)
}

/**
 * 头像渲染:
 * - Image 头像 (本地文件/file:// URI/http(s)/base64 data URI) 解码成功 -> 圆角位图
 * - Emoji 头像 -> 浅粉圆底 + emoji 字符
 * - 取不到 -> 粉渐变圆形底 + 助手名首字
 */
private fun renderAvatarBitmap(assistant: Assistant): Bitmap {
    val source = when (val avatar = assistant.avatar) {
        is Avatar.Image -> loadBitmapFromUrl(avatar.url)
        is Avatar.Emoji -> drawEmojiAvatar(avatar.content)
        is Avatar.Dummy -> null
    }
    return if (source != null) {
        roundedCornerBitmap(source)
    } else {
        drawFallbackAvatar(assistant.name)
    }
}

private fun drawEmojiAvatar(content: String): Bitmap? {
    val char = content.trim().firstOrNull() ?: return null
    val size = AVATAR_BITMAP_SIZE
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2E3DF.toInt() }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, circlePaint)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size * 0.62f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    val metrics = textPaint.fontMetrics
    val baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f
    canvas.drawText(char.toString(), size / 2f, baseline, textPaint)
    return bitmap
}

private fun drawFallbackAvatar(name: String): Bitmap {
    val size = AVATAR_BITMAP_SIZE
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, 0f, size.toFloat(),
            0xFFEFD9D4.toInt(), 0xFFD3A9A3.toInt(),
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, gradientPaint)
    val char = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = size * 0.42f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val metrics = textPaint.fontMetrics
    val baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f
    canvas.drawText(char, size / 2f, baseline, textPaint)
    return bitmap
}

/**
 * 按目标边长居中裁剪并做圆角(约 1/3 边长, 接近圆形观感)
 */
private fun roundedCornerBitmap(source: Bitmap): Bitmap {
    val size = AVATAR_BITMAP_SIZE
    val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val scale = max(
        size.toFloat() / source.width,
        size.toFloat() / source.height,
    )
    val matrix = android.graphics.Matrix().apply {
        setScale(scale, scale)
        postTranslate(
            (size - source.width * scale) / 2f,
            (size - source.height * scale) / 2f,
        )
    }
    paint.shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        setLocalMatrix(matrix)
    }
    val radius = size * 0.32f
    canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), radius, radius, paint)
    return output
}

private fun loadBitmapFromUrl(url: String): Bitmap? = runCatching {
    when {
        url.startsWith("data:") -> {
            // base64 data URI
            val base64 = url.substringAfter(',', url)
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            decodeSampled(bytes)
        }

        url.startsWith("http://") || url.startsWith("https://") -> {
            val client = GlobalContext.get().get<OkHttpClient>()
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                decodeSampled(response.body.bytes())
            }
        }

        else -> {
            // 本地文件路径或 file:// URI (FilesManager 存的头像在应用私有目录)
            val path = url.removePrefix("file://")
            val file = File(path)
            if (!file.exists()) null else decodeSampledFile(file.absolutePath)
        }
    }
}.getOrNull()

private fun decodeSampled(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val sample = calculateInSampleSize(bounds, AVATAR_BITMAP_SIZE)
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private fun decodeSampledFile(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val sample = calculateInSampleSize(bounds, AVATAR_BITMAP_SIZE)
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(path, options)
}

private fun calculateInSampleSize(bounds: BitmapFactory.Options, target: Int): Int {
    var sample = 1
    val half = max(bounds.outWidth, bounds.outHeight) / 2
    while (half / sample >= target) {
        sample *= 2
    }
    return sample
}

/**
 * 在一起的日子桌面小组件
 * - 小方块(约 110dp): 助手头像 + "第 N 天"
 * - 中条(约 250dp): 助手头像 + 最近一句聊天内容(单行省略) + "第 N 天"小字
 * 通过 SizeMode.Responsive 按实际尺寸在两种布局间切换
 */
class AnniversaryWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(110.dp, 110.dp), // 小方块
            DpSize(250.dp, 110.dp), // 中条
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = loadWidgetState(context)
        provideContent {
            val isMedium = LocalSize.current.width >= 180.dp
            WidgetCard(state) {
                if (isMedium) MediumContent(state) else SmallContent(state)
            }
        }
    }

    /**
     * 白色 85% 透明度 + 24dp 圆角底, 整卡可点击跳转对应会话
     */
    @Composable
    private fun WidgetCard(state: AnniversaryWidgetState, content: @Composable () -> Unit) {
        val clickContext = LocalContext.current
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ImageProvider(R.drawable.anniversary_widget_card_bg))
                .clickable(
                    actionStartActivity(buildLaunchIntent(clickContext, state.conversationId))
                ),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }

    /**
     * 小方块布局: 头像 + "第 N 天"
     */
    @Composable
    private fun SmallContent(state: AnniversaryWidgetState) {
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(state.avatarBitmap),
                contentDescription = null,
                modifier = GlanceModifier.size(52.dp),
                contentScale = ContentScale.FillBounds,
            )
            state.dayCount?.let { day ->
                Spacer(modifier = GlanceModifier.height(6.dp))
                Text(
                    text = "第 $day 天",
                    style = TextStyle(
                        color = ColorProvider(WIDGET_TEXT_MAIN),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
            }
        }
    }

    /**
     * 中条布局: 头像 + 最近一句聊天内容(单行省略) + "第 N 天"小字
     */
    @Composable
    private fun MediumContent(state: AnniversaryWidgetState) {
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(state.avatarBitmap),
                contentDescription = null,
                modifier = GlanceModifier.size(44.dp),
                contentScale = ContentScale.FillBounds,
            )
            Spacer(modifier = GlanceModifier.width(12.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = state.lastMessage
                        ?: state.dayCount?.let { "第 $it 天" }
                        ?: "在一起的日子还没设置",
                    style = TextStyle(
                        color = ColorProvider(WIDGET_TEXT_MAIN),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                state.dayCount?.let { day ->
                    Spacer(modifier = GlanceModifier.height(2.dp))
                    Text(
                        text = "第 $day 天",
                        style = TextStyle(
                            color = ColorProvider(WIDGET_TEXT_ACCENT),
                            fontSize = 11.sp,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
