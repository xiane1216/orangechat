/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Brain01
import me.rerere.hugeicons.stroke.DatabaseRestore
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.SecurityCheck
import me.rerere.rikkahub.data.model.ExternalMemory
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun ExternalMemoriesPage(vm: ExternalMemoriesVM = koinViewModel()) {
    val settings = vm.settings.collectAsStateWithLifecycle().value
    val opState = vm.opState.collectAsStateWithLifecycle().value
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<ExternalMemory?>(null) }
    var deleteTarget by remember { mutableStateOf<ExternalMemory?>(null) }
    var backfillTarget by remember { mutableStateOf<ExternalMemory?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("进阶记忆") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(HugeIcons.Add01, contentDescription = null)
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (settings.externalMemories.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Brain01,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "暂无进阶记忆",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(settings.externalMemories, key = { it.id }) { memory ->
                val assistantNames = settings.assistants
                    .filter { memory.id in it.externalMemoryIds }
                    .map { it.name.ifBlank { "未命名助手" } }
                ExternalMemoryItem(
                    memory = memory,
                    assistantNames = assistantNames,
                    op = opState?.takeIf { it.memoryId == memory.id },
                    onEdit = { editTarget = memory },
                    onDelete = { deleteTarget = memory },
                    onTestWrite = { vm.testWrite(memory) },
                    onBackfill = { backfillTarget = memory },
                )
            }
        }
    }

    if (showAddDialog) {
        ExternalMemoryEditDialog(
            memory = null,
            onDismiss = { showAddDialog = false },
            onConfirm = { memory ->
                vm.addExternalMemory(
                    name = memory.name,
                    supabaseUrl = memory.supabaseUrl,
                    supabaseKey = memory.supabaseKey,
                    tableName = memory.tableName,
                    summariesTableName = memory.summariesTableName,
                    autoSaveMessages = memory.autoSaveMessages,
                    autoSaveDiarySummary = memory.autoSaveDiarySummary,
                    recallCount = memory.recallCount,
                    embeddingModelId = memory.embeddingModelId,
                )
                showAddDialog = false
            }
        )
    }

    editTarget?.let { target ->
        ExternalMemoryEditDialog(
            memory = target,
            onDismiss = { editTarget = null },
            onConfirm = { updated ->
                vm.updateExternalMemory(updated.copy(id = target.id))
                editTarget = null
            }
        )
    }

    deleteTarget?.let { target ->
        RikkaConfirmDialog(
            show = true,
            title = "删除进阶记忆",
            confirmText = "删除",
            dismissText = "取消",
            onConfirm = {
                vm.deleteExternalMemory(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
            text = {
                Text("确定要删除 \"${target.name}\" 吗？关联该记忆库的助手将自动解除绑定。")
            }
        )
    }

    // 补传历史消息：选择日期范围
    backfillTarget?.let { target ->
        BackfillRangeDialog(
            onDismiss = { backfillTarget = null },
            onConfirm = { start, end ->
                backfillTarget = null
                vm.backfillHistory(target, start, end)
            }
        )
    }

    // 测试写入 / 补传 结果
    opState?.let { op ->
        if (!op.running && op.message != null) {
            AlertDialog(
                onDismissRequest = { vm.clearOp() },
                title = {
                    Text(
                        when (op.type) {
                            ExternalMemoryOpType.TestWrite -> "测试写入"
                            ExternalMemoryOpType.Backfill -> "补传历史消息"
                        }
                    )
                },
                text = { Text(op.message) },
                confirmButton = {
                    TextButton(onClick = { vm.clearOp() }) {
                        Text("确定")
                    }
                }
            )
        }
    }
}

@Composable
private fun BackfillRangeDialog(
    onDismiss: () -> Unit,
    onConfirm: (startDate: String, endDate: String) -> Unit,
) {
    val today = java.time.LocalDate.now()
    var start by rememberSaveable { mutableStateOf(today.minusDays(6).toString()) }
    var end by rememberSaveable { mutableStateOf(today.toString()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("补传历史消息") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "将所选日期范围内、关联此记忆库的助手的聊天记录补传到 Supabase，" +
                        "保留消息原始时间戳；远端已有的记录会自动跳过，可重复执行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it; error = null },
                    label = { Text("开始日期 (yyyy-MM-dd)") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = end,
                    onValueChange = { end = it; error = null },
                    label = { Text("结束日期 (yyyy-MM-dd)") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(
                        text = error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val s = start.trim()
                    val e = end.trim()
                    val datesValid = try {
                        java.time.LocalDate.parse(s)
                        java.time.LocalDate.parse(e)
                        true
                    } catch (ex: Exception) {
                        false
                    }
                    if (datesValid && s <= e) {
                        onConfirm(s, e)
                    } else {
                        error = "日期格式应为 yyyy-MM-dd，且开始日期不能晚于结束日期"
                    }
                }
            ) {
                Text("开始补传")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun ExternalMemoryItem(
    memory: ExternalMemory,
    assistantNames: List<String>,
    op: ExternalMemoryOpState?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTestWrite: () -> Unit,
    onBackfill: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = memory.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = memory.supabaseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = "表: ${memory.tableName} / 摘要: ${memory.summariesTableName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    text = if (assistantNames.isEmpty()) "未关联助手"
                    else "关联助手: ${assistantNames.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (memory.autoSaveMessages) {
                        FeatureChip("自动保存")
                    }
                    if (memory.autoSaveDiarySummary) {
                        FeatureChip("日记摘要")
                    }
                    FeatureChip("召回 ${memory.recallCount} 条")
                }

                if (op?.running == true) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = op.message ?: "处理中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(HugeIcons.MoreVertical, contentDescription = null)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("测试写入") },
                        leadingIcon = { Icon(HugeIcons.SecurityCheck, null) },
                        onClick = {
                            expanded = false
                            onTestWrite()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("补传历史消息") },
                        leadingIcon = { Icon(HugeIcons.DatabaseRestore, null) },
                        onClick = {
                            expanded = false
                            onBackfill()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        leadingIcon = { Icon(HugeIcons.Edit01, null) },
                        onClick = {
                            expanded = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(HugeIcons.Delete01, null) },
                        onClick = {
                            expanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun ExternalMemoryEditDialog(
    memory: ExternalMemory?,
    onDismiss: () -> Unit,
    onConfirm: (ExternalMemory) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(memory?.name ?: "") }
    var supabaseUrl by rememberSaveable { mutableStateOf(memory?.supabaseUrl ?: "") }
    var supabaseKey by rememberSaveable { mutableStateOf(memory?.supabaseKey ?: "") }
    var tableName by rememberSaveable { mutableStateOf(memory?.tableName ?: "chat_messages") }
    var summariesTableName by rememberSaveable { mutableStateOf(memory?.summariesTableName ?: "memory_summaries") }
    var autoSaveMessages by rememberSaveable { mutableStateOf(memory?.autoSaveMessages ?: true) }
    var autoSaveDiarySummary by rememberSaveable { mutableStateOf(memory?.autoSaveDiarySummary ?: false) }
    var recallCount by rememberSaveable { mutableStateOf((memory?.recallCount ?: 5).toString()) }
    var embeddingModelId by rememberSaveable { mutableStateOf(memory?.embeddingModelId) }

    val vm: ExternalMemoriesVM = koinViewModel()
    val settings = vm.settings.collectAsStateWithLifecycle().value

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (memory == null) "新建进阶记忆" else "编辑进阶记忆") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = supabaseUrl,
                        onValueChange = { supabaseUrl = it },
                        label = { Text("Supabase URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = supabaseKey,
                        onValueChange = { supabaseKey = it },
                        label = { Text("Supabase Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = tableName,
                        onValueChange = { tableName = it },
                        label = { Text("消息表名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = summariesTableName,
                        onValueChange = { summariesTableName = it },
                        label = { Text("摘要表名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("自动保存消息")
                        Switch(
                            checked = autoSaveMessages,
                            onCheckedChange = { autoSaveMessages = it }
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Supabase 端生成日记摘要")
                        Switch(
                            checked = autoSaveDiarySummary,
                            onCheckedChange = { autoSaveDiarySummary = it }
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = recallCount,
                        onValueChange = { recallCount = it.filter { c -> c.isDigit() } },
                        label = { Text("召回条数") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text(
                        text = "向量模型",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ModelSelector(
                        modelId = embeddingModelId,
                        providers = settings.providers,
                        type = ModelType.EMBEDDING,
                        allowClear = true,
                        onSelect = { model ->
                            embeddingModelId = model.id
                        }
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        ExternalMemory(
                            name = name,
                            supabaseUrl = supabaseUrl,
                            supabaseKey = supabaseKey,
                            tableName = tableName.ifBlank { "chat_messages" },
                            summariesTableName = summariesTableName.ifBlank { "memory_summaries" },
                            autoSaveMessages = autoSaveMessages,
                            autoSaveDiarySummary = autoSaveDiarySummary,
                            recallCount = recallCount.toIntOrNull() ?: 5,
                            embeddingModelId = embeddingModelId,
                        )
                    )
                },
                enabled = name.isNotBlank() && supabaseUrl.isNotBlank() && supabaseKey.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
