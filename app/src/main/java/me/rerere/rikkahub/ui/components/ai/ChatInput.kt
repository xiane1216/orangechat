/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ai

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.ui.UIMessagePart
import me.rerere.asr.ASRStatus
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.FullScreen
import me.rerere.hugeicons.stroke.Voice
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.getQuickMessagesOfAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.service.VoiceCallService
import me.rerere.rikkahub.ui.components.ui.KeepScreenOn
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalCurrentAssistant
import me.rerere.rikkahub.ui.context.LocalCurrentChatModel
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import me.rerere.rikkahub.ui.context.LocalProviders
import me.rerere.rikkahub.ui.context.LocalQuickMessages
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.SoundEffectPlayer
import org.koin.compose.koinInject
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

enum class ExpandState {
    Collapsed, Files,
}

@Composable
fun ChatInput(
    state: ChatInputState,
    loading: Boolean,
    conversation: Conversation,
    settings: Settings,
    mcpManager: McpManager,
    hazeState: HazeState,
    enableSearch: Boolean,
    onToggleSearch: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onUpdateChatModel: (Model) -> Unit,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    onCompressContext: (additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int) -> Job,
    onCancelClick: () -> Unit,
    onSendClick: () -> Unit,
    onLongSendClick: () -> Unit,
    onVoiceMessage: ((url: String, duration: Long, transcript: String, emotion: String, voiceEvents: String) -> Unit)? = null,
    autoStartVoice: Boolean = false,
) {
    val toaster = LocalToaster.current
    val assistant = settings.getCurrentAssistant()
    val hazeTintColor = MaterialTheme.colorScheme.surfaceContainerLow

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun sendMessage() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (loading) onCancelClick() else onSendClick()
    }

    fun sendMessageWithoutAnswer() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (loading) onCancelClick() else onLongSendClick()
    }

    var expand by remember { mutableStateOf(ExpandState.Collapsed) }
    var showInjectionSheet by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    fun dismissExpand() {
        expand = ExpandState.Collapsed
        showInjectionSheet = false
        showCompressDialog = false
    }

    fun expandToggle(type: ExpandState) {
        if (expand == type) {
            dismissExpand()
        } else {
            expand = type
        }
    }

    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val senseVoiceManager: me.rerere.rikkahub.data.voice.SenseVoiceManager = koinInject()
    val asr = LocalASRState.current
    val asrState by asr.state.collectAsState()
    val voiceCallActiveId by VoiceCallService.activeConversationId.collectAsStateWithLifecycle()
    val isVoiceCallActive = voiceCallActiveId != null
    val hapticFeedback = LocalHapticFeedback.current
    val soundEffectPlayer: SoundEffectPlayer = koinInject()
    LaunchedEffect(Unit) {
        soundEffectPlayer.preload(R.raw.asr_start, R.raw.asr_stop)
    }
    val asrPermission = rememberPermissionState(PermissionRecordAudio)
    PermissionManager(permissionState = asrPermission)
    var asrBaseText by remember { mutableStateOf("") }
    var voiceMessageMode by remember { mutableStateOf(false) }

    // ===== 微信式按住说话 =====
    var voiceMode by rememberSaveable { mutableStateOf(false) }
    var pressing by remember { mutableStateOf(false) }
    var recordSecs by remember { mutableStateOf(0) }
    var showModelDownloadDialog by remember { mutableStateOf(false) }
    var modelDownloadingText by remember { mutableStateOf<String?>(null) }
    val mainScope = rememberCoroutineScope()
    val senseVoiceReady = remember { mutableStateOf(senseVoiceManager.isModelReady()) }
    var localRecorder by remember { mutableStateOf<me.rerere.rikkahub.data.voice.LocalVoiceRecorder?>(null) }
    var usingLocalRecorder by remember { mutableStateOf(false) }
    // 下载进度监听 (模型首次下载时更新 pill 文案)
    LaunchedEffect(Unit) {
        senseVoiceManager.downloadState.collect { st ->
            when {
                st.done -> { senseVoiceReady.value = true; modelDownloadingText = null }
                st.isDownloading && st.totalBytes > 0 ->
                    modelDownloadingText = "模型下载中 ${st.receivedBytes * 100 / st.totalBytes}%"
                st.error != null -> modelDownloadingText = null
            }
        }
    }
    // 按住时计时
    LaunchedEffect(pressing) {
        if (pressing) {
            val start = System.currentTimeMillis()
            while (pressing) {
                recordSecs = ((System.currentTimeMillis() - start) / 1000).toInt()
                delay(100)
            }
        }
    }

    // Auto-start voice recording when entering from voice call notification
    LaunchedEffect(autoStartVoice) {
        if (autoStartVoice && asrState.status == ASRStatus.Idle && asrState.isAvailable) {
            if (asrPermission.allRequiredPermissionsGranted) {
                voiceMessageMode = true
                asr.start { }
            } else {
                asrPermission.requestPermissions()
            }
        }
    }

    LaunchedEffect(asrState.status) {
        when (asrState.status) {
            ASRStatus.Listening -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                soundEffectPlayer.play(R.raw.asr_start)
            }

            ASRStatus.Stopping -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                soundEffectPlayer.play(R.raw.asr_stop)
            }

            else -> {}
        }
    }
    LaunchedEffect(asrState.errorMessage) {
        asrState.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            toaster.show(message = message, type = ToastType.Error)
            voiceMessageMode = false
        }
    }

    // Handle voice message completion
    // 松手后: 停止录音 -> 本地 SenseVoice 提取语气/声音事件 (云端转写为空时兜底文字) -> 回调发送
    suspend fun finalizeVoiceMessage(path: String, cloudTranscript: String) {
        var emotion = ""
        var events = ""
        var transcript = cloudTranscript
        val wavFile = java.io.File(path)
        if (senseVoiceReady.value && !isVoiceCallActive) {
            runCatching {
                val r = senseVoiceManager.recognize(wavFile)
                emotion = r.emotion
                events = r.voiceEvents
                if (transcript.isBlank()) transcript = r.text
            }.onFailure { Log.w("ChatInput", "sensevoice recognize failed", it) }
        }
        // durationMs 上游从未赋值恒为 0, 从 WAV 头兜底计算秒数
        val durationSec = if (asrState.durationMs > 0 && !usingLocalRecorder) asrState.durationMs
        else runCatching { senseVoiceManager.wavDurationSec(wavFile) }.getOrDefault(0L)
        onVoiceMessage?.invoke(path, durationSec, transcript, emotion, events)
    }

    LaunchedEffect(asrState.audioFilePath, voiceMessageMode) {
        if (voiceMessageMode && asrState.audioFilePath != null && asrState.status == ASRStatus.Idle) {
            val path = asrState.audioFilePath!!
            val cloudTranscript = asrState.transcript
            finalizeVoiceMessage(path, cloudTranscript)
            voiceMessageMode = false
        }
    }

    // 首次使用按住说话: 本地情绪识别模型下载确认 (约228MB, 仅一次, 本地离线运行)
    if (showModelDownloadDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showModelDownloadDialog = false },
            title = { Text("下载语音情绪识别模型") },
            text = {
                Text(
                    "按住说话时可以用本地模型识别你说话的语气（开心、难过、生气）和声音（哭声、笑声、叹气等），" +
                        "让对方听得出你的情绪，完全离线、不上传录音。\n\n" +
                        "模型约 228MB，仅下载一次，建议在 Wi-Fi 下进行。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showModelDownloadDialog = false
                        modelDownloadingText = "模型下载中 0%"
                        mainScope.launch {
                            senseVoiceManager.startDownload()
                        }
                    }
                ) { Text("下载") }
            },
            dismissButton = {
                TextButton(onClick = { showModelDownloadDialog = false }) { Text("取消") }
            }
        )
    }

    // Camera launcher
    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    val (_, launchCameraCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            state.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            dismissExpand()
        },
        onCleanup = {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    )
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
        if (captureSuccessful && cameraOutputUri != null) {
            if (settings.displaySetting.skipCropImage) {
                state.addImages(filesManager.createChatFilesByContents(listOf(cameraOutputUri!!)))
                cameraOutputFile?.delete()
                cameraOutputFile = null
                cameraOutputUri = null
                dismissExpand()
            } else {
                launchCameraCrop(cameraOutputUri!!)
            }
        } else {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    }
    val onLaunchCamera: () -> Unit = {
        cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
        cameraOutputUri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", cameraOutputFile!!
        )
        cameraLauncher.launch(cameraOutputUri!!)
    }

    // Image picker launcher
    var preCropTempFile by remember { mutableStateOf<File?>(null) }
    val (_, launchImageCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            state.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            dismissExpand()
        },
        onCleanup = {
            preCropTempFile?.delete()
            preCropTempFile = null
        }
    )
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                Log.d("ImagePickButton", "Selected URIs: $selectedUris")
                if (settings.displaySetting.skipCropImage) {
                    state.addImages(filesManager.createChatFilesByContents(selectedUris))
                    dismissExpand()
                } else {
                    if (selectedUris.size == 1) {
                        val tempFile = File(context.appTempFolder, "pick_temp_${System.currentTimeMillis()}.jpg")
                        runCatching {
                            context.contentResolver.openInputStream(selectedUris.first())?.use { input ->
                                tempFile.outputStream().use { output -> input.copyTo(output) }
                            }
                            preCropTempFile = tempFile
                            launchImageCrop(tempFile.toUri())
                        }.onFailure {
                            Log.e("ImagePickButton", "Failed to copy image to temp, falling back", it)
                            launchImageCrop(selectedUris.first())
                        }
                    } else {
                        state.addImages(filesManager.createChatFilesByContents(selectedUris))
                        dismissExpand()
                    }
                }
            } else {
                Log.d("ImagePickButton", "No images selected")
            }
        }

    // Video picker launcher
    val videoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                state.addVideos(filesManager.createChatFilesByContents(selectedUris))
                dismissExpand()
            }
        }

    // Audio picker launcher
    val audioPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                state.addAudios(filesManager.createChatFilesByContents(selectedUris))
                dismissExpand()
            }
        }

    // File picker launcher
    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                val allowedMimeTypes = setOf(
                    "text/plain", "text/html", "text/css", "text/javascript", "text/csv", "text/xml",
                    "application/json", "application/javascript", "application/pdf",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-powerpoint",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/epub+zip",
                    "application/zip", "application/x-zip-compressed"
                )

                val allDocuments = mutableListOf<UIMessagePart.Document>()
                val allImageUris = mutableListOf<Uri>()

                uris.forEach { uri ->
                    val fileName = filesManager.getFileNameFromUri(uri) ?: "file"
                    val mime = filesManager.getFileMimeType(uri) ?: "text/plain"
                    val isZip = mime == "application/zip" || mime == "application/x-zip-compressed" ||
                        fileName.endsWith(".zip", ignoreCase = true)

                    if (isZip) {
                        // Auto-extract ZIP and add internal files
                        val extracted = filesManager.extractZipToChatFiles(uri, fileName)
                        allDocuments.addAll(extracted.documents)
                        allImageUris.addAll(extracted.images)
                    } else {
                        val isAllowed = allowedMimeTypes.contains(mime) || mime.startsWith("text/") ||
                            mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
                            mime == "application/pdf" ||
                            fileName.endsWith(".txt", ignoreCase = true) ||
                            fileName.endsWith(".md", ignoreCase = true) ||
                            fileName.endsWith(".csv", ignoreCase = true) ||
                            fileName.endsWith(".json", ignoreCase = true) ||
                            fileName.endsWith(".js", ignoreCase = true) ||
                            fileName.endsWith(".jsx", ignoreCase = true) ||
                            fileName.endsWith(".mjs", ignoreCase = true) ||
                            fileName.endsWith(".cjs", ignoreCase = true) ||
                            fileName.endsWith(".html", ignoreCase = true) ||
                            fileName.endsWith(".css", ignoreCase = true) ||
                            fileName.endsWith(".vue", ignoreCase = true) ||
                            fileName.endsWith(".svelte", ignoreCase = true) ||
                            fileName.endsWith(".xml", ignoreCase = true) ||
                            fileName.endsWith(".py", ignoreCase = true) ||
                            fileName.endsWith(".rb", ignoreCase = true) ||
                            fileName.endsWith(".lua", ignoreCase = true) ||
                            fileName.endsWith(".sql", ignoreCase = true) ||
                            fileName.endsWith(".java", ignoreCase = true) ||
                            fileName.endsWith(".kt", ignoreCase = true) ||
                            fileName.endsWith(".ts", ignoreCase = true) ||
                            fileName.endsWith(".tsx", ignoreCase = true) ||
                            fileName.endsWith(".dart", ignoreCase = true) ||
                            fileName.endsWith(".php", ignoreCase = true) ||
                            fileName.endsWith(".swift", ignoreCase = true) ||
                            fileName.endsWith(".go", ignoreCase = true) ||
                            fileName.endsWith(".bat", ignoreCase = true) ||
                            fileName.endsWith(".cmd", ignoreCase = true) ||
                            fileName.endsWith(".ps1", ignoreCase = true) ||
                            fileName.endsWith(".psm1", ignoreCase = true) ||
                            fileName.endsWith(".sh", ignoreCase = true) ||
                            fileName.endsWith(".bash", ignoreCase = true) ||
                            fileName.endsWith(".zsh", ignoreCase = true) ||
                            fileName.endsWith(".fish", ignoreCase = true) ||
                            fileName.endsWith(".c", ignoreCase = true) ||
                            fileName.endsWith(".h", ignoreCase = true) ||
                            fileName.endsWith(".cpp", ignoreCase = true) ||
                            fileName.endsWith(".cc", ignoreCase = true) ||
                            fileName.endsWith(".cxx", ignoreCase = true) ||
                            fileName.endsWith(".hpp", ignoreCase = true) ||
                            fileName.endsWith(".hh", ignoreCase = true) ||
                            fileName.endsWith(".hxx", ignoreCase = true) ||
                            fileName.endsWith(".rs", ignoreCase = true) ||
                            fileName.endsWith(".cs", ignoreCase = true) ||
                            fileName.endsWith(".markdown", ignoreCase = true) ||
                            fileName.endsWith(".mdx", ignoreCase = true) ||
                            fileName.endsWith(".toml", ignoreCase = true) ||
                            fileName.endsWith(".ini", ignoreCase = true) ||
                            fileName.endsWith(".env", ignoreCase = true) ||
                            fileName.endsWith(".gradle", ignoreCase = true) ||
                            fileName.endsWith(".kts", ignoreCase = true) ||
                            fileName.endsWith(".properties", ignoreCase = true) ||
                            fileName.endsWith(".proto", ignoreCase = true) ||
                            fileName.endsWith(".graphql", ignoreCase = true) ||
                            fileName.endsWith(".gql", ignoreCase = true) ||
                            fileName.endsWith(".yml", ignoreCase = true) ||
                            fileName.endsWith(".yaml", ignoreCase = true)
                        if (isAllowed) {
                            val localUri = filesManager.createChatFilesByContents(listOf(uri))[0]
                            allDocuments.add(UIMessagePart.Document(url = localUri.toString(), fileName = fileName, mime = mime))
                        } else {
                            toaster.show(
                                context.getString(R.string.chat_input_unsupported_file_type, fileName),
                                type = ToastType.Error
                            )
                        }
                    }
                }
                if (allDocuments.isNotEmpty()) {
                    state.addFiles(allDocuments)
                }
                if (allImageUris.isNotEmpty()) {
                    state.addImages(allImageUris)
                }
                if (allDocuments.isNotEmpty() || allImageUris.isNotEmpty()) {
                    dismissExpand()
                }
            }
        }

    // Collapse when ime is visible
    val imeVisile = WindowInsets.isImeVisible
    LaunchedEffect(imeVisile, showInjectionSheet, showCompressDialog) {
        if (imeVisile && !showInjectionSheet && !showCompressDialog) {
            dismissExpand()
        }
    }

    // Load input background image
    val inputBgPath = settings.displaySetting.inputBackgroundPath
    val inputBgBitmap = remember(inputBgPath) {
        if (inputBgPath.isNotBlank() && File(inputBgPath).exists()) {
            android.graphics.BitmapFactory.decodeFile(inputBgPath)?.asImageBitmap()
        } else null
    }

    Surface(
        color = Color.Transparent,
    ) {
        Column(
            modifier = modifier
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Expanded content
            Box(
                modifier = Modifier
                    .animateContentSize()
                    .fillMaxWidth()
            ) {
                BackHandler(
                    enabled = expand != ExpandState.Collapsed,
                ) {
                    dismissExpand()
                }
                if (expand == ExpandState.Files) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .then(
                                if (settings.displaySetting.enableBlurEffect || settings.displaySetting.bubbleGlassEnabled) Modifier.hazeEffect(
                                    state = hazeState,
                                    style = HazeMaterials.ultraThin()
                                )
                                else Modifier
                            ),
                        shape = RoundedCornerShape(20.dp),
                        tonalElevation = 0.dp,
                        color = if (settings.displaySetting.enableBlurEffect || settings.displaySetting.bubbleGlassEnabled) Color.Transparent else hazeTintColor,
                    ) {
                        Column {
                            // 定稿 UI: 加号面板分区 (模型 / 搜索与思考 / 附件)
                            Text(
                                text = "模型 / 搜索与思考",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 14.dp, top = 8.dp),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Model Picker
                                ModelSelector(
                                    modelId = assistant.chatModelId ?: settings.chatModelId,
                                    providers = settings.providers,
                                    onSelect = {
                                        onUpdateChatModel(it)
                                        dismissExpand()
                                    },
                                    type = ModelType.CHAT,
                                    onlyIcon = true,
                                    modifier = Modifier,
                                )

                                // Search
                                val enableSearchMsg = stringResource(R.string.web_search_enabled)
                                val disableSearchMsg = stringResource(R.string.web_search_disabled)
                                val chatModel = settings.getCurrentChatModel()
                                SearchPickerButton(
                                    enableSearch = enableSearch,
                                    settings = settings,
                                    onToggleSearch = { enabled ->
                                        onToggleSearch(enabled)
                                        toaster.show(
                                            message = if (enabled) enableSearchMsg else disableSearchMsg,
                                            duration = 1.seconds,
                                            type = if (enabled) {
                                                ToastType.Success
                                            } else {
                                                ToastType.Normal
                                            }
                                        )
                                    },
                                    onUpdateSearchService = onUpdateSearchService,
                                    model = chatModel,
                                )

                                // Reasoning
                                val model = settings.getCurrentChatModel()
                                if (model?.abilities?.contains(ModelAbility.REASONING) == true) {
                                    ReasoningButton(
                                        reasoningLevel = assistant.reasoningLevel,
                                        onUpdateReasoningLevel = {
                                            onUpdateAssistant(assistant.copy(reasoningLevel = it))
                                        },
                                        onlyIcon = true,
                                    )
                                }
                            }

                            FilesPicker(
                                conversation = conversation,
                                state = state,
                                assistant = assistant,
                                mcpManager = mcpManager,
                                onCompressContext = onCompressContext,
                                onUpdateAssistant = onUpdateAssistant,
                                showInjectionSheet = showInjectionSheet,
                                onShowInjectionSheetChange = { showInjectionSheet = it },
                                showCompressDialog = showCompressDialog,
                                onShowCompressDialogChange = { showCompressDialog = it },
                                onDismiss = { dismissExpand() },
                                onTakePic = onLaunchCamera,
                                onPickImage = { imagePickerLauncher.launch("image/*") },
                                onPickVideo = { videoPickerLauncher.launch("video/*") },
                                onPickAudio = { audioPickerLauncher.launch("audio/*") },
                                onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
                            )
                        }
                    }
                }
            }

            // Input area with optional background image (定稿: 整体胶囊形)
            // 输入框颜色: 自定义 inputFieldColor 优先 (含毛玻璃模式), 有背景图时图片优先
            // 毛玻璃生效: enableBlurEffect 或 bubbleGlassEnabled (气泡磨砂玻璃开关统一管理)
            // 颜色读取深浅色方案生效后的值 (LocalDisplaySettings 已按方案覆盖)
            val effectiveDisplay = LocalDisplaySettings.current
            val glassMode = effectiveDisplay.enableBlurEffect || effectiveDisplay.bubbleGlassEnabled
            val inputColor = effectiveDisplay.inputFieldColor?.let { it.toComposeColor() } ?: hazeTintColor
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .then(
                        if (glassMode) Modifier.hazeEffect(
                            state = hazeState,
                            style = HazeMaterials.ultraThin(containerColor = inputColor)
                        )
                        else Modifier
                    ),
                shape = RoundedCornerShape(26.dp),
                tonalElevation = 0.dp,
                // When background image is set, make surface transparent so image is visible;
                // blur mode: surface transparent, color tint comes from haze layer
                color = if (inputBgBitmap != null || glassMode) {
                    Color.Transparent
                } else {
                    inputColor
                },
            ) {
                // Use Box so background image can match parent size
                Box {
                    // Background image inside input area (matches content size exactly)
                    if (inputBgBitmap != null) {
                        Image(
                            bitmap = inputBgBitmap,
                            contentDescription = null,
                            modifier = Modifier
                                .matchParentSize()
                                .clip(MaterialTheme.shapes.largeIncreased),
                            contentScale = ContentScale.Crop,
                            alpha = 1f,
                        )
                    }
                    Column(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (state.messageContent.isNotEmpty()) {
                            MediaFileInputRow(state = state)
                        }

                        // 定稿 UI: 单行药丸输入条 [+] [输入框] [麦克风] [发送]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            ActionIconButton(
                                onClick = {
                                    expandToggle(ExpandState.Files)
                                }) {
                                Icon(
                                    imageVector = if (expand == ExpandState.Files) HugeIcons.Cancel01 else HugeIcons.Add01,
                                    contentDescription = stringResource(R.string.more_options)
                                )
                            }

                            if (voiceMode) {
                                // 微信式「按住 说话」: 按下开始录音, 松手发送
                                // 模型未就绪显示下载进度, 下载完成后即可用 (本地识别无需云端 ASR)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(42.dp)
                                        .clip(RoundedCornerShape(21.dp))
                                        .background(
                                            when {
                                                modelDownloadingText != null -> MaterialTheme.colorScheme.surfaceContainerHighest
                                                pressing -> MaterialTheme.colorScheme.primaryContainer
                                                else -> MaterialTheme.colorScheme.surfaceContainerHigh
                                            }
                                        )
                                        .pointerInput(senseVoiceReady.value) {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    val down = awaitFirstDown(requireUnconsumed = false)
                                                    down.consume()
                                                    if (!pressing && modelDownloadingText == null) {
                                                        when {
                                                            !asrPermission.allRequiredPermissionsGranted -> {
                                                                asrPermission.requestPermissions()
                                                            }
                                                            !senseVoiceReady.value -> {
                                                                showModelDownloadDialog = true
                                                            }
                                                            else -> {
                                                                // 云端 ASR 可能未配置 (start 会抛异常), 失败时走本地录音兜底
                                                                var started = false
                                                                try {
                                                                    voiceMessageMode = true
                                                                    asr.start { }
                                                                    started = true
                                                                } catch (e: Exception) {
                                                                    Log.w("ChatInput", "cloud asr start failed, fallback to local recorder", e)
                                                                }
                                                                if (started) {
                                                                    usingLocalRecorder = false
                                                                    pressing = true
                                                                    recordSecs = 0
                                                                } else {
                                                                    val rec = me.rerere.rikkahub.data.voice.LocalVoiceRecorder(context)
                                                                    if (rec.start()) {
                                                                        voiceMessageMode = true
                                                                        localRecorder = rec
                                                                        usingLocalRecorder = true
                                                                        pressing = true
                                                                        recordSecs = 0
                                                                    } else {
                                                                        voiceMessageMode = false
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                    waitForUpOrCancellation()
                                                    if (pressing) {
                                                        pressing = false
                                                        if (usingLocalRecorder) {
                                                            // 本地录音: 手动收尾 (不经过 asrState)
                                                            val path = localRecorder?.stop()
                                                            localRecorder = null
                                                            usingLocalRecorder = false
                                                            if (path != null && voiceMessageMode) {
                                                                val p = path
                                                                mainScope.launch { finalizeVoiceMessage(p, "") }
                                                            }
                                                            voiceMessageMode = false
                                                        } else {
                                                            asr.stop() // asrState 更新后由 LaunchedEffect 收尾
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (modelDownloadingText != null) {
                                        Text(
                                            text = modelDownloadingText!!,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    } else if (pressing) {
                                        // 录音中: 渐变音波 + 计时 + 松手提示
                                        val wavePhase by rememberInfiniteTransition(label = "wave").animateFloat(
                                            initialValue = 0f,
                                            targetValue = 1f,
                                            animationSpec = infiniteRepeatable(
                                                tween(durationMillis = 900, easing = LinearEasing)
                                            ),
                                            label = "wavePhase",
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            repeat(15) { i ->
                                                val c = Math.abs(i - 7) / 7f
                                                val h = 6 + 12 * (0.5 + 0.5 * Math.sin(wavePhase * 2 * Math.PI + c * 3.2)).toFloat()
                                                Box(
                                                    Modifier
                                                        .padding(horizontal = 0.75.dp)
                                                        .size(width = 2.5.dp, height = h.dp)
                                                        .clip(CircleShape)
                                                        .background(MaterialTheme.colorScheme.primary)
                                                )
                                            }
                                            Text(
                                                text = " $recordSecs″ · 松手 发送",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = "按住 说话",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else {
                                TextInputRow(
                                    state = state,
                                    onSendMessage = { sendMessage() },
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            // Voice button: 切换 打字/按住说话 模式 (微信式)
                            // 本地情绪识别模式下不再依赖云端 ASR 配置; 通话进行中禁用, 避免两路麦克风冲突
                            if (!isVoiceCallActive) {
                                ActionIconButton(
                                    onClick = {
                                        if (voiceMode) {
                                            voiceMode = false
                                            if (pressing) {
                                                pressing = false
                                                asr.stop()
                                            }
                                        } else {
                                            if (!asrPermission.allRequiredPermissionsGranted) {
                                                asrPermission.requestPermissions()
                                            } else {
                                                voiceMode = true
                                            }
                                        }
                                    }
                                ) {
                                    if (asrState.isRecording) {
                                        androidx.compose.material3.CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    } else {
                                        Icon(
                                            imageVector = HugeIcons.Voice,
                                            contentDescription = "Voice",
                                            tint = if (voiceMode) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }

                            AnimatedVisibility(
                                visible = !asrState.isRecording,
                                enter = fadeIn() + scaleIn(),
                                exit = fadeOut() + scaleOut(),
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .combinedClickable(
                                            enabled = loading || !state.isEmpty(),
                                            onClick = {
                                                dismissExpand()
                                                sendMessage()
                                            }, onLongClick = {
                                                dismissExpand()
                                                sendMessageWithoutAnswer()
                                            }
                                        )
                                ) {
                                    // 定稿: 就绪=深色圆钮(亮色模式)/浅色(暗色模式), 空内容=灰色 dim
                                    val containerColor = when {
                                        loading -> MaterialTheme.colorScheme.errorContainer
                                        state.isEmpty() -> MaterialTheme.colorScheme.surfaceContainerHigh
                                        else -> MaterialTheme.colorScheme.inverseSurface
                                    }
                                    val contentColor = when {
                                        loading -> MaterialTheme.colorScheme.onErrorContainer
                                        state.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                                        else -> MaterialTheme.colorScheme.inverseOnSurface
                                    }
                                    Surface(
                                        modifier = Modifier.fillMaxSize(),
                                        shape = CircleShape,
                                        color = containerColor,
                                        content = {})
                                    if (loading) {
                                        KeepScreenOn()
                                        Icon(
                                            imageVector = HugeIcons.Cancel01,
                                            contentDescription = stringResource(R.string.stop),
                                            tint = contentColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = HugeIcons.ArrowUp02,
                                            contentDescription = stringResource(R.string.send),
                                            tint = contentColor,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(30.dp),
        shape = CircleShape,
        tonalElevation = 0.dp,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
private fun TextInputRow(
    state: ChatInputState,
    onSendMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displaySettings = LocalDisplaySettings.current
    val filesManager: FilesManager = koinInject()
    val assistant = LocalCurrentAssistant.current
    val allQuickMessages = LocalQuickMessages.current
    val quickMessages = remember(allQuickMessages, assistant.quickMessageIds) {
        allQuickMessages.filter { it.id in assistant.quickMessageIds }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (state.isEditing()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(R.string.editing))
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.cancel_edit),
                        modifier = Modifier.clickable { state.clearInput() }
                    )
                }
            }
        }

        var isFocused by remember { mutableStateOf(false) }
        var isFullScreen by remember { mutableStateOf(false) }
        // 内层小胶囊颜色: 自定义输入框颜色的半透明版, 未设置时保持主题默认
        val customFieldColor = displaySettings.inputFieldColor?.let { it.toComposeColor() }?.copy(alpha = 0.5f)
            ?: MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)
        val receiveContentListener = remember(
            displaySettings.pasteLongTextAsFile, displaySettings.pasteLongTextThreshold
        ) {
            ReceiveContentListener { transferableContent ->
                when {
                    transferableContent.hasMediaType(MediaType.Image) -> {
                        transferableContent.consume { item ->
                            val uri = item.uri
                            if (uri != null) {
                                state.addImages(
                                    filesManager.createChatFilesByContents(
                                        listOf(uri)
                                    )
                                )
                            }
                            uri != null
                        }
                    }

                    displaySettings.pasteLongTextAsFile && transferableContent.hasMediaType(MediaType.Text) -> {
                        transferableContent.consume { item ->
                            val text = item.text?.toString()
                            if (text != null && text.length > displaySettings.pasteLongTextThreshold) {
                                val document = filesManager.createChatTextFile(text)
                                state.addFiles(listOf(document))
                                true
                            } else {
                                false
                            }
                        }
                    }

                    else -> transferableContent
                }
            }
        }
        TextField(
            state = state.textContent,
            modifier = Modifier
                .fillMaxWidth()
                .contentReceiver(receiveContentListener)
                .onFocusChanged {
                    isFocused = it.isFocused
                },
            shape = RoundedCornerShape(22.dp),
            placeholder = {
                Text(
                    "Message to ${displaySettings.partnerTitle}...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
            },
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
            keyboardOptions = KeyboardOptions(
                imeAction = if (displaySettings.sendOnEnter) ImeAction.Send else ImeAction.Default
            ),
            onKeyboardAction = {
                if (displaySettings.sendOnEnter && !state.isEmpty()) {
                    onSendMessage()
                }
            },
            colors = TextFieldDefaults.colors().copy(
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                focusedContainerColor = customFieldColor,
                unfocusedContainerColor = customFieldColor,
            ),
            trailingIcon = {
                if (isFocused) {
                    IconButton(
                        onClick = {
                            isFullScreen = !isFullScreen
                        }) {
                        Icon(HugeIcons.FullScreen, null)
                    }
                }
            },
            leadingIcon = if (quickMessages.isNotEmpty()) {
                {
                    QuickMessageButton(quickMessages = quickMessages, state = state)
                }
            } else null,
        )
        if (isFullScreen) {
            FullScreenEditor(state = state) {
                isFullScreen = false
            }
        }
    }
}

@Composable
private fun QuickMessageButton(
    quickMessages: List<QuickMessage>,
    state: ChatInputState,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(
        onClick = {
            expanded = !expanded
        }) {
        Icon(HugeIcons.Zap, null)
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .widthIn(min = 200.dp)
                .width(IntrinsicSize.Min)
        ) {
            quickMessages.forEach { quickMessage ->
                Surface(
                    onClick = {
                        state.appendText(quickMessage.content)
                        expanded = false
                    },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            text = quickMessage.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = quickMessage.content,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreenEditor(
    state: ChatInputState, onDone: () -> Unit
) {
    BasicAlertDialog(
        onDismissRequest = {
            onDone()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 800.dp),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row {
                        TextButton(
                            onClick = {
                                onDone()
                            }) {
                            Text(stringResource(R.string.chat_page_save))
                        }
                    }
                    TextField(
                        state = state.textContent,
                        modifier = Modifier
                            .padding(bottom = 2.dp)
                            .fillMaxSize(),
                        shape = RoundedCornerShape(32.dp),
                        placeholder = {
                            Text(
                                "Message to ${LocalDisplaySettings.current.partnerTitle}...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            )
                        },
                        colors = TextFieldDefaults.colors().copy(
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                }
            }
        }
    }
}