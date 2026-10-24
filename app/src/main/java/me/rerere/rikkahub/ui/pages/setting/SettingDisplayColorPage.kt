/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.datastore.ColorOverrideScheme
import me.rerere.rikkahub.data.datastore.ColorSchemeMode
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.applyColorScheme
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ColorPickerDialog
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingDisplayColorPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }

    // 编辑目标: global=全局单套颜色; light/dark=跟随系统时的浅色/深色方案
    var editTarget by rememberSaveable { mutableStateOf("global") }
    val followSystem = displaySetting.colorSchemeMode == ColorSchemeMode.FOLLOW_SYSTEM

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    // 当前编辑基底: 方案模式下, 方案未设置的项显示全局当前值作为起点
    val editBase = when (editTarget) {
        "light" -> displaySetting.lightColorScheme?.let { displaySetting.applyColorScheme(it) } ?: displaySetting
        "dark" -> displaySetting.darkColorScheme?.let { displaySetting.applyColorScheme(it) } ?: displaySetting
        else -> displaySetting
    }

    // 编辑目标路由: 方案模式下把颜色写进对应方案 (方案外颜色项不影响全局), 全局模式直接写
    fun updateEditBase(newBase: DisplaySetting) {
        when (editTarget) {
            "light" -> updateDisplaySetting(
                displaySetting.copy(
                    lightColorScheme = ColorOverrideScheme(
                        chatTextColor = newBase.chatTextColor,
                        globalTextColor = newBase.globalTextColor,
                        userBubbleColor = newBase.userBubbleColor,
                        userMessageFontColor = newBase.userMessageFontColor,
                        assistantBubbleColor = newBase.assistantBubbleColor,
                        thinkingBubbleColor = newBase.thinkingBubbleColor,
                        thinkingTextColor = newBase.thinkingTextColor,
                        chatBackgroundColor = newBase.chatBackgroundColor,
                        primaryColor = newBase.primaryColor,
                        inputFieldColor = newBase.inputFieldColor,
                    )
                )
            )
            "dark" -> updateDisplaySetting(
                displaySetting.copy(
                    darkColorScheme = ColorOverrideScheme(
                        chatTextColor = newBase.chatTextColor,
                        globalTextColor = newBase.globalTextColor,
                        userBubbleColor = newBase.userBubbleColor,
                        userMessageFontColor = newBase.userMessageFontColor,
                        assistantBubbleColor = newBase.assistantBubbleColor,
                        thinkingBubbleColor = newBase.thinkingBubbleColor,
                        thinkingTextColor = newBase.thinkingTextColor,
                        chatBackgroundColor = newBase.chatBackgroundColor,
                        primaryColor = newBase.primaryColor,
                        inputFieldColor = newBase.inputFieldColor,
                    )
                )
            )
            else -> updateDisplaySetting(newBase)
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var showChatTextColorPicker by remember { mutableStateOf(false) }
    var showGlobalTextColorPicker by remember { mutableStateOf(false) }
    var showUserBubbleColorPicker by remember { mutableStateOf(false) }
    var showUserMessageFontColorPicker by remember { mutableStateOf(false) }
    var showAssistantBubbleColorPicker by remember { mutableStateOf(false) }
    var showThinkingBubbleColorPicker by remember { mutableStateOf(false) }
    var showThinkingTextColorPicker by remember { mutableStateOf(false) }
    var showChatBackgroundColorPicker by remember { mutableStateOf(false) }
    var showPrimaryColorPicker by remember { mutableStateOf(false) }
    var showInputFieldColorPicker by remember { mutableStateOf(false) }

    if (showChatTextColorPicker) {
        ColorPickerDialog(
            initialColor = editBase.chatTextColor,
            defaultColor = MaterialTheme.colorScheme.onSurface,
            onConfirm = { updateEditBase(editBase.copy(chatTextColor = it)) },
            onDismiss = { showChatTextColorPicker = false }
        )
    }
    if (showGlobalTextColorPicker) {
        ColorPickerDialog(
            initialColor = editBase.globalTextColor,
            defaultColor = MaterialTheme.colorScheme.background,
            onConfirm = { updateEditBase(editBase.copy(globalTextColor = it)) },
            onDismiss = { showGlobalTextColorPicker = false }
        )
    }
    if (showUserBubbleColorPicker) {
        ColorPickerDialog(
            initialColor = editBase.userBubbleColor,
            defaultColor = MaterialTheme.colorScheme.secondaryContainer,
            onConfirm = { updateEditBase(editBase.copy(userBubbleColor = it)) },
            onDismiss = { showUserBubbleColorPicker = false }
        )
    }
    if (showUserMessageFontColorPicker) {
        ColorPickerDialog(
            initialColor = editBase.userMessageFontColor,
            defaultColor = MaterialTheme.colorScheme.onPrimary,
            onConfirm = { updateEditBase(editBase.copy(userMessageFontColor = it)) },
            onDismiss = { showUserMessageFontColorPicker = false }
        )
    }
    if (showAssistantBubbleColorPicker) {
        ColorPickerDialog(
            initialColor = editBase.assistantBubbleColor,
            defaultColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            onConfirm = { updateEditBase(editBase.copy(assistantBubbleColor = it)) },
            onDismiss = { showAssistantBubbleColorPicker = false }
        )
    }
    if (showThinkingBubbleColorPicker) {
        ColorPickerDialog(
            initialColor = editBase.thinkingBubbleColor,
            defaultColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            onConfirm = { updateEditBase(editBase.copy(thinkingBubbleColor = it)) },
            onDismiss = { showThinkingBubbleColorPicker = false }
        )
    }
    if (showThinkingTextColorPicker) {
        ColorPickerDialog(
            initialColor = editBase.thinkingTextColor,
            defaultColor = MaterialTheme.colorScheme.secondary,
            onConfirm = { updateEditBase(editBase.copy(thinkingTextColor = it)) },
            onDismiss = { showThinkingTextColorPicker = false }
        )
    }
    if (showChatBackgroundColorPicker) {
        ColorPickerDialog(
            initialColor = editBase.chatBackgroundColor,
            defaultColor = MaterialTheme.colorScheme.background,
            onConfirm = { updateEditBase(editBase.copy(chatBackgroundColor = it)) },
            onDismiss = { showChatBackgroundColorPicker = false }
        )
    }
    if (showPrimaryColorPicker) {
        ColorPickerDialog(
            initialColor = editBase.primaryColor,
            defaultColor = MaterialTheme.colorScheme.primary,
            onConfirm = { updateEditBase(editBase.copy(primaryColor = it)) },
            onDismiss = { showPrimaryColorPicker = false }
        )
    }
    if (showInputFieldColorPicker) {
        ColorPickerDialog(
            initialColor = editBase.inputFieldColor,
            defaultColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            onConfirm = { updateEditBase(editBase.copy(inputFieldColor = it)) },
            onDismiss = { showInputFieldColorPicker = false }
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("颜色自定义") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("颜色方案") },
                ) {
                    item(
                        headlineContent = { Text("跟随系统深浅色") },
                        supportingContent = { Text("手机切换深色/浅色模式时自动使用对应方案; 方案里没设置的颜色回退全局颜色") },
                        trailingContent = {
                            Switch(
                                checked = followSystem,
                                onCheckedChange = {
                                    updateDisplaySetting(
                                        displaySetting.copy(
                                            colorSchemeMode = if (it) ColorSchemeMode.FOLLOW_SYSTEM else ColorSchemeMode.NONE
                                        )
                                    )
                                    if (!it) editTarget = "global"
                                }
                            )
                        },
                    )
                    if (followSystem) {
                        item(
                            onClick = { editTarget = "global" },
                            headlineContent = { Text("编辑全局颜色") },
                            supportingContent = { Text("所有模式下通用的基础颜色") },
                            trailingContent = {
                                if (editTarget == "global") {
                                    Text("当前", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            },
                        )
                        item(
                            onClick = { editTarget = "light" },
                            headlineContent = { Text("编辑浅色方案") },
                            supportingContent = { Text("手机浅色模式时使用, 以全局颜色为起点") },
                            trailingContent = {
                                if (editTarget == "light") {
                                    Text("当前", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            },
                        )
                        item(
                            onClick = { editTarget = "dark" },
                            headlineContent = { Text("编辑深色方案") },
                            supportingContent = { Text("手机深色模式时使用, 以全局颜色为起点") },
                            trailingContent = {
                                if (editTarget == "dark") {
                                    Text("当前", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            },
                        )
                        if (editTarget != "global") {
                            item(
                                onClick = {
                                    when (editTarget) {
                                        "light" -> updateDisplaySetting(displaySetting.copy(lightColorScheme = null))
                                        "dark" -> updateDisplaySetting(displaySetting.copy(darkColorScheme = null))
                                    }
                                },
                                headlineContent = { Text("清除当前方案") },
                                supportingContent = { Text("清空正在编辑的方案, 颜色全部回退全局颜色") },
                            )
                        }
                    }
                }
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = {
                        Text(
                            when (editTarget) {
                                "light" -> "颜色自定义 (浅色方案)"
                                "dark" -> "颜色自定义 (深色方案)"
                                else -> "颜色自定义"
                            }
                        )
                    },
                ) {
                    item(
                        headlineContent = { Text("聊天正文颜色") },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .background(
                                            editBase.chatTextColor?.let { it.toComposeColor() } ?: Color.Gray,
                                            CircleShape
                                        )
                                )
                                TextButton(onClick = { showChatTextColorPicker = true }) { Text("自定义") }
                                if (editBase.chatTextColor != null) {
                                    TextButton(onClick = { updateEditBase(editBase.copy(chatTextColor = null)) }) { Text("重置") }
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("全局字体颜色") },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .background(
                                            editBase.globalTextColor?.let { it.toComposeColor() } ?: Color.Gray,
                                            CircleShape
                                        )
                                )
                                TextButton(onClick = { showGlobalTextColorPicker = true }) { Text("自定义") }
                                if (editBase.globalTextColor != null) {
                                    TextButton(onClick = { updateEditBase(editBase.copy(globalTextColor = null)) }) { Text("重置") }
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("用户气泡颜色") },
                        supportingContent = { Text("自定义用户消息气泡背景色") },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .background(
                                            editBase.userBubbleColor?.let { it.toComposeColor() } ?: MaterialTheme.colorScheme.secondaryContainer,
                                            CircleShape
                                        )
                                )
                                TextButton(onClick = { showUserBubbleColorPicker = true }) { Text("自定义") }
                                if (editBase.userBubbleColor != null) {
                                    TextButton(onClick = { updateEditBase(editBase.copy(userBubbleColor = null)) }) { Text("重置") }
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("用户消息字体颜色") },
                        supportingContent = { Text("只改变用户发送内容的文字颜色，不设置则跟随气泡底色") },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .background(
                                            editBase.userMessageFontColor?.let { it.toComposeColor() } ?: Color.Gray,
                                            CircleShape
                                        )
                                )
                                TextButton(onClick = { showUserMessageFontColorPicker = true }) { Text("自定义") }
                                if (editBase.userMessageFontColor != null) {
                                    TextButton(onClick = { updateEditBase(editBase.copy(userMessageFontColor = null)) }) { Text("重置") }
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("AI气泡颜色") },
                        supportingContent = { Text("自定义AI消息气泡背景色") },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .background(
                                            editBase.assistantBubbleColor?.let { it.toComposeColor() } ?: MaterialTheme.colorScheme.surfaceContainerHigh,
                                            CircleShape
                                        )
                                )
                                TextButton(onClick = { showAssistantBubbleColorPicker = true }) { Text("自定义") }
                                if (editBase.assistantBubbleColor != null) {
                                    TextButton(onClick = { updateEditBase(editBase.copy(assistantBubbleColor = null)) }) { Text("重置") }
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("思维链气泡颜色") },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .background(
                                            editBase.thinkingBubbleColor?.let { it.toComposeColor() } ?: Color.Gray,
                                            CircleShape
                                        )
                                )
                                TextButton(onClick = { showThinkingBubbleColorPicker = true }) { Text("自定义") }
                                if (editBase.thinkingBubbleColor != null) {
                                    TextButton(onClick = { updateEditBase(editBase.copy(thinkingBubbleColor = null)) }) { Text("重置") }
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("思考链字体颜色") },
                        supportingContent = { Text("♡ 图标、\"思考了X秒\"标题和思考内容的颜色") },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .background(
                                            editBase.thinkingTextColor?.let { it.toComposeColor() } ?: Color.Gray,
                                            CircleShape
                                        )
                                )
                                TextButton(onClick = { showThinkingTextColorPicker = true }) { Text("自定义") }
                                if (editBase.thinkingTextColor != null) {
                                    TextButton(onClick = { updateEditBase(editBase.copy(thinkingTextColor = null)) }) { Text("重置") }
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("气泡磨砂玻璃") },
                        supportingContent = { Text("iMessage 风格: 气泡/思考链/输入框模糊透出背景颜色 (需背景图或背景色, Android 12+)") },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.bubbleGlassEnabled,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(bubbleGlassEnabled = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("聊天背景色") },
                        supportingContent = { Text("有背景图时图片优先") },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .background(
                                            editBase.chatBackgroundColor?.let { it.toComposeColor() } ?: Color.Gray,
                                            CircleShape
                                        )
                                )
                                TextButton(onClick = { showChatBackgroundColorPicker = true }) { Text("自定义") }
                                if (editBase.chatBackgroundColor != null) {
                                    TextButton(onClick = { updateEditBase(editBase.copy(chatBackgroundColor = null)) }) { Text("重置") }
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("主色调（按钮/链接）") },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .background(
                                            editBase.primaryColor?.let { it.toComposeColor() } ?: MaterialTheme.colorScheme.primary,
                                            CircleShape
                                        )
                                )
                                TextButton(onClick = { showPrimaryColorPicker = true }) { Text("自定义") }
                                if (editBase.primaryColor != null) {
                                    TextButton(onClick = { updateEditBase(editBase.copy(primaryColor = null)) }) { Text("重置") }
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("输入框背景颜色") },
                        supportingContent = { Text("有背景图时图片优先") },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .background(
                                            editBase.inputFieldColor?.let { it.toComposeColor() } ?: MaterialTheme.colorScheme.surfaceContainerLowest,
                                            CircleShape
                                        )
                                )
                                TextButton(onClick = { showInputFieldColorPicker = true }) { Text("自定义") }
                                if (editBase.inputFieldColor != null) {
                                    TextButton(onClick = { updateEditBase(editBase.copy(inputFieldColor = null)) }) { Text("重置") }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
