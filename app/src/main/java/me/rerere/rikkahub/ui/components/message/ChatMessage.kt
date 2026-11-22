/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.message
 
import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.PlayCircle
import me.rerere.hugeicons.stroke.PauseCircle
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.Translate
import me.rerere.hugeicons.stroke.Video01
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import me.rerere.rikkahub.ui.context.LocalHazeState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.tts.model.PlaybackStatus
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.Image
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.copyMessageToClipboard
import me.rerere.rikkahub.utils.extractQuotedContentAsText
import me.rerere.rikkahub.utils.openUrl
import coil3.compose.AsyncImage
import me.rerere.rikkahub.utils.splitIntoBubbleSegments
import me.rerere.rikkahub.utils.urlDecode
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
 
@Composable
fun ChatMessage(
    node: MessageNode,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    model: Model? = null,
    assistant: Assistant? = null,
    lastMessage: Boolean = false,
    listScrolling: Boolean = false,
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onQuote: ((message: UIMessage, blockIndex: Int, segmentIndex: Int) -> Unit)? = null,
) {
    val message = node.messages[node.selectIndex]
    val settings = LocalDisplaySettings.current
    val textStyle = LocalTextStyle.current.copy(
        fontSize = LocalTextStyle.current.fontSize * settings.fontSizeRatio,
        color = settings.chatTextColor?.let { it.toComposeColor() } ?: Color.Unspecified,
        lineHeight = LocalTextStyle.current.lineHeight * settings.fontSizeRatio,
        fontFamily = when (settings.chatFontFamily) {
            ChatFontFamily.DEFAULT -> FontFamily.Default
            ChatFontFamily.SERIF -> FontFamily.Serif
            ChatFontFamily.MONOSPACE -> FontFamily.Monospace
            ChatFontFamily.CUSTOM -> {
                val fontPath = settings.customFontPath
                if (fontPath.isNotBlank() && java.io.File(fontPath).exists()) {
                    FontFamily(Font(java.io.File(fontPath)))
                } else {
                    FontFamily.Default
                }
            }
        }
    )
    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    // 用户消息"重新生成"确认弹窗 (会删除该消息之后的分支)
    var showUserRegenConfirm by remember { mutableStateOf(false) }
    // 点气泡快捷菜单: showBubbleMenu 控制显隐, bubbleMenuBlockIndex 记录锚定在哪个内容块 (气泡) 上,
    // bubbleMenuSegmentIndex 记录分气泡模式下点的是第几段 (按气泡引用用)
    var showBubbleMenu by remember { mutableStateOf(false) }
    var bubbleMenuBlockIndex by remember { mutableIntStateOf(-1) }
    var bubbleMenuSegmentIndex by remember { mutableIntStateOf(0) }
    var showTranslateDialog by remember { mutableStateOf(false) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val isUser = message.role == MessageRole.USER

    // 列表滚动时自动关闭快捷菜单
    LaunchedEffect(listScrolling) {
        if (listScrolling) {
            showBubbleMenu = false
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        // 定稿 UI: 头像/昵称上移到消息组结构, 这里只渲染消息内容本身;
        // 点气泡弹出快捷菜单, 长按弹出完整操作面板
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ProvideTextStyle(textStyle) {
                MessagePartsBlock(
                    assistant = assistant,
                    role = message.role,
                    parts = message.parts,
                    annotations = message.annotations,
                    loading = loading,
                    model = model,
                    onToolApproval = onToolApproval,
                    onToolAnswer = onToolAnswer,
                    showSideAvatar = !isUser,
                    onBubbleClick = { blockIndex, segmentIndex ->
                        bubbleMenuBlockIndex = blockIndex
                        bubbleMenuSegmentIndex = segmentIndex
                        showBubbleMenu = true
                    },
                    bubbleMenu = { alignEnd, menuAnchor ->
                        // 点气泡快捷菜单: 自定义 Popup 浮层, 锚定在被点气泡上
                        CompactBubbleMenu(
                            alignEnd = alignEnd,
                            anchor = menuAnchor,
                            onDismissRequest = { showBubbleMenu = false },
                        ) {
                            CompactBubbleMenuItem(
                                text = "引用",
                                highlight = true,
                                enabled = onQuote != null,
                                onClick = {
                                    showBubbleMenu = false
                                    // 按气泡引用: 传内容块索引 + 分气泡段索引, 引用被点的那个气泡而非整轮回复
                                    onQuote?.invoke(message, bubbleMenuBlockIndex, bubbleMenuSegmentIndex)
                                },
                            )
                            CompactBubbleMenuItem(
                                text = "复制",
                                icon = HugeIcons.Copy01,
                                onClick = {
                                    showBubbleMenu = false
                                    context.copyMessageToClipboard(message)
                                },
                            )
                            if (isUser) {
                                CompactBubbleMenuItem(
                                    text = "编辑",
                                    icon = HugeIcons.Edit01,
                                    onClick = {
                                        showBubbleMenu = false
                                        onEdit()
                                    },
                                )
                                CompactBubbleMenuItem(
                                    text = "重新生成",
                                    icon = HugeIcons.Refresh03,
                                    onClick = {
                                        showBubbleMenu = false
                                        showUserRegenConfirm = true
                                    },
                                )
                                CompactBubbleMenuItem(
                                    text = "多选",
                                    icon = HugeIcons.Share04,
                                    onClick = {
                                        showBubbleMenu = false
                                        onShare()
                                    },
                                )
                            } else {
                                if (onTranslate != null) {
                                    CompactBubbleMenuItem(
                                        text = "翻译",
                                        icon = HugeIcons.Translate,
                                        onClick = {
                                            showBubbleMenu = false
                                            showTranslateDialog = true
                                        },
                                    )
                                }
                                CompactBubbleMenuItem(
                                    text = "重新生成",
                                    icon = HugeIcons.Refresh03,
                                    onClick = {
                                        showBubbleMenu = false
                                        onRegenerate()
                                    },
                                )
                                CompactBubbleMenuItem(
                                    text = "更多",
                                    icon = HugeIcons.MoreVertical,
                                    onClick = {
                                        showBubbleMenu = false
                                        showActionsSheet = true
                                    },
                                )
                            }
                        }
                    },
                    showBubbleMenu = showBubbleMenu,
                    menuBlockIndex = bubbleMenuBlockIndex,
                    onBubbleLongClick = { showActionsSheet = true },
                    showStreamingCursor = loading && !isUser && lastMessage,
                )

                message.translation?.let { translation ->
                    CollapsibleTranslationText(
                        content = translation,
                        onClickCitation = {}
                    )
                }
            }

            // 多版本消息保留分支切换入口 (仅在有多版本时显示)
            if (node.messages.size > 1) {
                ChatMessageBranchSelector(node = node, onUpdate = onUpdate)
            }

            ProvideTextStyle(textStyle) {
                ChatMessageNerdLine(message = message)
            }
        }
    }

    // 翻译语言选择对话框
    if (showTranslateDialog && onTranslate != null) {
        LanguageSelectionDialog(
            onLanguageSelected = { language ->
                showTranslateDialog = false
                onTranslate(message, language)
            },
            onClearTranslation = {
                showTranslateDialog = false
                onClearTranslation(message)
            },
            onDismissRequest = {
                showTranslateDialog = false
            },
        )
    }

    // 用户消息重新生成确认
    if (showUserRegenConfirm) {
        RikkaConfirmDialog(
            show = true,
            title = "重新生成",
            confirmText = "重新发送",
            dismissText = "取消",
            onConfirm = {
                showUserRegenConfirm = false
                onRegenerate()
            },
            onDismiss = { showUserRegenConfirm = false },
            text = { Text("会撤回这条消息并删除它后面的回复，然后重新发送") },
        )
    }

    if (showActionsSheet) {
        ChatMessageActionsSheet(
            message = message,
            onEdit = onEdit,
            onDelete = onDelete,
            onShare = onShare,
            onFork = onFork,
            model = model,
            onSelectAndCopy = {
                showSelectCopySheet = true
            },
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            onWebViewPreview = {
                val textContent = message.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()
                if (textContent.isNotBlank()) {
                    val htmlContent = buildMarkdownPreviewHtml(
                        context = context,
                        markdown = textContent,
                        colorScheme = colorScheme
                    )
                    navController.navigate(Screen.WebView(content = htmlContent.base64Encode()))
                }
            },
            onDismissRequest = {
                showActionsSheet = false
            }
        )
    }

    if (showSelectCopySheet) {
        ChatMessageCopySheet(
            message = message,
            onDismissRequest = {
                showSelectCopySheet = false
            }
        )
    }
}

// 快捷菜单锚点信息: 锚点(气泡块)的高度与其顶边在窗口中的 y 坐标
// (用于把菜单定位到气泡上方, 上方空间不足时翻到气泡下方)
private data class BubbleMenuAnchor(
    val anchorHeightPx: Int,
    val anchorTopPx: Int,
)

// 点气泡快捷菜单: 自定义 Popup 浮层, 视觉对齐原型 .ctx 的紧凑小菜单
// (圆角 12dp / 内边距 4dp / 最小宽 104dp / 柔和阴影), 深浅色自动跟随 colorScheme.surface;
// 弹出在气泡上方 8dp 处, 水平靠点击侧 (AI 左对齐 / 用户右对齐), 上方放不下翻到气泡下方;
// focusable = true 时点击浮层外自动关闭
@Composable
private fun CompactBubbleMenu(
    alignEnd: Boolean,
    anchor: BubbleMenuAnchor,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { 8.dp.toPx().roundToInt() }
    // 菜单自身高度: 首帧为 0 (先按上方定位), 测量后若上方放不下则翻到气泡下方
    var menuHeightPx by remember { mutableIntStateOf(0) }
    val aboveFits = anchor.anchorTopPx >= menuHeightPx + gapPx
    val offsetY = if (aboveFits) -(anchor.anchorHeightPx + gapPx) else gapPx
    Popup(
        alignment = if (alignEnd) Alignment.BottomEnd else Alignment.BottomStart,
        offset = IntOffset(0, offsetY),
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 3.dp,
            modifier = Modifier
                .widthIn(min = 104.dp)
                .onSizeChanged { menuHeightPx = it.height },
        ) {
            Column(
                modifier = Modifier.padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                content = content,
            )
        }
    }
}

// 快捷菜单项: 高约 30dp 的横向行 (vertical 7dp + horizontal 11dp 内边距),
// 文字 11.5sp, 图标 12dp 半透明; highlight = true 时浅粉底 + primary 字色加粗 (引用项)
@Composable
private fun ColumnScope.CompactBubbleMenuItem(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    highlight: Boolean = false,
) {
    val textColor = if (highlight) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    // 禁用态整体降透明度 (文字与图标), 可用态图标按原型降为半透明
    val itemAlpha = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (highlight) {
                    Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                } else {
                    Modifier
                }
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = textColor.copy(alpha = 0.55f * itemAlpha),
            )
        }
        Text(
            text = text,
            fontSize = 11.5.sp,
            color = textColor.copy(alpha = itemAlpha),
            fontWeight = if (highlight) FontWeight.SemiBold else null,
        )
    }
}
 
/**
 * 用户消息字体颜色: 设置了 userMessageFontColor 时, 覆盖用户气泡内文字颜色
 * (通过 LocalTextStyle 覆盖, 同时兼容显式 chatTextColor 与默认主题色两种情况)
 */
@Composable
private fun UserMessageTextColor(content: @Composable () -> Unit) {
    val displaySettings = LocalDisplaySettings.current
    val fontColor = displaySettings.userMessageFontColor?.let { it.toComposeColor() }
    if (fontColor != null) {
        val merged = LocalTextStyle.current.copy(color = fontColor)
        CompositionLocalProvider(LocalTextStyle provides merged, content = content)
    } else {
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageContentBlock(
    block: MessagePartBlock.ContentBlock,
    role: MessageRole,
    assistant: Assistant?,
    parts: List<UIMessagePart>,
    isLastBlock: Boolean,
    bubbleAlpha: Float,
    maxBubbleWidth: Dp,
    showStreamingCursor: Boolean,
    // 分气泡模式下传被点气泡的段索引, 单气泡传 0; 上层按 (内容块, 段) 定位引用文本
    onBubbleClick: (segmentIndex: Int) -> Unit,
    onBubbleLongClick: () -> Unit,
    onClickCitation: (String) -> Unit,
    showBubbleMenu: Boolean = false,
    bubbleMenu: (@Composable (alignEnd: Boolean, anchor: BubbleMenuAnchor) -> Unit)? = null,
) {
    val context = LocalContext.current
    val displaySettings = LocalDisplaySettings.current
    when (val part = block.part) {
        is UIMessagePart.Text -> {
            // 从显示文本中移除[zip:...]标记
            val displayText = remember(part.text) {
                part.text.replace(Regex("\\[zip:[^\\]]+\\]", RegexOption.IGNORE_CASE), "")
            }

            // 引用部分: 渲染在首个正文文本的气泡内顶部
            val quotePart = remember(parts) {
                parts.filterIsInstance<UIMessagePart.Quote>().firstOrNull()
            }
            val firstTextIndex = remember(parts) {
                parts.indexOfFirst { it is UIMessagePart.Text }
            }
            val showQuote = quotePart != null && block.index == firstTextIndex
            val bubbleModifier = Modifier.widthIn(max = maxBubbleWidth)

            // 气泡块容器: 快捷菜单 Popup 锚定在这个 Box 上 (相对气泡定位),
            // AI 消息菜单靠气泡左缘对齐, 用户消息靠右缘对齐;
            // onGloballyPositioned 记录气泡块高度与窗口内位置, 供菜单定位到上方/翻到下方
            var menuAnchor by remember { mutableStateOf(BubbleMenuAnchor(0, 0)) }
            Box(
                modifier = Modifier.onGloballyPositioned { coords ->
                    // 只在高度/窗口位置真正变化时写入状态: 流式期间布局回调每帧都会触发,
                    // 无条件写入 mutableState 会让菜单锚点状态高频失效并拖慢整个消息项
                    val anchorTopPx = coords.positionInWindow().y.roundToInt()
                    val anchorHeightPx = coords.size.height
                    if (menuAnchor.anchorTopPx != anchorTopPx || menuAnchor.anchorHeightPx != anchorHeightPx) {
                        menuAnchor = BubbleMenuAnchor(
                            anchorHeightPx = anchorHeightPx,
                            anchorTopPx = anchorTopPx,
                        )
                    }
                }
            ) {
                SelectionContainer {
                    Column {
                    if (role == MessageRole.USER) {
                        if (assistant?.splitUserBubbleByLine == true) {
                            // 分气泡: 按用户输入的换行 (\n) 拆成多个独立气泡,
                            // 拆分逻辑见 splitIntoBubbleSegments (会保护代码块/表格内部的换行)
                            val bubbleSegments = remember(displayText) {
                                displayText.splitIntoBubbleSegments()
                            }
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                horizontalAlignment = Alignment.End,
                            ) {
                                bubbleSegments.fastForEachIndexed { segIndex, segment ->
                                    key(segIndex) {
                                        BubbleSurface(
                                            imagePath = displaySettings.userBubbleImagePath,
                                            cornerRadius = displaySettings.bubbleCornerRadius.dp,
                                            color = displaySettings.userBubbleColor?.let { it.toComposeColor() } ?: MaterialTheme.colorScheme.secondaryContainer,
                                            overlayEnabled = displaySettings.bubbleImageOverlayEnabled,
                                            bubbleAlpha = bubbleAlpha,
                                            modifier = bubbleModifier,
                                            onClick = { onBubbleClick(segIndex) },
                                            onLongClick = onBubbleLongClick,
                                        ) {
                                            if (showQuote && segIndex == 0) {
                                                quotePart?.let { MessageQuoteBlock(quote = it) }
                                            }
                                            UserMessageTextColor {
                                                MarkdownBlock(
                                                    content = segment.replaceRegexes(
                                                        assistant = assistant,
                                                        scope = AssistantAffectScope.USER,
                                                        visual = true,
                                                    ),
                                                    onClickCitation = onClickCitation
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            BubbleSurface(
                                imagePath = displaySettings.userBubbleImagePath,
                                cornerRadius = displaySettings.bubbleCornerRadius.dp,
                                color = displaySettings.userBubbleColor?.let { it.toComposeColor() } ?: MaterialTheme.colorScheme.secondaryContainer,
                                overlayEnabled = displaySettings.bubbleImageOverlayEnabled,
                                bubbleAlpha = bubbleAlpha,
                                modifier = bubbleModifier,
                                onClick = { onBubbleClick(0) },
                                onLongClick = onBubbleLongClick,
                            ) {
                                if (showQuote) {
                                    quotePart?.let { MessageQuoteBlock(quote = it) }
                                }
                                UserMessageTextColor {
                                    MarkdownBlock(
                                        content = displayText.replaceRegexes(
                                            assistant = assistant,
                                            scope = AssistantAffectScope.USER,
                                            visual = true,
                                        ),
                                        onClickCitation = onClickCitation
                                    )
                                }
                            }
                        }
                    } else if (assistant?.splitBubbleByLine == true) {
                        // 分气泡: 按模型自己写的换行 (\n) 拆成多个独立气泡,
                        // 拆分逻辑见 splitIntoBubbleSegments (会保护代码块/表格内部的换行)
                        val bubbleSegments = remember(displayText) {
                            displayText.splitIntoBubbleSegments()
                        }
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            bubbleSegments.fastForEachIndexed { segIndex, segment ->
                                key(segIndex) {
                                    if (displaySettings.showAssistantBubble) {
                                        BubbleSurface(
                                            imagePath = displaySettings.assistantBubbleImagePath,
                                            cornerRadius = displaySettings.bubbleCornerRadius.dp,
                                            color = displaySettings.assistantBubbleColor?.let { it.toComposeColor() } ?: MaterialTheme.colorScheme.surfaceContainerHigh,
                                            overlayEnabled = displaySettings.bubbleImageOverlayEnabled,
                                            bubbleAlpha = bubbleAlpha,
                                            modifier = bubbleModifier,
                                            onClick = { onBubbleClick(segIndex) },
                                            onLongClick = onBubbleLongClick,
                                        ) {
                                            if (showQuote && segIndex == 0) {
                                                quotePart?.let { MessageQuoteBlock(quote = it) }
                                            }
                                            // 流式光标: 内联跟在最后一个字符后面 (而非独立块掉到下一行)
                                            MarkdownBlock(
                                                content = segment.replaceRegexes(
                                                    assistant = assistant,
                                                    scope = AssistantAffectScope.ASSISTANT,
                                                    visual = true,
                                                ),
                                                onClickCitation = onClickCitation,
                                            )
                                        }
                                    } else {
                                        Box(
                                            modifier = bubbleModifier
                                                .animateContentSize()
                                                .combinedClickable(
                                                    onClick = { onBubbleClick(segIndex) },
                                                    onLongClick = onBubbleLongClick,
                                                )
                                        ) {
                                            if (showQuote && segIndex == 0) {
                                                quotePart?.let { MessageQuoteBlock(quote = it) }
                                            }
                                            MarkdownBlock(
                                                content = segment.replaceRegexes(
                                                    assistant = assistant,
                                                    scope = AssistantAffectScope.ASSISTANT,
                                                    visual = true,
                                                ),
                                                onClickCitation = onClickCitation,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        if (displaySettings.showAssistantBubble) {
                            BubbleSurface(
                                imagePath = displaySettings.assistantBubbleImagePath,
                                cornerRadius = displaySettings.bubbleCornerRadius.dp,
                                color = displaySettings.assistantBubbleColor?.let { it.toComposeColor() } ?: MaterialTheme.colorScheme.surfaceContainerHigh,
                                overlayEnabled = displaySettings.bubbleImageOverlayEnabled,
                                bubbleAlpha = bubbleAlpha,
                                modifier = bubbleModifier,
                                onClick = { onBubbleClick(0) },
                                onLongClick = onBubbleLongClick,
                            ) {
                                if (showQuote) {
                                    quotePart?.let { MessageQuoteBlock(quote = it) }
                                }
                                // 流式光标: 内联跟在最后一个字符后面 (而非独立块掉到下一行)
                                MarkdownBlock(
                                    content = displayText.replaceRegexes(
                                        assistant = assistant,
                                        scope = AssistantAffectScope.ASSISTANT,
                                        visual = true,
                                    ),
                                    onClickCitation = onClickCitation,
                                )
                            }
                        } else {
                            Box(
                                modifier = bubbleModifier
                                    .animateContentSize()
                                    .combinedClickable(
                                        onClick = { onBubbleClick(0) },
                                        onLongClick = onBubbleLongClick,
                                    )
                            ) {
                                if (showQuote) {
                                    quotePart?.let { MessageQuoteBlock(quote = it) }
                                }
                                MarkdownBlock(
                                    content = displayText.replaceRegexes(
                                        assistant = assistant,
                                        scope = AssistantAffectScope.ASSISTANT,
                                        visual = true,
                                    ),
                                    onClickCitation = onClickCitation,
                                )
                            }
                        }
                    }
                }
                // 快捷菜单浮层: 只在锚定的气泡块上渲染
                if (showBubbleMenu) {
                    bubbleMenu?.invoke(role == MessageRole.USER, menuAnchor)
                }
                }
            }
        }

        is UIMessagePart.Video -> {
            Surface(
                tonalElevation = 2.dp,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.data = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        part.url.toUri().toFile()
                    )
                    val chooserIndent = Intent.createChooser(intent, null)
                    context.startActivity(chooserIndent)
                },
                modifier = Modifier,
                shape = RoundedCornerShape(8.dp),
            ) {
                Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                    Icon(HugeIcons.Video01, null)
                }
            }
        }

        is UIMessagePart.Audio -> {
            AudioPlayerBubble(url = part.url, bubbleAlpha = bubbleAlpha)
        }

        is UIMessagePart.VoiceMessage -> {
            // 转写/语气行在气泡外下方 (与 meta 小字同款样式), 气泡本体只有播放条
            val isUserMsg = role == MessageRole.USER
            Column(
                horizontalAlignment = if (isUserMsg) Alignment.End else Alignment.Start
            ) {
                VoiceMessageBubble(
                    voiceMessage = part,
                    isUser = isUserMsg,
                    bubbleAlpha = bubbleAlpha,
                )
                if (part.transcript.isNotBlank()) {
                    Text(
                        text = part.transcript,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 3.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val emoLine = buildString {
                    if (part.emotion.isNotBlank()) append(part.emotion)
                    if (part.voiceEvents.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(part.voiceEvents.split(",").joinToString(" · "))
                    }
                }
                if (emoLine.isNotBlank()) {
                    Text(
                        text = emoLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        is UIMessagePart.Image -> {
            val isImageLoading =
                part.url.isBlank() || part.url.matches(Regex("^data:image/[^;]*;base64,\\s*$"))
            if (isImageLoading) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .shimmer(isLoading = true)
                )
            } else {
                ZoomableAsyncImage(
                    model = part.url,
                    contentDescription = null,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .height(72.dp)
                )
            }
        }

        is UIMessagePart.Document -> {
            Surface(
                tonalElevation = 2.dp,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.data = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        part.url.toUri().toFile()
                    )
                    val chooserIndent = Intent.createChooser(intent, null)
                    context.startActivity(chooserIndent)
                },
                modifier = Modifier,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when (part.mime) {
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                                Icon(
                                    painter = painterResource(R.drawable.docx),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            "application/pdf" -> {
                                Icon(
                                    painter = painterResource(R.drawable.pdf),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            else -> {
                                Icon(
                                    imageVector = HugeIcons.File02,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Text(
                            text = part.fileName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp)
                        )
                    }
                }
            }
        }

        else -> {
            // Skip unknown part types (e.g., deprecated ToolCall, ToolResult, Search)
        }
    }
}

// 消息内引用块: 正文气泡内顶部展示被引用的消息 (玫瑰粉左边框 + 浅色底)
@Composable
private fun MessageQuoteBlock(
    quote: UIMessagePart.Quote,
    modifier: Modifier = Modifier,
) {
    val rose = Color(0xFFD3A9A3)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(rose.copy(alpha = 0.10f))
            .drawBehind {
                drawLine(
                    color = rose.copy(alpha = 0.45f),
                    start = Offset(x = 1.dp.toPx(), y = 0f),
                    end = Offset(x = 1.dp.toPx(), y = size.height),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = quote.quotedAuthor,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF9A6F68),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = quote.quotedText,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


// 等待动画: 三个 6dp 粉点上下跳动 (周期 1.1s, 相位差 0.15s)。
// ChatList 的独立等待项与消息内等待态共用, 保证两处样式完全一致
@Composable
internal fun BouncingLoadingDots(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BouncingLoadingDot(delayMillis = 0)
        BouncingLoadingDot(delayMillis = 150)
        BouncingLoadingDot(delayMillis = 300)
    }
}

// 单个跳动粉点: 6dp 圆点, 550ms 上跳 + 550ms 回落 (一个周期 1.1s), 用 StartOffset 做相位差
@Composable
private fun BouncingLoadingDot(
    delayMillis: Int,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "bouncingDot")
    val offsetY by transition.animateFloat(
        initialValue = 0f,
        targetValue = -6f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(delayMillis),
        ),
        label = "bouncingDotOffset",
    )
    Box(
        modifier = modifier
            .offset(y = offsetY.dp)
            .size(6.dp)
            .clip(CircleShape)
            .background(Color(0xFFD3A9A3)),
    )
}

@OptIn(FlowPreview::class)
@Composable
private fun MessagePartsBlock(
    assistant: Assistant?,
    role: MessageRole,
    model: Model?,
    parts: List<UIMessagePart>,
    annotations: List<UIMessageAnnotation>,
    loading: Boolean,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    showSideAvatar: Boolean = false,
    onBubbleClick: (blockIndex: Int, segmentIndex: Int) -> Unit = { _, _ -> },
    onBubbleLongClick: () -> Unit = {},
    // 点气泡快捷菜单浮层: showBubbleMenu 为 true 且内容块索引等于 menuBlockIndex 时,
    // 锚定渲染在该气泡块上方 (alignEnd 表示是否靠气泡右缘对齐, anchor 为气泡块的位置尺寸信息)
    bubbleMenu: (@Composable (alignEnd: Boolean, anchor: BubbleMenuAnchor) -> Unit)? = null,
    showBubbleMenu: Boolean = false,
    menuBlockIndex: Int = -1,
    showStreamingCursor: Boolean = false,
) {
    val context = LocalContext.current
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)

    // 消息输出HapticFeedback
    val hapticFeedback = LocalHapticFeedback.current
    val displaySettings = LocalDisplaySettings.current
    val bubbleAlpha = 1f - displaySettings.chatBubbleTransparency / 100f
    val partsState by rememberUpdatedState(parts)

    val handleClickCitation: (String) -> Unit = remember {
        handler@{ citationId ->
            partsState.forEach { part ->
                if (part is UIMessagePart.Tool && part.toolName == "search_web" && part.isExecuted) {
                    val outputText = part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val items =
                        runCatching { JsonInstant.parseToJsonElement(outputText).jsonObject["items"]?.jsonArray }.getOrNull()
                            ?: return@forEach
                    items.forEach { item ->
                        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@forEach
                        val url = item.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                        if (citationId == id) {
                            context.openUrl(url)
                            return@handler
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(displaySettings) {
        snapshotFlow { partsState }
            .debounce(50.milliseconds)
            .collect { parts ->
                if (parts.isNotEmpty() && loading && displaySettings.enableMessageGenerationHapticEffect) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                }
            }
    }

    // 等待跳点: 正在生成的 assistant 消息 (最后一条且生成中) 还没有任何可见输出
    // (无思考/工具/正文文本/媒体附件) 时, 在本消息自己的内容列顶部显示三粉点;
    // 思考/正文一旦到达, 这里立即消失, 思考链行在同一个头像列原地出现。
    // 独立 loading item 此时已隐藏 (见 ChatList), 保证 "等待 -> 思考 -> 正文" 全程只有一个头像
    if (showStreamingCursor && role == MessageRole.ASSISTANT && !parts.hasVisibleOutput()) {
        BouncingLoadingDots(modifier = Modifier.padding(top = 10.dp))
    }

    // Render parts in original order (group thinking/tool as chain-of-thought)
    val groupedParts = remember(parts) { parts.groupMessageParts() }
    // 气泡最大宽度: 0.75 屏宽
    val maxBubbleWidth = (LocalConfiguration.current.screenWidthDp * 0.75).dp
    // 分头像规则: 只有消息里实际用了工具时才分阶段头像 (思考/工具/回复各带一个);
    // 普通回复 (思考+正文) 一体, 全部排在组头像下方
    val hasToolParts = remember(parts) { parts.any { it is UIMessagePart.Tool } }
    val stageAvatar = showSideAvatar && hasToolParts
    groupedParts.fastForEachIndexed { blockIndex, block ->
        when (block) {
            is MessagePartBlock.ThinkingBlock -> {
                if (block.steps.isNotEmpty()) {
                    val isReasoningOnlyBlock = block.steps.fastAll { it is ThinkingStep.ReasoningStep }
                    // 分头像时把思考与工具按连续段拆开, 各自带一个头像 (对应原型分阶段结构)
                    val stageRuns = remember(block.steps) {
                        val runs = mutableListOf<Pair<Boolean, MutableList<ThinkingStep>>>()
                        block.steps.forEach { step ->
                            val isReasoning = step is ThinkingStep.ReasoningStep
                            if (runs.isNotEmpty() && runs.last().first == isReasoning) {
                                runs.last().second.add(step)
                            } else {
                                runs.add(isReasoning to mutableListOf(step))
                            }
                        }
                        runs
                    }
                    val segments: List<List<ThinkingStep>> =
                        if (stageAvatar) stageRuns.map { it.second } else listOf(block.steps)
                    // 分头像结构 (定稿 v4): 每个环节 (思考/工具/第二段思考) 各带一个头像,
                    // 环节之间留出呼吸间距, 像微信语音序列那样一列排下来
                    val stageGap = 12.dp
                    segments.forEachIndexed { segIndex, steps ->
                        val segmentContent: @Composable () -> Unit = {
                            ChainOfThought(
                                modifier = Modifier.animateContentSize(),
                                steps = steps,
                                collapsedAdaptiveWidth = isReasoningOnlyBlock,
                            ) { step ->
                                when (step) {
                                    is ThinkingStep.ReasoningStep -> {
                                        key(step.reasoning.createdAt) {
                                            ChatMessageReasoningStep(
                                                reasoning = step.reasoning,
                                                model = model,
                                                assistant = assistant,
                                                collapsedAdaptiveWidth = isReasoningOnlyBlock,
                                                onLongClick = onBubbleLongClick,
                                            )
                                        }
                                    }

                                    is ThinkingStep.ToolStep -> {
                                        key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                            ChatMessageToolStep(
                                                tool = step.tool,
                                                loading = loading && !step.tool.isExecuted,
                                                allParts = parts,
                                                onToolApproval = onToolApproval,
                                                onToolAnswer = onToolAnswer,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        when {
                            stageAvatar && segIndex == 0 -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ChatMessageSideAvatar(
                                        isUser = false,
                                        model = model,
                                        assistant = assistant,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        segmentContent()
                                    }
                                }
                            }
                            stageAvatar -> {
                                // 后续环节: 同样带头像, 与上一环节拉开呼吸间距
                                Row(
                                    modifier = Modifier.padding(top = stageGap),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    ChatMessageSideAvatar(
                                        isUser = false,
                                        model = model,
                                        assistant = assistant,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        segmentContent()
                                    }
                                }
                            }
                            else -> segmentContent()
                        }
                    }
                }
            }

            is MessagePartBlock.TtsBlock -> {
                // v4: TTS 语音条作为独立消息, 跟随 AI 气泡外壳, 带头像
                if (stageAvatar) {
                    Row(
                        modifier = Modifier.padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ChatMessageSideAvatar(
                            isUser = false,
                            model = model,
                            assistant = assistant,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            TtsMessageBubble(
                                text = block.text,
                                bubbleAlpha = bubbleAlpha,
                                maxBubbleWidth = maxBubbleWidth,
                            )
                        }
                    }
                } else {
                    TtsMessageBubble(
                        text = block.text,
                        bubbleAlpha = bubbleAlpha,
                        maxBubbleWidth = maxBubbleWidth,
                    )
                }
            }

            is MessagePartBlock.ContentBlock -> {
                val content: @Composable () -> Unit = {
                    key(block.index) {
                        MessageContentBlock(
                            block = block,
                            role = role,
                            assistant = assistant,
                            parts = parts,
                            isLastBlock = blockIndex == groupedParts.lastIndex,
                            bubbleAlpha = bubbleAlpha,
                            maxBubbleWidth = maxBubbleWidth,
                            showStreamingCursor = showStreamingCursor,
                            onBubbleClick = { segmentIdx -> onBubbleClick(blockIndex, segmentIdx) },
                            onBubbleLongClick = onBubbleLongClick,
                            onClickCitation = handleClickCitation,
                            showBubbleMenu = showBubbleMenu && blockIndex == menuBlockIndex,
                            bubbleMenu = bubbleMenu,
                        )
                    }
                }
                if (stageAvatar) {
                    // 回复阶段: 同样带头像, 与上方环节拉开呼吸间距
                    Row(
                        modifier = Modifier.padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ChatMessageSideAvatar(
                            isUser = false,
                            model = model,
                            assistant = assistant,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            content()
                        }
                    }
                } else {
                    content()
                }
            }
        }
    }
 
    // Annotations (always rendered at the end)
    if (annotations.isNotEmpty()) {
        Column(
            modifier = Modifier.animateContentSize(),
        ) {
            var expand by remember { mutableStateOf(false) }
            if (expand) {
                ProvideTextStyle(
                    MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.extendColors.gray8.copy(alpha = 0.65f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .drawWithContent {
                                drawContent()
                                drawRoundRect(
                                    color = contentColor.copy(alpha = 0.2f),
                                    size = Size(width = 10f, height = size.height),
                                )
                            }
                            .padding(start = 16.dp)
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        annotations.fastForEachIndexed { index, annotation ->
                            when (annotation) {
                                is UIMessageAnnotation.UrlCitation -> {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Favicon(annotation.url, modifier = Modifier.size(20.dp))
                                        Text(
                                            text = buildAnnotatedString {
                                                append("${index + 1}. ")
                                                withLink(LinkAnnotation.Url(annotation.url)) {
                                                    append(annotation.title.urlDecode())
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            TextButton(
                onClick = {
                    expand = !expand
                }
            ) {
                Text(stringResource(R.string.citations_count, annotations.size))
            }
        }
    }
 
    // 工作区文件 chip: assistant 消息下方展示被 workspace_write_file/
    // workspace_edit_file 写入/编辑的文件, 点击可导出/分享。
    // 仅在归属工作区的 assistant 消息中渲染, 不影响用户消息和其它布局。
    if (role == MessageRole.ASSISTANT) {
        EditedFilesList(parts = parts, assistant = assistant)
    }
}
 
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BubbleSurface(
    imagePath: String,
    cornerRadius: Dp,
    color: Color,
    overlayEnabled: Boolean,
    bubbleAlpha: Float,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // 点按弹快捷菜单, 长按打开完整操作面板
    val clickableModifier = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(
            onClick = onClick ?: {},
            onLongClick = onLongClick,
        )
    } else {
        Modifier
    }
    val hasImage = imagePath.isNotBlank() && java.io.File(imagePath).exists()
    if (hasImage) {
        Box(
            modifier = modifier
                .animateContentSize()
                .clip(RoundedCornerShape(cornerRadius))
                .then(clickableModifier)
        ) {
            AsyncImage(
                model = imagePath,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
            if (overlayEnabled) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(color.copy(alpha = bubbleAlpha))
                )
            }
            Column(modifier = Modifier.padding(8.dp)) { content() }
        }
    } else {
        // 气泡磨砂玻璃 (iMessage 风格): 模糊透出背景颜色, 不发白不发黑
        // 生效条件: 开关开启 + 存在聊天背景图/背景色 + 系统支持
        val settings = LocalSettings.current
        val displaySettings = LocalDisplaySettings.current
        val hazeState = LocalHazeState.current
        val glass = displaySettings.bubbleGlassEnabled && hazeState != null && (
            settings.getCurrentAssistant().background != null ||
                displaySettings.chatBackgroundColor != null
            )
        if (glass) {
            // 磨砂模式: 用普通 Box 而非 Surface(onClick), 去掉 48dp 最小触摸尺寸撑高,
            // 气泡物理上紧贴内容, 大小和关闭磨砂时观感一致
            Box(
                modifier = modifier
                    .animateContentSize()
                    // 先裁圆角再模糊: 磨砂层不会溢出气泡圆角
                    .clip(RoundedCornerShape(cornerRadius))
                    .then(
                        Modifier.hazeEffect(
                            state = hazeState!!,
                            // tint = 气泡色 × 用户透明度: 模糊透出背景图颜色, 不发白/不发黑
                            style = HazeStyle(
                                backgroundColor = Color.Transparent,
                                tints = listOf(HazeTint(color.copy(alpha = bubbleAlpha))),
                            )
                        )
                    )
                    .then(clickableModifier)
            ) {
                Column(modifier = Modifier.padding(8.dp)) { content() }
            }
        } else {
            Box(
                modifier = modifier
                    .animateContentSize()
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(color.copy(alpha = bubbleAlpha))
                    .then(clickableModifier)
            ) {
                Column(modifier = Modifier.padding(8.dp)) { content() }
            }
        }
    }
}
 
@Composable
internal fun AudioPlayerBubble(
    url: String,
    bubbleAlpha: Float = 1f,
) {
    val context = LocalContext.current
    val displaySettings = LocalDisplaySettings.current
    var isPlaying by remember { mutableStateOf(false) }
    var isPrepared by remember { mutableStateOf(false) }
    var durationMs by remember { mutableIntStateOf(0) }
    var currentMs by remember { mutableIntStateOf(0) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    // 异步读音频时长: 宽度按时长自适应, 短语音是短条 (定稿 v4)
    LaunchedEffect(url) {
        if (durationMs > 0) return@LaunchedEffect
        runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(context, android.net.Uri.parse(url))
            val d = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            retriever.release()
            if (d > 0) durationMs = d.toInt()
        }
    }

    fun toggle() {
        errorMsg = null
        if (isPlaying) {
            mediaPlayer?.pause()
            isPlaying = false
            return
        }
        if (mediaPlayer != null && isPrepared) {
            mediaPlayer?.start()
            isPlaying = true
            return
        }
        mediaPlayer?.release()
        mediaPlayer = null
        val mp = MediaPlayer()
        try {
            mp.setDataSource(context, android.net.Uri.parse(url))
            mp.prepare()
            durationMs = mp.duration
            mp.setOnCompletionListener {
                isPlaying = false
                currentMs = 0
            }
            mp.start()
            isPlaying = true
            isPrepared = true
            mediaPlayer = mp
        } catch (e: Exception) {
            mp.release()
            errorMsg = "播放失败，点一下重试"
        }
    }

    // 波形: 按 url 确定性生成, 短语音短条长语音封顶
    val waveformBars = remember(url) {
        val rnd = java.util.Random(url.hashCode().toLong())
        List(48) { 0.15f + rnd.nextFloat() * 0.85f }
    }
    val progress = if (durationMs > 0) currentMs.toFloat() / durationMs else 0f
    val barWidth = (128 + (durationMs / 1000) * 6).dp.coerceIn(150.dp, 260.dp)
    val playedColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            mediaPlayer?.let {
                if (it.isPlaying) currentMs = it.currentPosition
            }
            kotlinx.coroutines.delay(50)
        }
    }

    // 外壳与 AI 气泡完全同款: 毛玻璃/背景图/颜色/透明度全部跟随气泡设置
    BubbleSurface(
        imagePath = displaySettings.assistantBubbleImagePath,
        cornerRadius = displaySettings.bubbleCornerRadius.dp,
        color = displaySettings.assistantBubbleColor?.let { it.toComposeColor() }
            ?: MaterialTheme.colorScheme.surfaceContainerHigh,
        overlayEnabled = displaySettings.bubbleImageOverlayEnabled,
        bubbleAlpha = bubbleAlpha,
        modifier = Modifier.widthIn(max = barWidth),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { toggle() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) HugeIcons.PauseCircle else HugeIcons.PlayCircle,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(22.dp)
                        .pointerInput(durationMs) {
                            detectTapGestures { offset ->
                                if (durationMs > 0) {
                                    val ratio = (offset.x / size.width).coerceIn(0f, 1f)
                                    val target = (ratio * durationMs).toInt()
                                    mediaPlayer?.seekTo(target)
                                    currentMs = target
                                }
                            }
                        }
                ) {
                    val barW = 2.dp.toPx()
                    val gap = 2.5.dp.toPx()
                    val n = ((size.width + gap) / (barW + gap)).toInt().coerceIn(6, 48)
                    val playedCount = (progress * n).toInt()
                    for (i in 0 until n) {
                        val barHeight = size.height * waveformBars[i % waveformBars.size].coerceIn(0.15f, 1f)
                        drawRoundRect(
                            color = if (i < playedCount) playedColor else idleColor,
                            topLeft = Offset(i * (barW + gap), (size.height - barHeight) / 2f),
                            size = Size(barW, barHeight),
                            cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                val displaySec = if (isPlaying || currentMs > 0) {
                    ((durationMs - currentMs) / 1000).coerceAtLeast(0)
                } else {
                    durationMs / 1000
                }
                Text(
                    text = String.format(
                        java.util.Locale.US, "%d:%02d", displaySec / 60, displaySec % 60
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    modifier = Modifier.width(34.dp),
                    textAlign = TextAlign.End
                )
            }
            if (errorMsg != null) {
                Text(
                    text = errorMsg ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

/**
 * 细线播放/暂停图标: 圆角描边三角 / 双竖条, 线条纤细比实心圆图标更精致
 */
@Composable
private fun VoicePlayIcon(isPlaying: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = 1.8.dp.toPx()
        if (!isPlaying) {
            val p1 = androidx.compose.ui.geometry.Offset(size.width * 0.34f, size.height * 0.22f)
            val p2 = androidx.compose.ui.geometry.Offset(size.width * 0.34f, size.height * 0.78f)
            val p3 = androidx.compose.ui.geometry.Offset(size.width * 0.84f, size.height * 0.5f)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); close()
            }
            drawPath(
                path, color,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        } else {
            drawLine(
                color,
                start = androidx.compose.ui.geometry.Offset(size.width * 0.36f, size.height * 0.24f),
                end = androidx.compose.ui.geometry.Offset(size.width * 0.36f, size.height * 0.76f),
                strokeWidth = stroke, cap = StrokeCap.Round
            )
            drawLine(
                color,
                start = androidx.compose.ui.geometry.Offset(size.width * 0.66f, size.height * 0.24f),
                end = androidx.compose.ui.geometry.Offset(size.width * 0.66f, size.height * 0.76f),
                strokeWidth = stroke, cap = StrokeCap.Round
            )
        }
    }
}

/**
 * TTS 语音条 (text_to_speech 工具): v4 定稿独立消息样式,
 * 外壳与 AI 气泡完全同款 (宽度/圆角/毛玻璃/背景图/颜色/透明度全部跟随气泡设置),
 * 唯一独立的是播放键圆圈颜色 (voicePlayButtonColor, 默认思考链爱心玫瑰粉),
 * 播放走系统 TTS 引擎, 波形按文本确定性生成
 */
@Composable
private fun TtsMessageBubble(
    text: String,
    bubbleAlpha: Float = 1f,
    maxBubbleWidth: Dp,
) {
    val displaySettings = LocalDisplaySettings.current
    val ttsState = LocalTTSState.current
    val playbackState by ttsState.playbackState.collectAsState()
    val isSpeaking by ttsState.isSpeaking.collectAsState()
    val playing = isSpeaking && playbackState.status == PlaybackStatus.Playing

    // 时长估算: TTS 中文约 4 字/秒, 限 2~180 秒
    val estimatedSec = (text.length / 4).coerceIn(2, 180)
    val progress = if (playing && playbackState.durationMs > 0) {
        (playbackState.positionMs.toFloat() / playbackState.durationMs).coerceIn(0f, 1f)
    } else 0f
    val displaySec = if (playing && playbackState.durationMs > 0) {
        ((playbackState.durationMs - playbackState.positionMs) / 1000).toInt().coerceAtLeast(0)
    } else {
        estimatedSec
    }

    val waveformBars = remember(text) {
        val rnd = java.util.Random(text.hashCode().toLong())
        List(48) { 0.15f + rnd.nextFloat() * 0.85f }
    }
    // 播放键圆圈 + 已播放波形: 自定义色优先, 默认与思考链爱心同色系玫瑰粉
    val playColor = displaySettings.voicePlayButtonColor?.let { it.toComposeColor() }
        ?: Color(0xFFD3A9A3)
    val idleColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)

    // 外壳与 AI 气泡完全同款: 毛玻璃/背景图/颜色/透明度全部跟随气泡设置
    BubbleSurface(
        imagePath = displaySettings.assistantBubbleImagePath,
        cornerRadius = displaySettings.bubbleCornerRadius.dp,
        color = displaySettings.assistantBubbleColor?.let { it.toComposeColor() }
            ?: MaterialTheme.colorScheme.surfaceContainerHigh,
        overlayEnabled = displaySettings.bubbleImageOverlayEnabled,
        bubbleAlpha = bubbleAlpha,
        modifier = Modifier.widthIn(max = maxBubbleWidth),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(playColor)
                    .clickable {
                        when {
                            playbackState.status == PlaybackStatus.Playing -> ttsState.pause()
                            isSpeaking -> ttsState.resume()
                            else -> ttsState.speak(text)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (playing) HugeIcons.PauseCircle else HugeIcons.PlayCircle,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(22.dp)
            ) {
                val barW = 2.dp.toPx()
                val gap = 2.5.dp.toPx()
                val n = ((size.width + gap) / (barW + gap)).toInt().coerceIn(6, 48)
                val playedCount = (progress * n).toInt()
                for (i in 0 until n) {
                    val barHeight = size.height * waveformBars[i % waveformBars.size].coerceIn(0.15f, 1f)
                    drawRoundRect(
                        color = if (i < playedCount) playColor else idleColor,
                        topLeft = Offset(i * (barW + gap), (size.height - barHeight) / 2f),
                        size = Size(barW, barHeight),
                        cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = String.format(
                    java.util.Locale.US, "%d:%02d", displaySec / 60, displaySec % 60
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                fontSize = 12.sp,
                modifier = Modifier.width(34.dp),
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
internal fun VoiceMessageBubble(
    voiceMessage: UIMessagePart.VoiceMessage,
    isUser: Boolean,
    bubbleAlpha: Float = 1f,
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    // 气泡外壳与普通消息气泡完全同款: 用户/助手气泡色 + 毛玻璃 + 背景图 + 圆角 + 透明度全跟随设置
    val displaySettings = LocalDisplaySettings.current
 
    val durationSec = (voiceMessage.duration / 1000).coerceAtLeast(1)
 
    DisposableEffect(voiceMessage.url) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }
 
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            mediaPlayer?.let {
                if (!it.isPlaying) {
                    isPlaying = false
                }
            }
            kotlinx.coroutines.delay(50)
        }
    }
 
    BubbleSurface(
        imagePath = displaySettings.userBubbleImagePath,
        cornerRadius = displaySettings.bubbleCornerRadius.dp,
        color = if (isUser) displaySettings.userBubbleColor?.let { it.toComposeColor() }
        ?: MaterialTheme.colorScheme.secondaryContainer
        else displaySettings.assistantBubbleColor?.let { it.toComposeColor() }
        ?: MaterialTheme.colorScheme.surfaceContainerHigh,
        overlayEnabled = displaySettings.bubbleImageOverlayEnabled,
        bubbleAlpha = bubbleAlpha,
        onClick = {
            if (isPlaying) {
                mediaPlayer?.let {
                    it.stop()
                    it.reset()
                }
                isPlaying = false
            } else {
                try {
                    val mp = MediaPlayer()
                    mp.setDataSource(voiceMessage.url)
                    mp.prepare()
                    mp.setOnCompletionListener {
                        isPlaying = false
                    }
                    mp.start()
                    isPlaying = true
                    mediaPlayer?.release()
                    mediaPlayer = mp
                } catch (e: Exception) {
                    // File might not exist
                }
            }
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
                // 细线播放/暂停图标 (比 HugeIcons 实心圆更精致)
                VoicePlayIcon(
                    isPlaying = isPlaying,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                // Waveform bars
                val waveformBars = remember(voiceMessage.url) {
                    val rnd = java.util.Random(voiceMessage.url.hashCode().toLong())
                    List(24) { 0.2f + rnd.nextFloat() * 0.8f }
                }
                val waveformColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f)
                Canvas(modifier = Modifier.width(60.dp).height(24.dp)) {
                    val barCount = waveformBars.size
                    val barWidth = 2.5f
                    val gap = (size.width - barWidth * barCount) / (barCount - 1).coerceAtLeast(1)
                    waveformBars.forEachIndexed { index, barRatio ->
                        val barHeight = size.height * barRatio.coerceIn(0.2f, 1f)
                        val x = index * (barWidth + gap)
                        val y = (size.height - barHeight) / 2f
                        drawRoundRect(
                            color = waveformColor,
                            topLeft = androidx.compose.ui.geometry.Offset(x, y),
                            size = Size(barWidth, barHeight),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f, 1.5f)
                        )
                    }
                }
                Text(
                    text = "${durationSec}″",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                )
        }
    }
}
 