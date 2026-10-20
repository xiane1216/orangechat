/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.message
 
import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.window.Popup
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.PlayCircle
import me.rerere.hugeicons.stroke.PauseCircle
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.Video01
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.layout.ContentScale
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
import kotlin.time.Duration.Companion.milliseconds
 
@Composable
fun ChatMessage(
    node: MessageNode,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    model: Model? = null,
    assistant: Assistant? = null,
    lastMessage: Boolean = false,
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
    var showActionMenu by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val tts = LocalTTSState.current
    val isSpeaking by tts.isSpeaking.collectAsState()
    val displaySettings = LocalDisplaySettings.current
    // 长按消息弹出操作面板 (微信式交互, 定稿 UI: 移除消息下方常驻图标行)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(message.id) {
                detectTapGestures(onLongPress = {
                    showActionMenu = true
                })
            },
        horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (!message.parts.isEmptyUIMessage()) {
            // 定稿 UI: 消息行 = 头像在消息侧面 (AI 左/用户右) + 消息列(名字小标签+正文+时间)
            val isUser = message.role == MessageRole.USER
            val assistantAvatarVisible = !isUser && assistant?.useAssistantAvatar == true && displaySettings.showModelIcon
            val modelIconVisible = !isUser && assistant?.useAssistantAvatar != true && model != null && displaySettings.showModelIcon
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val messageContent: @Composable () -> Unit = {
                    // 名字小标签 (消息列顶部)
                    if (isUser) {
                        Text(
                            text = displaySettings.userNickname.ifEmpty { stringResource(R.string.user_default_name) },
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(alpha = 0.85f),
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    } else if (displaySettings.showModelName) {
                        Text(
                            text = assistant?.name?.ifEmpty { stringResource(R.string.assistant_page_default_assistant) }
                                ?: model?.displayName
                                ?: stringResource(R.string.assistant_page_default_assistant),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(alpha = 0.85f),
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
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
                            onUserMessageClick = if (isUser) onEdit else null,
                        )

                        message.translation?.let { translation ->
                            CollapsibleTranslationText(
                                content = translation,
                                onClickCitation = {}
                            )
                        }
                    }

                    ProvideTextStyle(textStyle) {
                        ChatMessageNerdLine(message = message)
                    }
                }
                if (isUser) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.End,
                    ) {
                        messageContent()
                    }
                    // 用户头像 (右侧, 沿用 showUserAvatar 与头像框设置)
                    if (displaySettings.showUserAvatar) {
                        Box(contentAlignment = Alignment.Center) {
                            UIAvatar(
                                name = displaySettings.userNickname,
                                modifier = Modifier.size(32.dp),
                                value = settings.userAvatar,
                                loading = false,
                            )
                            AvatarFrameOverlay(
                                framePath = displaySettings.userAvatarFramePath,
                                offsetX = displaySettings.userAvatarFrameOffsetX,
                                offsetY = displaySettings.userAvatarFrameOffsetY,
                                scale = displaySettings.userAvatarFrameScale,
                                baseSize = 32f,
                            )
                        }
                    }
                } else {
                    // AI 头像 (左侧): 优先助手头像, 其次模型图标, 沿用 showModelIcon 与头像框设置
                    if (assistantAvatarVisible) {
                        Box(contentAlignment = Alignment.Center) {
                            UIAvatar(
                                name = assistant?.name ?: "",
                                modifier = Modifier.size(32.dp),
                                value = assistant?.avatar ?: Avatar.Dummy,
                                loading = loading,
                            )
                            AvatarFrameOverlay(
                                framePath = displaySettings.aiAvatarFramePath,
                                offsetX = displaySettings.aiAvatarFrameOffsetX,
                                offsetY = displaySettings.aiAvatarFrameOffsetY,
                                scale = displaySettings.aiAvatarFrameScale,
                                baseSize = 32f,
                            )
                        }
                    } else if (modelIconVisible) {
                        Box(contentAlignment = Alignment.Center) {
                            AutoAIIcon(
                                name = model?.modelId ?: "",
                                modifier = Modifier.size(32.dp),
                                loading = loading
                            )
                            AvatarFrameOverlay(
                                framePath = displaySettings.aiAvatarFramePath,
                                offsetX = displaySettings.aiAvatarFrameOffsetX,
                                offsetY = displaySettings.aiAvatarFrameOffsetY,
                                scale = displaySettings.aiAvatarFrameScale,
                                baseSize = 32f,
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        messageContent()
                    }
                }
            }
        }
    }
    if (showActionMenu) {
        Popup(
            alignment = Alignment.Center,
            onDismissRequest = { showActionMenu = false }
        ) {
            // 定稿 UI: 微信式深色浮层操作菜单 (横排 图标+小字)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1F1F23).copy(alpha = 0.96f),
                shadowElevation = 8.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    MessageMenuButton(HugeIcons.Copy01, stringResource(R.string.copy)) {
                        showActionMenu = false
                        context.copyMessageToClipboard(message)
                    }
                    MessageMenuButton(HugeIcons.Refresh03, stringResource(R.string.regenerate)) {
                        showActionMenu = false
                        onRegenerate()
                    }
                    if (message.role == MessageRole.ASSISTANT) {
                        MessageMenuButton(
                            if (isSpeaking) HugeIcons.StopCircle else HugeIcons.VolumeHigh,
                            stringResource(R.string.tts)
                        ) {
                            if (!isSpeaking) {
                                val text = message.parts
                                    .filterIsInstance<UIMessagePart.Text>()
                                    .joinToString("\n\n") { it.text }
                                    .trim()
                                val textToSpeak = if (displaySettings.ttsOnlyReadQuoted) {
                                    text.extractQuotedContentAsText() ?: text
                                } else text
                                tts.speak(textToSpeak)
                            } else {
                                tts.stop()
                            }
                        }
                    }
                    MessageMenuButton(HugeIcons.Delete01, stringResource(R.string.delete)) {
                        showActionMenu = false
                        onDelete()
                    }
                    MessageMenuButton(HugeIcons.MoreVertical, stringResource(R.string.more_options)) {
                        showActionMenu = false
                        showActionsSheet = true
                    }
                }
            }
        }
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
            onCopy = {
                context.copyMessageToClipboard(message)
            },
            onRegenerate = onRegenerate,
            onSpeak = if (message.role == MessageRole.ASSISTANT) { {
                if (!isSpeaking) {
                    val text = message.parts
                        .filterIsInstance<UIMessagePart.Text>()
                        .joinToString("\n\n") { it.text }
                        .trim()
                    val textToSpeak = if (displaySettings.ttsOnlyReadQuoted) {
                        text.extractQuotedContentAsText() ?: text
                    } else {
                        text
                    }
                    tts.speak(textToSpeak)
                } else {
                    tts.stop()
                }
            } } else null,
            isSpeaking = isSpeaking,
            onTranslate = onTranslate,
            onClearTranslation = onClearTranslation,
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

/**
 * 长按浮层菜单按钮 (微信式: 深色底 横排 图标+小字)
 */
@Composable
private fun MessageMenuButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(17.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            fontSize = 9.5.sp,
            maxLines = 1,
            color = Color.White.copy(alpha = 0.92f)
        )
    }
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
    onUserMessageClick: (() -> Unit)? = null,
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
 
    // Render parts in original order (group thinking/tool as chain-of-thought)
    val groupedParts = remember(parts) { parts.groupMessageParts() }
    groupedParts.fastForEach { block ->
        when (block) {
            is MessagePartBlock.ThinkingBlock -> {
                if (block.steps.isNotEmpty()) {
                    val isReasoningOnlyBlock = block.steps.fastAll { it is ThinkingStep.ReasoningStep }
                    ChainOfThought(
                        modifier = Modifier.animateContentSize(),
                        steps = block.steps,
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
            }
 
            is MessagePartBlock.ContentBlock -> key(block.index) {
                when (val part = block.part) {
                    is UIMessagePart.Text -> {
                        // 从显示文本中移除[zip:...]标记
                        val displayText = remember(part.text) {
                            part.text.replace(Regex("\\[zip:[^\\]]+\\]", RegexOption.IGNORE_CASE), "")
                        }
                        
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
                                                        onClick = { onUserMessageClick?.invoke() },
                                                    ) {
                                                        UserMessageTextColor {
                                                            MarkdownBlock(
                                                                content = segment.replaceRegexes(
                                                                    assistant = assistant,
                                                                    scope = AssistantAffectScope.USER,
                                                                    visual = true,
                                                                ),
                                                                onClickCitation = handleClickCitation
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
                                            onClick = { onUserMessageClick?.invoke() },
                                        ) {
                                            UserMessageTextColor {
                                                MarkdownBlock(
                                                    content = displayText.replaceRegexes(
                                                        assistant = assistant,
                                                        scope = AssistantAffectScope.USER,
                                                        visual = true,
                                                    ),
                                                    onClickCitation = handleClickCitation
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
                                                    ) {
                                                        MarkdownBlock(
                                                            content = segment.replaceRegexes(
                                                                assistant = assistant,
                                                                scope = AssistantAffectScope.ASSISTANT,
                                                                visual = true,
                                                            ),
                                                            onClickCitation = handleClickCitation,
                                                        )
                                                    }
                                                } else {
                                                    MarkdownBlock(
                                                        content = segment.replaceRegexes(
                                                            assistant = assistant,
                                                            scope = AssistantAffectScope.ASSISTANT,
                                                            visual = true,
                                                        ),
                                                        onClickCitation = handleClickCitation,
                                                        modifier = Modifier
                                                            .animateContentSize()
                                                    )
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
                                        ) {
                                            MarkdownBlock(
                                                content = displayText.replaceRegexes(
                                                    assistant = assistant,
                                                    scope = AssistantAffectScope.ASSISTANT,
                                                    visual = true,
                                                ),
                                                onClickCitation = handleClickCitation,
                                            )
                                        }
                                    } else {
                                        MarkdownBlock(
                                            content = displayText.replaceRegexes(
                                                assistant = assistant,
                                                scope = AssistantAffectScope.ASSISTANT,
                                                visual = true,
                                            ),
                                            onClickCitation = handleClickCitation,
                                            modifier = Modifier
                                                .animateContentSize()
                                        )
                                    }
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
                        AudioPlayerBubble(url = part.url)
                    }
 
                    is UIMessagePart.VoiceMessage -> {
                        VoiceMessageBubble(
                            voiceMessage = part,
                            isUser = role == MessageRole.USER,
                        )
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
 
@Composable
private fun BubbleSurface(
    imagePath: String,
    cornerRadius: Dp,
    color: Color,
    overlayEnabled: Boolean,
    bubbleAlpha: Float,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val hasImage = imagePath.isNotBlank() && java.io.File(imagePath).exists()
    if (hasImage) {
        Box(
            modifier = Modifier
                .animateContentSize()
                .clip(RoundedCornerShape(cornerRadius))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
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
        Surface(
            modifier = Modifier.animateContentSize(),
            shape = RoundedCornerShape(cornerRadius),
            color = color.copy(alpha = bubbleAlpha),
            onClick = onClick ?: {},
        ) {
            Column(modifier = Modifier.padding(8.dp)) { content() }
        }
    }
}
 
@Composable
@Suppress("UnusedCrossTarget")
internal fun AudioPlayerBubble(url: String) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableIntStateOf(0) }
    var currentMs by remember { mutableIntStateOf(0) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPrepared by remember { mutableStateOf(false) }
 
    // Generate pseudo-random waveform bar heights (deterministic per url)
    val waveformBars = remember(url) {
        val rnd = java.util.Random(url.hashCode().toLong())
        List(40) { 0.15f + rnd.nextFloat() * 0.85f }
    }
 
    val progress = if (durationMs > 0) currentMs.toFloat() / durationMs else 0f
 
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }
 
    // Progress ticker
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    currentMs = it.currentPosition
                }
            }
            kotlinx.coroutines.delay(50)
        }
    }
 
    // Animate waveform bars when playing
    val animatedBars = remember { mutableStateOf(waveformBars) }
    LaunchedEffect(isPlaying, progress) {
        if (isPlaying) {
            val rnd = java.util.Random()
            val newBars = waveformBars.mapIndexed { index, base ->
                val playedRatio = if (progress > 0f) index.toFloat() / waveformBars.size else 0f
                if (playedRatio <= progress) {
                    // Already played bars stay at original height
                    base
                } else {
                    // Upcoming bars get slight animation
                    base * (0.85f + rnd.nextFloat() * 0.3f)
                }
            }
            animatedBars.value = newBars
        } else {
            animatedBars.value = waveformBars
        }
    }
 
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
 
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Play / Pause button
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable {
                    if (isPlaying) {
                        mediaPlayer?.pause()
                        isPlaying = false
                    } else {
                        if (mediaPlayer == null || !isPrepared) {
                            val mp = MediaPlayer()
                            try {
                                val uri = android.net.Uri.parse(url)
                                mp.setDataSource(context, uri)
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
                            }
                        } else {
                            mediaPlayer?.start()
                            isPlaying = true
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) HugeIcons.PauseCircle else HugeIcons.PlayCircle,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
 
        Spacer(modifier = Modifier.width(8.dp))
 
        // Waveform bars
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .clickable { /* click waveform to seek (optional future) */ }
        ) {
            val barCount = animatedBars.value.size
            val totalWidth = size.width
            val barWidth = 2.5f
            val gap = (totalWidth - barWidth * barCount) / (barCount - 1).coerceAtLeast(1)
            val playedBarCount = (progress * barCount).toInt()
 
            animatedBars.value.forEachIndexed { index, barRatio ->
                val barHeight = size.height * barRatio.coerceIn(0.15f, 1f)
                val x = index * (barWidth + gap)
                val y = (size.height - barHeight) / 2f
                drawRoundRect(
                    color = if (index < playedBarCount) activeColor else inactiveColor,
                    topLeft = androidx.compose.ui.geometry.Offset(x, y),
                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f, 1.5f)
                )
            }
        }
 
        Spacer(modifier = Modifier.width(6.dp))
 
        // Duration text
        val displaySec = if (isPlaying || currentMs > 0) {
            val remaining = (durationMs - currentMs) / 1000
            remaining.coerceAtLeast(0)
        } else {
            durationMs / 1000
        }
        Text(
            text = String.format("%d:%02d", displaySec / 60, displaySec % 60),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontSize = 13.sp,
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.End
        )
    }
}
 
@Composable
internal fun VoiceMessageBubble(
    voiceMessage: UIMessagePart.VoiceMessage,
    isUser: Boolean,
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
 
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
 
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isUser) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.tertiaryContainer,
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
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (isPlaying) HugeIcons.PauseCircle else HugeIcons.PlayCircle,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
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
            // Show transcript text below the voice bubble (like WeChat)
            if (voiceMessage.transcript.isNotBlank()) {
                Text(
                    text = voiceMessage.transcript,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
 