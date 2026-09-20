/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.Translate
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.service.VoiceCallService
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "VoiceCallPage"

/** 通话头像保存路径 */
private val Context.voiceCallAvatarFile: File
    get() = File(filesDir, "voice_call_avatar.jpg")

/**
 * 语音通话页面
 */
@Composable
fun VoiceCallPage(
    conversationId: Uuid,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var boundService by remember { mutableStateOf<VoiceCallService?>(null) }

    val asrPermission = rememberPermissionState(PermissionRecordAudio)

    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundService = (binder as? VoiceCallService.LocalBinder)?.getService()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                boundService = null
            }
        }
    }

    DisposableEffect(conversationId) {
        if (VoiceCallService.activeConversationId.value != conversationId.toString()) {
            if (asrPermission.allRequiredPermissionsGranted) {
                VoiceCallService.start(context, conversationId.toString())
            }
        }
        val intent = Intent(context, VoiceCallService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)

        onDispose {
            try {
                context.unbindService(connection)
            } catch (e: Exception) {
                Log.e(TAG, "unbindService 失败", e)
            }
        }
    }

    LaunchedEffect(asrPermission.allRequiredPermissionsGranted) {
        if (asrPermission.allRequiredPermissionsGranted &&
            VoiceCallService.activeConversationId.value == null
        ) {
            VoiceCallService.start(context, conversationId.toString())
        }
    }

    LaunchedEffect(Unit) {
        if (!asrPermission.allRequiredPermissionsGranted) {
            asrPermission.requestPermissions()
        }
    }

    val uiState by (boundService?.uiState
        ?: MutableStateFlow(VoiceCallUiState()).asStateFlow())
        .collectAsStateWithLifecycle(initialValue = VoiceCallUiState())

    BackHandler {
        onBack()
    }

    // 深色模式纯黑透彻, 浅色模式纯白
    val bg = MaterialTheme.colorScheme.background
    // 手动计算亮度 (0.299R + 0.587G + 0.114B)
    val lum = 0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue
    val isDark = lum < 0.5f
    val bgColor = if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)

    // 头像选择
    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    context.voiceCallAvatarFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
    var avatarVersion by remember { mutableStateOf(0) }

    // 实时通话计时
    var callDuration by remember { mutableStateOf(0L) }
    LaunchedEffect(uiState.callStartTime) {
        if (uiState.callStartTime > 0) {
            while (true) {
                callDuration = System.currentTimeMillis() - uiState.callStartTime
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 顶部留白, 把所有内容往下挪
            Spacer(modifier = Modifier.size(72.dp))

            // 名字 "Daddy" — 细体 + 字间距 + 发光
            Text(
                text = "Daddy",
                color = if (isDark) Color(0xFFFFFFFF) else Color(0xFF1A1A1A),
                fontSize = 30.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 6.sp,
                textAlign = TextAlign.Center,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = if (isDark) Color(0x66FFFFFF) else Color(0x33000000),
                        blurRadius = 18f,
                    )
                ),
            )
            Spacer(modifier = Modifier.size(10.dp))
            // 通话计时 — 细体等宽 + 大字间距
            Text(
                text = formatCallDuration(callDuration),
                color = if (isDark) Color(0x99FFFFFF) else Color(0x99000000),
                fontSize = 16.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 4.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )

            Spacer(modifier = Modifier.size(36.dp))

            // 头像 + 音波环
            CallAvatar(
                avatarFile = context.voiceCallAvatarFile,
                avatarVersion = avatarVersion,
                isDark = isDark,
                onClick = {
                    avatarPicker.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                    avatarVersion++
                },
            )

            Spacer(modifier = Modifier.size(24.dp))

            // 状态文字 (中文 + 英文)
            val (statusCn, statusEn) = when (uiState.status) {
                VoiceCallStatus.Listening -> "聆听中..." to "LISTENING"
                VoiceCallStatus.Processing -> "思考中..." to "THINKING"
                VoiceCallStatus.Working -> "Working..." to "WORKING"
                VoiceCallStatus.Speaking -> "说话中..." to "SPEAKING"
                VoiceCallStatus.Error -> "出错了" to "ERROR"
                VoiceCallStatus.Idle -> "等待中..." to "IDLE"
            }
            Text(
                text = statusCn,
                color = if (isDark) Color(0xFFFFFFFF) else Color(0xFF1A1A1A),
                fontSize = 16.sp,
                fontWeight = FontWeight.Light,
            )
            Text(
                text = statusEn,
                color = if (isDark) Color(0x66FFFFFF) else Color(0x66000000),
                fontSize = 11.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 3.sp,
                modifier = Modifier.padding(top = 2.dp),
            )

            Spacer(modifier = Modifier.size(12.dp))

            // 竖条音波 (仅 AI 说话时起伏)
            WaveformBars(
                isActive = uiState.status == VoiceCallStatus.Speaking,
                amplitudes = uiState.amplitudes,
            )

            Spacer(modifier = Modifier.size(16.dp))

            // 字幕卡片: weight(1f) 弹性占据中间剩余空间 —
            // 卡片高度固定为"屏幕减去顶部固定内容", 字幕再多也只在卡片内滚动,
            // 永远不会把底部四个按键挤小/挤出屏幕
            SubtitleCard(
                uiState = uiState,
                onReplay = { text -> boundService?.replayText(text) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 20.dp),
            )

            // 底部按钮: 麦克风 / 翻译 / 挂断 / 外放
            Row(
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 50.dp, top = 16.dp),
            ) {
                ControlButton(
                    icon = HugeIcons.Mic01,
                    contentDescription = "麦克风",
                    onClick = { boundService?.toggleMute() },
                    backgroundColor = if (uiState.isMuted) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    },
                    iconTint = if (uiState.isMuted) {
                        Color(0xFFC98A8A)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    enabled = boundService != null,
                )
                ControlButton(
                    icon = HugeIcons.Translate,
                    contentDescription = "翻译",
                    onClick = { boundService?.toggleTranslation() },
                    backgroundColor = if (uiState.translationEnabled) {
                        Color(0xFFC98A8A).copy(alpha = 0.25f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    },
                    iconTint = if (uiState.translationEnabled) {
                        Color(0xFFC98A8A)
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    },
                    enabled = boundService != null,
                )
                ControlButton(
                    icon = HugeIcons.Cancel01,
                    contentDescription = "挂断",
                    onClick = {
                        VoiceCallService.stop(context)
                        onBack()
                    },
                    backgroundColor = Color(0xFFE09A97),
                    iconTint = Color.White,
                    size = 58.dp,
                )
                ControlButton(
                    icon = HugeIcons.VolumeHigh,
                    contentDescription = "外放",
                    onClick = { boundService?.toggleSpeaker() },
                    backgroundColor = if (uiState.speakerOn) {
                        Color(0xFFC98A8A).copy(alpha = 0.25f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    },
                    iconTint = if (uiState.speakerOn) {
                        Color(0xFFC98A8A)
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    },
                    enabled = boundService != null,
                )
            }
        }
    }
}

private fun formatCallDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/**
 * 圆形头像 + 缓慢匀速音波环 + 边缘发光
 */
@Composable
private fun CallAvatar(
    avatarFile: File,
    avatarVersion: Int,
    isDark: Boolean,
    onClick: () -> Unit,
    size: Dp = 160.dp,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "avatar_ring")
    // 缓慢匀速呼吸, 不随状态变化
    val breathe by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "avatar_breathe",
    )
    val ringPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "avatar_ring",
    )

    // 深色模式用粉白光晕, 浅色模式用淡粉
    val ringColor = if (isDark) Color(0xFFE8C4C4) else Color(0xFFE0B0B0)
    val glowColor = if (isDark) Color(0x55E8C4C4) else Color(0x33E0B0B0)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size),
    ) {
        // 头像边缘发光层 (多层同心圆模拟 soft glow)
        Canvas(modifier = Modifier.size(size)) {
            val center = Offset(size.toPx() / 2, size.toPx() / 2)
            val avatarRadius = size.toPx() / 2 * 0.82f
            // 发光: 从头像边缘向外扩散的柔光晕
            for (i in 0 until 8) {
                val r = avatarRadius + i * 4f
                val alpha = (1f - i / 8f) * 0.18f
                drawCircle(
                    color = glowColor.copy(alpha = alpha),
                    radius = r,
                    center = center,
                )
            }
        }

        // 扩散音波环 (缓慢匀速, 始终存在)
        Canvas(modifier = Modifier.size(size)) {
            val center = Offset(size.toPx() / 2, size.toPx() / 2)
            val baseRadius = size.toPx() / 2 * 0.85f
            for (i in 0 until 3) {
                val phase = (ringPhase + i.toFloat() / 3f) % 1f
                val radius = baseRadius * (1f + phase * 0.35f)
                val alpha = (1f - phase) * 0.35f
                drawCircle(
                    color = ringColor.copy(alpha = alpha),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 2.5f),
                )
            }
        }

        // 头像本体
        Box(
            modifier = Modifier
                .size(size * 0.82f * breathe)
                .clip(CircleShape)
                .border(3.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            if (avatarFile.exists()) {
                AsyncImage(
                    model = avatarFile,
                    contentDescription = "头像",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = "D",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * 竖条音波 (仅 AI 说话时起伏)
 *
 * Speaking 期间 TTS 侧没有实时振幅数据 (amplitudes 来自 ASR), 若直接用
 * 旧数据波形会冻住. 这里在激活且无振幅时用无限动画模拟起伏.
 */
@Composable
private fun WaveformBars(
    isActive: Boolean,
    amplitudes: List<Float>,
    modifier: Modifier = Modifier,
) {
    val barCount = 28
    val hasAmps = isActive && amplitudes.isNotEmpty()

    // 无振幅数据时的模拟波形相位 (每根条错开的正弦)
    val phase by rememberInfiniteTransition(label = "waveform")
        .animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "waveform_phase",
        )

    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.height(36.dp),
    ) {
        val ampSize = amplitudes.size
        repeat(barCount) { i ->
            val h = when {
                hasAmps -> {
                    val amp = amplitudes[i % ampSize]
                    (0.15f + amp * 0.85f).coerceIn(0.1f, 1f)
                }
                // 激活但无数据: 正弦模拟, 相邻条相位错开
                isActive -> (0.35f + 0.3f * kotlin.math.sin(phase + i * 0.6f))
                    .coerceIn(0.15f, 0.8f)
                else -> 0.12f
            }
            val animatedH by animateFloatAsState(
                targetValue = h,
                animationSpec = tween(durationMillis = 120),
                label = "bar_$i",
            )
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = (30.dp * animatedH))
                    .background(
                        color = Color(0xFFE0A8A8).copy(alpha = if (isActive) 0.8f else 0.3f),
                        shape = RoundedCornerShape(2.dp),
                    )
            )
        }
    }
}

/**
 * 字幕卡片: 滚动历史 + 实时字幕
 *
 * 格式 (保留完整历史对话):
 *   · 宝宝  · 23:42
 *   xxxx
 *   · Daddy  · 23:42
 *   xxxx
 *   xxxx（翻译）
 *   ...
 */
@Composable
private fun SubtitleCard(
    uiState: VoiceCallUiState,
    onReplay: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val history = uiState.subtitleHistory

    // 历史新增时平滑滚动到底部 (用最后一条的 id 而不是 size, 避免同尺寸更新错过滚动)
    LaunchedEffect(history.lastOrNull()?.id, history.size) {
        if (history.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(history.size - 1) }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
        ) {
            // 工具调用提示
            AnimatedVisibility(
                visible = uiState.toolCallInfo != null,
                enter = fadeIn(tween(300)),
                exit = fadeOut(tween(300)),
            ) {
                uiState.toolCallInfo?.let { info ->
                    Text(
                        text = info,
                        color = Color(0xFFC98A8A),
                        fontSize = 12.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        fontWeight = FontWeight.Light,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            // 字幕历史: weight(1f) 在卡片内弹性伸缩, 内容在框内滚动,
            // 卡片整体高度由外层布局固定, 不随字幕长度变化
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(
                    items = history,
                    key = { it.id }
                ) { entry ->
                    SubtitleItem(entry = entry, onReplay = onReplay)
                }
            }

            // 实时用户字幕 (聆听中)
            if (uiState.status == VoiceCallStatus.Listening && uiState.userTranscript.isNotBlank()) {
                LiveSubtitle(
                    name = "宝宝",
                    nameColor = Color(0xFFC98A8A),
                    text = uiState.userTranscript,
                )
            }
            // 实时 AI 字幕 (说话中/思考中)
            if ((uiState.status == VoiceCallStatus.Speaking ||
                        uiState.status == VoiceCallStatus.Processing ||
                        uiState.status == VoiceCallStatus.Working) &&
                uiState.assistantText.isNotBlank()
            ) {
                LiveSubtitle(
                    name = "Daddy",
                    nameColor = Color(0xFF8A8F9C),
                    text = uiState.assistantText,
                    translation = uiState.assistantTranslation,
                )
            }
        }
    }
}

@Composable
private fun SubtitleItem(
    entry: SubtitleEntry,
    onReplay: (String) -> Unit,
) {
    val name = if (entry.isAssistant) "Daddy" else "宝宝"
    val nameColor = if (entry.isAssistant) Color(0xFF8A8F9C) else Color(0xFFC98A8A)
    val time = remember(entry.timestamp) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(entry.timestamp))
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 名字行: · 宝宝  · 23:42  (点加粗)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "·",
                color = nameColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = " $name ",
                color = nameColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "· $time",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                fontSize = 11.sp,
            )
        }
        // 消息内容
        Text(
            text = entry.text,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        // AI 回复的翻译
        if (entry.translation.isNotBlank()) {
            Text(
                text = entry.translation,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (entry.isAssistant) {
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReplayButton(onClick = { onReplay(entry.text) })
            }
        }
    }
}

@Composable
private fun LiveSubtitle(
    name: String,
    nameColor: Color,
    text: String,
    translation: String = "",
) {
    val time = remember {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("·", color = nameColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(" $name ", color = nameColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("· $time", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f), fontSize = 11.sp)
        }
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (translation.isNotBlank()) {
            Text(
                text = translation,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ReplayButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.height(28.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 14.dp),
        ) {
            Text(
                text = "重播语音",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * 控制按钮 (圆形)
 */
@Composable
private fun ControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    iconTint: Color,
    size: Dp = 54.dp,
    iconSizeRatio: Float = 0.38f,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (enabled) backgroundColor else backgroundColor.copy(alpha = 0.3f),
        modifier = Modifier.size(size),
        enabled = enabled,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) iconTint else iconTint.copy(alpha = 0.5f),
                modifier = Modifier.size(size * iconSizeRatio),
            )
        }
    }
}
