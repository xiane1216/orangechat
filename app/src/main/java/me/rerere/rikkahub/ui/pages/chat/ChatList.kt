/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.chat

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ArrowDownDouble
import me.rerere.hugeicons.stroke.ArrowUpDouble
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.utils.extractQuotedContentAsText
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalScrollCaptureInProgress
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.ui.components.message.BouncingLoadingDots
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.components.message.ChatMessageSideAvatar
import me.rerere.rikkahub.ui.components.ui.ErrorCardsDisplay
import me.rerere.rikkahub.ui.components.ui.ListSelectableItem
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.hooks.ImeLazyListAutoScroller
import me.rerere.rikkahub.utils.plus
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

private const val TAG = "ChatList"
private const val LoadingIndicatorKey = "LoadingIndicator"
private const val ScrollBottomKey = "ScrollBottomKey"

@Composable
fun ChatList(
    innerPadding: PaddingValues,
    conversation: Conversation,
    state: LazyListState,
    loading: Boolean,
    processingStatus: String? = null,
    sendVisual: SendVisual? = null,
    previewMode: Boolean,
    settings: Settings,
    hazeState: HazeState,
    errors: List<ChatError> = emptyList(),
    onDismissError: (Uuid) -> Unit = {},
    onClearAllErrors: () -> Unit = {},
    onRegenerate: (UIMessage) -> Unit = {},
    onEdit: (UIMessage) -> Unit = {},
    onForkMessage: (UIMessage) -> Unit = {},
    onDelete: (UIMessage) -> Unit = {},
    onUpdateMessage: (MessageNode) -> Unit = {},
    onClickSuggestion: (String) -> Unit = {},
    onTranslate: ((UIMessage, java.util.Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onJumpToMessage: (Int) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToggleFavorite: ((MessageNode) -> Unit)? = null,
    onConversationSystemPromptChange: ((String?) -> Unit)? = null,
    onQuote: ((UIMessage) -> Unit)? = null,
) {
    AnimatedContent(
        targetState = previewMode,
        label = "ChatListMode",
        transitionSpec = {
            (fadeIn() + scaleIn(initialScale = 0.8f) togetherWith fadeOut() + scaleOut(targetScale = 0.8f))
        }
    ) { target ->
        if (target) {
            ChatListPreview(
                innerPadding = innerPadding,
                conversation = conversation,
                settings = settings,
                hazeState = hazeState,
                onJumpToMessage = onJumpToMessage,
                animatedVisibilityScope = this@AnimatedContent,
            )
        } else {
            ChatListNormal(
                innerPadding = innerPadding,
                conversation = conversation,
                state = state,
                loading = loading,
                processingStatus = processingStatus,
                sendVisual = sendVisual,
                settings = settings,
                hazeState = hazeState,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = onRegenerate,
                onEdit = onEdit,
                onForkMessage = onForkMessage,
                onDelete = onDelete,
                onUpdateMessage = onUpdateMessage,
                onClickSuggestion = onClickSuggestion,
                onTranslate = onTranslate,
                onClearTranslation = onClearTranslation,
                animatedVisibilityScope = this@AnimatedContent,
                onToolApproval = onToolApproval,
                onToolAnswer = onToolAnswer,
                onToggleFavorite = onToggleFavorite,
                onConversationSystemPromptChange = onConversationSystemPromptChange,
                onQuote = onQuote,
            )
        }
    }
}

@Composable
private fun ChatListNormal(
    innerPadding: PaddingValues,
    conversation: Conversation,
    state: LazyListState,
    loading: Boolean,
    processingStatus: String? = null,
    sendVisual: SendVisual?,
    settings: Settings,
    hazeState: HazeState,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    onRegenerate: (UIMessage) -> Unit,
    onEdit: (UIMessage) -> Unit,
    onForkMessage: (UIMessage) -> Unit,
    onDelete: (UIMessage) -> Unit,
    onUpdateMessage: (MessageNode) -> Unit,
    onClickSuggestion: (String) -> Unit,
    onTranslate: ((UIMessage, java.util.Locale) -> Unit)?,
    onClearTranslation: (UIMessage) -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToggleFavorite: ((MessageNode) -> Unit)? = null,
    onConversationSystemPromptChange: ((String?) -> Unit)? = null,
    onQuote: ((UIMessage) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val loadingState by rememberUpdatedState(loading)
    var isRecentScroll by remember { mutableStateOf(false) }
    val conversationUpdated by rememberUpdatedState(conversation)
    val density = LocalDensity.current
    val activity = LocalContext.current as? me.rerere.rikkahub.RouteActivity

    DisposableEffect(Unit) {
        val listener: (Boolean) -> Boolean = { isVolumeUp ->
            if (settings.displaySetting.enableVolumeKeyScroll) {
                val bottomPaddingPx = with(density) {
                    (32.dp + innerPadding.calculateBottomPadding()).toPx()
                }
                val scrollAmount = (state.layoutInfo.viewportSize.height - bottomPaddingPx) *
                    settings.displaySetting.volumeKeyScrollRatio
                scope.launch { state.scrollBy(if (isVolumeUp) -scrollAmount else scrollAmount) }
                true
            } else false
        }
        activity?.volumeKeyListeners?.add(listener)
        onDispose {
            activity?.volumeKeyListeners?.remove(listener)
        }
    }

    fun List<LazyListItemInfo>.isAtBottom(): Boolean {
        val lastItem = lastOrNull() ?: return false
        val inputBarHeight = with(density) { innerPadding.calculateBottomPadding().toPx() }
        val lastPos = lastItem.offset + lastItem.size
        val inputPos = (state.layoutInfo.viewportEndOffset - inputBarHeight.roundToInt())
        // println("lastPos = $lastPos, inputPos = $inputPos  | ${lastPos <= inputPos - 8}")
        return lastPos <= inputPos - 8
    }

    // 聊天选择
    val selectedItems = remember { mutableStateListOf<Uuid>() }
    var selecting by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }

    // 自动跟随键盘滚动
    ImeLazyListAutoScroller(lazyListState = state)

    // 对话大小警告对话框
    val sizeInfo = rememberConversationSizeInfo(conversation)
    var showSizeWarningDialog by rememberSaveable(conversation.id) { mutableStateOf(true) }
    if (sizeInfo.showWarning && showSizeWarningDialog) {
        ConversationSizeWarningDialog(
            sizeInfo = sizeInfo,
            onDismiss = { showSizeWarningDialog = false }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        // 自动滚动到底部
        if (settings.displaySetting.enableAutoScroll) {
            LaunchedEffect(state) {
                snapshotFlow { state.layoutInfo.visibleItemsInfo }.collect { visibleItemsInfo ->
                    // println("is bottom = ${visibleItemsInfo.isAtBottom()}, scroll = ${state.isScrollInProgress}, can_scroll = ${state.canScrollForward}, loading = $loading")
                    if (!state.isScrollInProgress && loadingState) {
                        if (visibleItemsInfo.isAtBottom()) {
                            state.requestScrollToItem(conversationUpdated.messageNodes.lastIndex + 10)
                            // Log.i(TAG, "ChatList: scroll to ${conversationUpdated.messageNodes.lastIndex}")
                        }
                    }
                }
            }
        }

        // 判断最近是否滚动
        LaunchedEffect(state.isScrollInProgress) {
            if (state.isScrollInProgress) {
                isRecentScroll = true
                delay(1500)
                isRecentScroll = false
            } else {
                delay(1500)
                isRecentScroll = false
            }
        }

        // Filter out [SKIP] messages and proactive/heartbeat synthetic context markers.
        // 心跳触发的合成 USER 消息标记是 "[心跳检查上下文]" (主动消息是 "[主动消息上下文]"),
        // 漏过滤会让心跳回复前的合成触发消息以用户气泡混进列表, 导致心跳 assistant 组头像错位缺失。
        // 过滤结果按 "节点 id + 节点实例" 记忆: 流式时绝大多数节点引用不变直接命中缓存,
        // 只有真正更新的那条消息 (新实例) 才重新做 toText 检查, 避免每个 token 全量扫描全部消息文本
        val displayNodeFilter = remember { DisplayNodeFilterCache() }
        val displayNodes = displayNodeFilter.filter(conversation.messageNodes)

        // 最新节点实例索引: 流式期间会话对象每 token 都是新实例, 渲染时用它把组内的
        // 节点 id 映射回最新 MessageNode, 保证流式内容更新直达对应消息 item
        val latestNodeById = conversation.messageNodes.associateBy { it.id }

        // 消息分组: 相邻同角色消息合成一组再渲染 (组头一个头像, 组内气泡 2dp 间距)。
        // 分组结构只携带 "角色 + 节点 id", 不携带节点实例; 流式 token 更新不会改变
        // id/角色序列, 组的 key/参数保持稳定, 避免每 token 全量重新分组 + 全列表深度比较
        val displayGroups = buildGroups(displayNodes)

        // 独立等待跳点 item 只在 "正在生成的 assistant 消息尚未创建" (最后一条不是 assistant,
        // 比如用户刚发出) 时显示; 一旦 assistant 消息节点已创建 (哪怕还没有内容), 独立 item 隐藏,
        // 三粉点改为渲染在该消息自己的内容列顶部 (见 ChatMessage 内部), 思考到达后原地变成思考行,
        // 全程只保留组头像一个头像, 消除 "loading 头像 -> 消息头像" 的切换感
        val showLoadingItem = loading && run {
            val lastMsg = conversation.messageNodes.lastOrNull()?.currentMessage
            lastMsg == null || lastMsg.role != MessageRole.ASSISTANT
        }

        LazyColumn(
            state = state,
            // 水平 6dp: AI 组 (头像+气泡+思考链+小字) 整体贴左, 用户组整体贴右, 像微信那样
            // 靠边但留一点缝隙; 组是 fillMaxWidth 的整体, 收窄边距即整组平移
            contentPadding = PaddingValues(start = 6.dp, end = 6.dp) +
                PaddingValues(bottom = 32.dp + innerPadding.calculateBottomPadding()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            itemsIndexed(
                items = displayGroups,
                key = { _, group -> "grp_${group.ids.first()}" },
                // contentType 按角色区分, 复用组合时用户/助手组各自匹配, 减少无谓的结构重建
                contentType = { _, group -> group.role },
            ) { groupIndex, group ->
                val isUserGroup = group.role == MessageRole.USER
                // 把组内节点 id 映射回最新实例, 流式更新只影响真正变化的那条消息
                val groupNodes = group.ids.mapNotNull { latestNodeById[it] }
                val isLastGroup = groupIndex == displayGroups.lastIndex
                val groupAssistant = settings.getAssistantById(conversation.assistantId)
                // 直接计算 (不再 remember 节点列表): findModelById 是小列表查找,
                // 比每 token 对整组节点做深度 equals 便宜得多
                val groupModel = groupNodes.lastOrNull()?.currentMessage?.modelId?.let { settings.findModelById(it) }
                // 带工具的 AI 消息在分组时已独立成组 (buildGroups), 渲染时组级头像不画,
                // 由消息内部分阶段(思考/工具/回复)头像接管, 避免两个头像并排
                val groupHasToolParts = !isUserGroup && group.hasTool
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // AI 组头像在左 (未分阶段头像的 assistant 组一律带头像), 用户组头像在右
                    if (!isUserGroup && !groupHasToolParts) {
                        ChatMessageSideAvatar(
                            isUser = false,
                            model = groupModel,
                            assistant = groupAssistant,
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        groupNodes.forEachIndexed { nodeIndex, node ->
                            ListSelectableItem(
                                key = node.id,
                                onSelectChange = {
                                    if (!selectedItems.contains(node.id)) {
                                        selectedItems.add(node.id)
                                    } else {
                                        selectedItems.remove(node.id)
                                    }
                                },
                                selectedKeys = selectedItems,
                                enabled = selecting,
                            ) {
                                ChatMessage(
                                    node = node,
                                    model = node.currentMessage.modelId?.let { settings.findModelById(it) },
                                    assistant = groupAssistant,
                                    loading = loading && isLastGroup && nodeIndex == groupNodes.lastIndex,
                                    listScrolling = state.isScrollInProgress,
                                    onRegenerate = {
                                        onRegenerate(node.currentMessage)
                                    },
                                    onEdit = {
                                        onEdit(node.currentMessage)
                                    },
                                    onFork = {
                                        onForkMessage(node.currentMessage)
                                    },
                                    onDelete = {
                                        onDelete(node.currentMessage)
                                    },
                                    onShare = {
                                        selecting = true  // 使用 CoroutineScope 延迟状态更新
                                        selectedItems.clear()
                                        selectedItems.addAll(conversation.messageNodes.map { it.id }
                                            .subList(0, conversation.messageNodes.indexOf(node) + 1))
                                    },
                                    onUpdate = {
                                        onUpdateMessage(it)
                                    },
                                    isFavorite = node.isFavorite,
                                    onToggleFavorite = {
                                        onToggleFavorite?.invoke(node)
                                    },
                                    onTranslate = onTranslate,
                                    onClearTranslation = onClearTranslation,
                                    onToolApproval = onToolApproval,
                                    onToolAnswer = onToolAnswer,
                                    onQuote = onQuote,
                                    lastMessage = isLastGroup && nodeIndex == groupNodes.lastIndex,
                                )
                            }
                        }
                        // 组末一条下方: "名字 时间" 小字; 最新发送的用户消息组在状态动画期间
                        // 改为显示 发送中/已送达 行, 动画结束(状态清空)后恢复 meta 小字
                        val groupSendVisual = sendVisual?.takeIf {
                            isUserGroup && groupNodes.any { node -> node.id == it.messageId }
                        }
                        if (groupSendVisual != null) {
                            SendStatusLine(
                                phase = groupSendVisual.phase,
                                fontSizeRatio = settings.displaySetting.fontSizeRatio,
                            )
                        } else {
                            ChatGroupMetaLine(
                                role = group.role,
                                nodes = groupNodes,
                                assistant = groupAssistant,
                                settings = settings,
                                // 分阶段头像模式下(组内有工具), 头像由阶段块自己绘制,
                                // meta 行需补一个头像宽度的左偏移, 否则会贴到头像列位置
                                startPadding = if (groupHasToolParts) (28 + 8).dp else 0.dp,
                            )
                        }
                    }
                    if (isUserGroup) {
                        ChatMessageSideAvatar(
                            isUser = true,
                            model = groupModel,
                            assistant = groupAssistant,
                        )
                    }
                }
            }

            val assistant = settings.getAssistantById(conversation.assistantId)
            if (!loading && assistant?.allowConversationSystemPrompt == true && onConversationSystemPromptChange != null) {
                item(key = "ConversationSystemPrompt") {
                    ConversationSystemPromptButton(
                        customSystemPrompt = conversation.customSystemPrompt,
                        onSystemPromptChange = onConversationSystemPromptChange,
                    )
                }
            }

            // processingStatus 文字跟跳点同住一个 item: 跳点隐藏时 status 文字也一起消失,
            // 思考期间画面上只保留思考链动画
            if (showLoadingItem) {
                item(LoadingIndicatorKey) {
                    // 等待动画: 组结构 (AI 头像 28dp 在左 + 右侧三个 6dp 粉点跳动), 不再用兔子
                    val loadingAssistant = settings.getAssistantById(conversation.assistantId)
                    val loadingModel = loadingAssistant?.chatModelId?.let { settings.findModelById(it) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ChatMessageSideAvatar(
                            isUser = false,
                            model = loadingModel,
                            assistant = loadingAssistant,
                        )
                        Row(
                            modifier = Modifier.padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 三个 6dp 粉点上下跳 (周期 1.1s, 相位差 0.15s), 与消息内等待态共用同一组件
                            BouncingLoadingDots()
                            AnimatedVisibility(
                                visible = processingStatus != null,
                                modifier = Modifier.padding(start = 4.dp),
                            ) {
                                Text(
                                    text = processingStatus ?: "",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // 为了能正确滚动到这
            item(ScrollBottomKey) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 错误消息卡片
            ErrorCardsDisplay(
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(5f)
            )

            // 完成选择
            AnimatedVisibility(
                visible = selecting,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -(48).dp),
                enter = slideInVertically(
                    initialOffsetY = { it * 2 },
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it * 2 },
                ),
            ) {
                HorizontalFloatingToolbar(
                    expanded = true,
                ) {
                    Tooltip(
                        tooltip = {
                            Text("Clear selection")
                        }
                    ) {
                        IconButton(
                            onClick = {
                                selecting = false
                                selectedItems.clear()
                            }
                        ) {
                            Icon(HugeIcons.Cancel01, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text("Select all")
                        }
                    ) {
                        IconButton(
                            onClick = {
                                if (selectedItems.isNotEmpty()) {
                                    selectedItems.clear()
                                } else {
                                    selectedItems.addAll(conversation.messageNodes.map { it.id })
                                }
                            }
                        ) {
                            Icon(HugeIcons.CursorPointer01, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text("Confirm")
                        }
                    ) {
                        FilledIconButton(
                            onClick = {
                                selecting = false
                                val messages = conversation.messageNodes.filter { it.id in selectedItems }
                                if (messages.isNotEmpty()) {
                                    showExportSheet = true
                                }
                            }
                        ) {
                            Icon(HugeIcons.Tick01, null)
                        }
                    }
                }
            }

            // 导出对话框
            ChatExportSheet(
                visible = showExportSheet,
                onDismissRequest = {
                    showExportSheet = false
                    selectedItems.clear()
                },
                conversation = conversation,
                selectedMessages = conversation.messageNodes.filter { it.id in selectedItems }
                    .map { it.currentMessage }
            )

            val captureProgress = LocalScrollCaptureInProgress.current

            // 消息快速跳转
            MessageJumper(
                show = isRecentScroll && !state.isScrollInProgress && settings.displaySetting.showMessageJumper && !captureProgress,
                onLeft = settings.displaySetting.messageJumperOnLeft,
                scope = scope,
                state = state
            )

            // Suggestion
            if (conversation.chatSuggestions.isNotEmpty() && !captureProgress) {
                ChatSuggestionsRow(
                    conversation = conversation,
                    onClickSuggestion = onClickSuggestion,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

/**
 * 提取包含搜索词的文本片段，确保匹配词在开头可见
 */
private fun extractMatchingSnippet(
    text: String,
    query: String
): String {
    if (query.isBlank()) {
        return text
    }

    val matchIndex = text.indexOf(query, ignoreCase = true)
    if (matchIndex == -1) {
        return text
    }

    // 直接从匹配词开始显示，确保匹配词在最前面
    val snippet = text.substring(matchIndex)

    // 只在前面有内容时添加省略号
    return if (matchIndex > 0) {
        "...$snippet"
    } else {
        snippet
    }
}

private fun buildHighlightedText(
    text: String,
    query: String,
    highlightColor: Color
): AnnotatedString {
    if (query.isBlank()) {
        return AnnotatedString(text)
    }

    return buildAnnotatedString {
        var startIndex = 0
        var index = text.indexOf(query, startIndex, ignoreCase = true)

        while (index >= 0) {
            // 添加高亮前的文本
            append(text.substring(startIndex, index))

            // 添加高亮文本
            withStyle(
                style = SpanStyle(
                    background = highlightColor,
                    color = Color.Black
                )
            ) {
                append(text.substring(index, index + query.length))
            }

            startIndex = index + query.length
            index = text.indexOf(query, startIndex, ignoreCase = true)
        }

        // 添加剩余文本
        if (startIndex < text.length) {
            append(text.substring(startIndex))
        }
    }
}

@Composable
private fun ChatListPreview(
    innerPadding: PaddingValues,
    conversation: Conversation,
    settings: Settings,
    hazeState: HazeState,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onJumpToMessage: (Int) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    // 过滤消息，同时保留原始 index 避免后续 O(n) indexOf 查找
    val filteredMessages = remember(conversation.messageNodes, searchQuery) {
        if (searchQuery.isBlank()) {
            conversation.messageNodes.mapIndexed { index, node -> index to node }
        } else {
            conversation.messageNodes.mapIndexed { index, node -> index to node }
                .filter { (_, node) -> node.currentMessage.toText().contains(searchQuery, ignoreCase = true) }
        }
    }

    Column(
        modifier = Modifier
            .padding(top = innerPadding.calculateTopPadding())
            .fillMaxSize(),
    ) {
        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.history_page_search)) },
            leadingIcon = {
                Icon(
                    imageVector = HugeIcons.Search01,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = "Clear",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            singleLine = true,
            shape = CircleShape,
            maxLines = 1,
        )

        // 消息预览
        LazyColumn(
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 32.dp + innerPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            itemsIndexed(
                items = filteredMessages,
                key = { index, item -> item.second.id },
            ) { _, (originalIndex, node) ->
                val message = node.currentMessage
                val isUser = message.role == me.rerere.ai.core.MessageRole.USER
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (!isUser) Modifier.padding(end = 24.dp) else Modifier
                        ),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (isUser) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable {
                                    onJumpToMessage(originalIndex)
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
                            val highlightedText = remember(searchQuery, message) {
                                val fullText = message.toText().trim().ifBlank { "[...]" }
                                val messageText = extractMatchingSnippet(
                                    text = fullText,
                                    query = searchQuery
                                )
                                buildHighlightedText(
                                    text = messageText,
                                    query = searchQuery,
                                    highlightColor = highlightColor
                                )
                            }
                            Text(
                                text = highlightedText,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatSuggestionsRow(
    modifier: Modifier = Modifier,
    conversation: Conversation,
    onClickSuggestion: (String) -> Unit
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(conversation.chatSuggestions) { suggestion ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        onClickSuggestion(suggestion)
                    }
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                    .padding(vertical = 4.dp, horizontal = 8.dp),
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun BoxScope.MessageJumper(
    show: Boolean,
    onLeft: Boolean,
    scope: CoroutineScope,
    state: LazyListState
) {
    AnimatedVisibility(
        visible = show,
        modifier = Modifier.align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd),
        enter = slideInHorizontally(
            initialOffsetX = { if (onLeft) -it * 2 else it * 2 },
        ),
        exit = slideOutHorizontally(
            targetOffsetX = { if (onLeft) -it * 2 else it * 2 },
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                onClick = {
                    scope.launch {
                        state.scrollToItem(0)
                    }
                },
                shape = CircleShape,
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowUpDouble,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        state.animateScrollToItem(
                            (state.firstVisibleItemIndex - 1).fastCoerceAtLeast(
                                0
                            )
                        )
                    }
                },
                shape = CircleShape,
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowUp01,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        state.animateScrollToItem(state.firstVisibleItemIndex + 1)
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowDown01,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        state.scrollToItem(state.layoutInfo.totalItemsCount - 1)
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f),
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowDownDouble,
                    contentDescription = stringResource(R.string.chat_page_scroll_to_bottom),
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
        }
    }
}

// 组末一条消息下方的 "名字 时间" 小字 (9sp 灰)
// 对齐: AI 消息左对齐内容列起点 (与思考链行左缘一致), 用户消息靠右对齐内容列右缘;
// 与气泡垂直间距 2dp; 字体/字号/颜色/格式保持原样不动
@Composable
private fun ChatGroupMetaLine(
    role: MessageRole,
    nodes: List<MessageNode>,
    assistant: Assistant?,
    settings: Settings,
    startPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val isUser = role == MessageRole.USER
    val lastMessage = nodes.lastOrNull()?.currentMessage ?: return
    val fontSizeRatio = settings.displaySetting.fontSizeRatio
    val showModelName = settings.displaySetting.showModelName
    // 每次重组直接计算, 保证昵称/助手名等设置变化即时生效
    val timeText = remember(lastMessage.createdAt) {
        lastMessage.createdAt.toJavaLocalDateTime()
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    }
    val name = if (isUser) {
        settings.displaySetting.userNickname.takeIf { it.isNotBlank() }
    } else if (showModelName) {
        assistant?.name?.takeIf { it.isNotBlank() } ?: "AI"
    } else {
        null
    }
    val metaText = if (name != null) "$name · $timeText" else timeText
    // AI 消息: 小字旁的朗读小喇叭 (与菜单里的朗读同逻辑, 从气泡菜单挪出)
    val tts = if (isUser) null else LocalTTSState.current
    val ttsSpeaking = tts?.isSpeaking?.collectAsState()?.value == true
    val ttsAvailable = tts?.isAvailable?.collectAsState()?.value == true
    val metaColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    Row(
        // 与气泡的 2dp 垂直间距由父级 Column 的 spacedBy(2.dp) 提供, 这里不再叠加 padding
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startPadding),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = metaText,
            fontSize = 9.sp * fontSizeRatio,
            color = metaColor,
        )
        if (tts != null && ttsAvailable) {
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = if (ttsSpeaking) HugeIcons.StopCircle else HugeIcons.VolumeHigh,
                contentDescription = if (ttsSpeaking) "停止朗读" else "朗读",
                modifier = Modifier
                    .size(9.dp * fontSizeRatio)
                    .clickable {
                        if (!ttsSpeaking) {
                            val text = lastMessage.toText()
                            val textToSpeak = if (settings.ttsOnlyReadQuoted) {
                                text.extractQuotedContentAsText() ?: text
                            } else {
                                text
                            }
                            tts.speak(textToSpeak)
                        } else {
                            tts.stop()
                        }
                    },
                tint = metaColor,
            )
        }
    }
}

// 发送状态行 (用户消息组末): 发送中 900ms -> 已送达 2s -> 500ms 淡出后换回 meta 小字
// 样式对齐 v4 定稿: 9sp 小字 + 8dp 粉色描边转圈 / 9dp 绿勾 + 绿灰文字
@Composable
private fun SendStatusLine(
    phase: SendPhase,
    fontSizeRatio: Float,
    modifier: Modifier = Modifier,
) {
    // FADING 阶段整行 500ms 淡出, 其余阶段保持可见
    val lineAlpha by animateFloatAsState(
        targetValue = if (phase == SendPhase.FADING) 0f else 1f,
        animationSpec = tween(500),
        label = "sendStateAlpha",
    )
    Row(
        // 与气泡的 2dp 垂直间距由父级 Column 的 spacedBy(2.dp) 提供, 这里不再叠加 padding
        modifier = modifier
            .fillMaxWidth()
            .alpha(lineAlpha),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (phase == SendPhase.SENDING) {
            SendStateSpinner()
            Spacer(Modifier.width(4.dp))
            Text(
                text = "发送中…",
                fontSize = 9.sp * fontSizeRatio,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        } else {
            // SENT / FADING: 绿勾 + 已送达
            Icon(
                imageVector = HugeIcons.Tick01,
                contentDescription = null,
                tint = Color(0xFFA9BFA9),
                modifier = Modifier.size(9.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "已送达",
                fontSize = 9.sp * fontSizeRatio,
                color = Color(0xFFA9BFA9),
            )
        }
    }
}

// 发送中转圈: 8dp 圆环, 灰色底环 + 玫瑰粉高亮弧 (700ms 线性旋转)
@Composable
private fun SendStateSpinner(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "sendRing")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
        ),
        label = "sendRingAngle",
    )
    Canvas(modifier = modifier.size(8.dp)) {
        val stroke = 1.dp.toPx()
        drawCircle(
            color = Color(0xFFCFC7C1),
            style = Stroke(width = stroke),
        )
        drawArc(
            color = Color(0xFFC4958E),
            startAngle = angle - 90f,
            sweepAngle = 90f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

// 展示节点过滤缓存: 以 "节点 id -> (节点实例, 过滤结果)" 记忆。
// 流式时列表里绝大多数节点是同一实例 (引用相等), 直接命中缓存跳过 toText;
// 只有被更新重建的那条消息 (新实例) 才重新检查, 会话切换后旧条目因 id 不匹配会被覆盖
private class DisplayNodeFilterCache {
    private val cache = HashMap<Uuid, Pair<MessageNode, Boolean>>()

    fun filter(nodes: List<MessageNode>): List<MessageNode> {
        return nodes.filter { isDisplayed(it) }
    }

    private fun isDisplayed(node: MessageNode): Boolean {
        cache[node.id]?.let { (cachedNode, result) ->
            if (cachedNode === node) return result
        }
        val result = checkNode(node)
        cache[node.id] = node to result
        return result
    }

    private fun checkNode(node: MessageNode): Boolean {
        val msg = node.currentMessage
        val text = msg.toText().trim()
        val isSyntheticTrigger = msg.role == MessageRole.USER && (
            text.contains("[主动消息上下文]") ||
                text.contains("[心跳检查上下文]") ||
                text.startsWith("[现在是")
            )
        return !(msg.role == MessageRole.ASSISTANT && text == "[SKIP]") && !isSyntheticTrigger
    }
}

// 消息组: 角色相同的相邻消息合并, 额外按 "是否带工具调用" 细分。
// 带 Tool 的 AI 消息必须独立成组: 这类组不画组级头像 (由消息内部分阶段头像接管),
// 若与普通消息混在一组, 普通消息会因组头像被抑制而失去头像 (心跳主动消息无头像的根因)。
// 结构只由 id/角色/工具标记这些不变量构成, 不携带节点实例, 保证流式期间组的参数稳定
data class ChatDisplayGroup(
    val role: MessageRole,
    val hasTool: Boolean,
    val ids: List<Uuid>,
)

private fun buildGroups(nodes: List<MessageNode>): List<ChatDisplayGroup> {
    val groups = mutableListOf<ChatDisplayGroup>()
    var currentRole: MessageRole? = null
    var currentHasTool = false
    var currentIds = mutableListOf<Uuid>()
    var prevCreatedAt: kotlinx.datetime.LocalDateTime? = null
    fun flush() {
        if (currentIds.isNotEmpty()) {
            groups.add(ChatDisplayGroup(currentRole!!, currentHasTool, currentIds.toList()))
        }
    }
    nodes.forEach { node ->
        val msg = node.currentMessage
        val role = msg.role
        val hasTool = msg.parts.any { it is UIMessagePart.Tool }
        // 相邻同角色消息间隔超过 5 分钟视为一次新的开口 (心跳/主动消息必然间隔很久),
        // 独立成组拥有自己的头像; 正常连续聊天间隔在分钟级以内, 不受影响
        val gapSplit = prevCreatedAt?.let { prev ->
            java.time.Duration.between(
                prev.toJavaLocalDateTime(),
                msg.createdAt.toJavaLocalDateTime(),
            ).toMinutes() >= 5
        } ?: false
        if (role == currentRole && hasTool == currentHasTool && !gapSplit) {
            currentIds.add(node.id)
        } else {
            flush()
            currentRole = role
            currentHasTool = hasTool
            currentIds = mutableListOf(node.id)
        }
        prevCreatedAt = msg.createdAt
    }
    flush()
    return groups
}
