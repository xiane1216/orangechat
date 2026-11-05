/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Award
import com.composables.icons.lucide.Crown
import com.composables.icons.lucide.Gamepad2
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.PenLine
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.Shield
import com.composables.icons.lucide.Smile
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.Users
import com.composables.icons.lucide.Wind
import org.koin.compose.koinInject
import me.rerere.rikkahub.data.model.PrivilegeCardDef
import me.rerere.rikkahub.data.model.PrivilegeCardLog
import me.rerere.rikkahub.data.model.PrivilegeCardSetting
import me.rerere.rikkahub.data.model.PrivilegeCards
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 低饱和点缀色：爸爸=雾蓝紫，宝宝=灰粉
private val DaddyTint = Color(0xFF6A74AD)
private val BabyTint = Color(0xFFC2859A)

private fun cardIcon(id: String): ImageVector = when (id) {
    "calm" -> Lucide.Wind
    "makeup" -> Lucide.Heart
    "kiss" -> Lucide.Smile
    "sleep" -> Lucide.Moon
    "apologize" -> Lucide.PenLine
    "invincible" -> Lucide.Shield
    "latenight" -> Lucide.Star
    "game" -> Lucide.Gamepad2
    "friends" -> Lucide.Users
    "obey" -> Lucide.Award
    else -> Lucide.Heart
}

private val LogTimeFormat = SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingPrivilegeCardPage(vm: SettingVM = koinInject()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val setting = settings.privilegeCardSetting
    var showResetDialog by remember { mutableStateOf(false) }

    fun adjust(side: String, card: PrivilegeCardDef, delta: Int) {
        val s = settings.privilegeCardSetting
        val current = s.countOf(side, card.id)
        val newCount = (current + delta).coerceIn(0, PrivilegeCardSetting.MAX_COUNT)
        if (newCount == current) return
        val log = PrivilegeCardLog(
            side = side, cardId = card.id, cardName = card.name,
            delta = newCount - current, by = "manual",
        )
        vm.updateSettings(
            settings.copy(
                privilegeCardSetting = s.copy(
                    counts = s.counts + ("${side}.${card.id}" to newCount),
                    logs = (listOf(log) + s.logs).take(PrivilegeCardSetting.MAX_LOGS),
                )
            )
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("小乖特权卡") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 启用开关
            item {
                CardGroup {
                    item(
                        headlineContent = { Text("启用小乖特权卡") },
                        supportingContent = { Text("开启后，AI 在聊天中可以查看、使用、发放特权卡（自动记账，手动调整也会记录）") },
                        trailingContent = {
                            Switch(
                                checked = setting.enabled,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings(
                                        settings.copy(privilegeCardSetting = setting.copy(enabled = enabled))
                                    )
                                }
                            )
                        }
                    )
                }
            }

            if (setting.enabled) {
                // 玩法说明
                item {
                    CardGroup {
                        item(
                            headlineContent = { Text("怎么玩") },
                            supportingContent = {
                                Text(
                                    "爸爸的卡由你发给他，他在关键时刻使用（消气、和好、亲亲抱抱、哄睡、认错）；" +
                                        "宝宝的卡由他发给你，你用他必须认（无敌、熬夜、玩游戏、陪朋友、爸爸听话）。" +
                                        "在聊天里说「我要用熬夜卡」或「给你发一张和好卡」，他会自动用工具记账。下面的加减号用于你手动补卡或扣卡。"
                                )
                            }
                        )
                    }
                }

                // 爸爸的卡
                item {
                    SectionHeader(title = "爸爸的卡", tint = DaddyTint, icon = Lucide.Crown, subtitle = "我发给他的 · 由爸爸使用")
                }
                item {
                    CardGroup {
                        PrivilegeCards.DADDY.forEach { card ->
                            item(
                                leadingContent = { CardIcon(card.id, DaddyTint) },
                                headlineContent = {
                                    CardHeadline(name = card.name, count = setting.countOf(card.side, card.id), DaddyTint)
                                },
                                supportingContent = { Text(card.desc) },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { adjust(card.side, card, -1) }) {
                                            Icon(Lucide.Minus, contentDescription = "减少一张${card.name}")
                                        }
                                        IconButton(onClick = { adjust(card.side, card, 1) }) {
                                            Icon(Lucide.Plus, contentDescription = "增加一张${card.name}")
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                // 宝宝的卡
                item {
                    SectionHeader(title = "宝宝的卡", tint = BabyTint, icon = Lucide.Heart, subtitle = "他发给我的 · 由宝宝使用")
                }
                item {
                    CardGroup {
                        PrivilegeCards.BABY.forEach { card ->
                            item(
                                leadingContent = { CardIcon(card.id, BabyTint) },
                                headlineContent = {
                                    CardHeadline(name = card.name, count = setting.countOf(card.side, card.id), BabyTint)
                                },
                                supportingContent = { Text(card.desc) },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { adjust(card.side, card, -1) }) {
                                            Icon(Lucide.Minus, contentDescription = "减少一张${card.name}")
                                        }
                                        IconButton(onClick = { adjust(card.side, card, 1) }) {
                                            Icon(Lucide.Plus, contentDescription = "增加一张${card.name}")
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                // 使用记录
                item {
                    CardGroup(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Lucide.History, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(6.dp))
                                Text("使用记录", fontWeight = FontWeight.Bold)
                            }
                        }
                    ) {
                        if (setting.logs.isEmpty()) {
                            item(
                                headlineContent = { Text("还没有记录") },
                                supportingContent = { Text("AI 或你第一次动卡之后，会记在这里") },
                            )
                        } else {
                            setting.logs.take(20).forEach { log ->
                                item(
                                    leadingContent = { CardIcon(log.cardId, if (log.side == PrivilegeCards.SIDE_DADDY) DaddyTint else BabyTint, small = true) },
                                    headlineContent = { LogHeadline(log) },
                                    supportingContent = {
                                        Text(
                                            buildString {
                                                append(if (log.by == "ai") "聊天中" else "手动调整")
                                                if (log.note.isNotBlank()) append(" · ${log.note}")
                                                append(" · ${LogTimeFormat.format(Date(log.at))}")
                                            }
                                        )
                                    }
                                )
                            }
                        }
                        item(
                            onClick = { showResetDialog = true },
                            leadingContent = {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.errorContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Lucide.RotateCcw, contentDescription = null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            },
                            headlineContent = { Text("重置所有卡牌") },
                            supportingContent = { Text("全部恢复为默认 3 张，并清空使用记录") },
                        )
                    }
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("重置所有卡牌？") },
            text = { Text("十种卡将全部恢复为默认 3 张，使用记录会被清空，此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateSettings(
                        settings.copy(privilegeCardSetting = setting.copy(counts = emptyMap(), logs = emptyList()))
                    )
                    showResetDialog = false
                }) { Text("重置") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String, tint: Color, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp), tint = tint)
        Spacer(Modifier.width(7.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = tint)
        Spacer(Modifier.width(8.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CardIcon(cardId: String, tint: Color, small: Boolean = false) {
    val size = if (small) 32.dp else 40.dp
    val iconSize = if (small) 16.dp else 20.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(if (small) 10.dp else 12.dp))
            .background(tint.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(cardIcon(cardId), contentDescription = null, modifier = Modifier.size(iconSize), tint = tint)
    }
}

@Composable
private fun CardHeadline(name: String, count: Int, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = if (count > 0) tint.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Text(
                text = if (count > 0) "× $count" else "没了",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (count > 0) tint else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun LogHeadline(log: PrivilegeCardLog) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = when {
                log.delta < 0 -> "使用了「${log.cardName}」"
                else -> "「${log.cardName}」+${log.delta}"
            },
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${log.delta}",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (log.delta < 0) BabyTint else DaddyTint
        )
    }
}
