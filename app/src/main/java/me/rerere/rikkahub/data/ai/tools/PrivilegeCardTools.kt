/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.Logging
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.PrivilegeCardLog
import me.rerere.rikkahub.data.model.PrivilegeCardSetting
import me.rerere.rikkahub.data.model.PrivilegeCards

private const val TAG = "PrivilegeCardTool"

/**
 * 小乖特权卡工具：AI 查卡 (list) / 用卡 (use) / 发卡 (grant)
 *
 * 通过 SettingsStore 在 execute 时读写最新数据（而非构建工具时的快照），
 * 避免 UI 手动调整与 AI 操作互相覆盖。
 */
fun createPrivilegeCardTool(
    context: Context,
    settingsStore: SettingsStore,
): Tool = Tool(
    name = "privilege_cards",
    description = buildString {
        append(PrivilegeCards.ruleText())
        append("操作：operation=list 查看双方所有卡的品种/数量/说明；operation=use 使用一张卡（side+card，数量减1，不足则失败）；operation=grant 发放或调整数量（side+card+count，正数加卡、负数减卡）。")
        append("规范：使用或发放卡牌必须调用本工具记账，不可口头承诺不记账；宝宝要求用卡时先核对数量；grant 用于奖励、纪念日或宝宝协商请求，不要滥发。")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("operation") {
                    put("type", "string")
                    putJsonArray("enum") { add("list"); add("use"); add("grant") }
                    put("description", "list=查看全部卡牌；use=使用一张卡；grant=发放/调整卡牌数量")
                }
                putJsonObject("side") {
                    put("type", "string")
                    putJsonArray("enum") { add("daddy"); add("baby") }
                    put("description", "卡牌归属方：daddy=爸爸的卡（爸爸使用），baby=宝宝的卡（宝宝使用）。use/grant 必填")
                }
                putJsonObject("card") {
                    put("type", "string")
                    put("description", "卡牌 id。use/grant 必填")
                }
                putJsonObject("count") {
                    put("type", "integer")
                    put("description", "grant 时的数量变化，正数加卡、负数减卡，范围 -99~99")
                }
                putJsonObject("note") {
                    put("type", "string")
                    put("description", "可选备注，如使用原因或发卡理由")
                }
            },
            required = listOf("operation")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val operation = params["operation"]?.jsonPrimitive?.contentOrNull
        val side = params["side"]?.jsonPrimitive?.contentOrNull
        val cardId = params["card"]?.jsonPrimitive?.contentOrNull
        val count = params["count"]?.jsonPrimitive?.intOrNull
        val note = params["note"]?.jsonPrimitive?.contentOrNull ?: ""

        try {
            when (operation) {
                "list" -> {
                    val setting = settingsStore.settingsFlow.value.privilegeCardSetting
                    val result = buildJsonObject {
                        put("success", true)
                        putJsonObject("daddy_cards") {
                            PrivilegeCards.DADDY.forEach {
                                put(it.id, buildJsonObject {
                                    put("name", it.name)
                                    put("count", setting.countOf(it.side, it.id))
                                    put("desc", it.desc)
                                })
                            }
                        }
                        putJsonObject("baby_cards") {
                            PrivilegeCards.BABY.forEach {
                                put(it.id, buildJsonObject {
                                    put("name", it.name)
                                    put("count", setting.countOf(it.side, it.id))
                                    put("desc", it.desc)
                                })
                            }
                        }
                    }
                    listOf(UIMessagePart.Text(result.toString()))
                }

                "use" -> {
                    val card = PrivilegeCards.find(side ?: "", cardId ?: "")
                    if (card == null) {
                        listOf(UIMessagePart.Text(buildJsonObject {
                            put("success", false)
                            put("error", "卡牌不存在：side=$side, card=$cardId")
                        }.toString()))
                    } else {
                        val setting = settingsStore.settingsFlow.value.privilegeCardSetting
                        val current = setting.countOf(card.side, card.id)
                        if (current <= 0) {
                            listOf(UIMessagePart.Text(buildJsonObject {
                                put("success", false)
                                put("error", "「${card.name}」已经用完了，无法使用（可考虑 grant 补卡）")
                            }.toString()))
                        } else {
                            val newLog = PrivilegeCardLog(
                                side = card.side, cardId = card.id, cardName = card.name,
                                delta = -1, by = "ai", note = note,
                            )
                            val newSetting = setting.copy(
                                counts = setting.counts + ("${card.side}.${card.id}" to current - 1),
                                logs = (listOf(newLog) + setting.logs).take(PrivilegeCardSetting.MAX_LOGS),
                            )
                            settingsStore.update { it.copy(privilegeCardSetting = newSetting) }
                            Logging.log(TAG, "AI used card ${card.side}/${card.id}")
                            listOf(UIMessagePart.Text(buildJsonObject {
                                put("success", true)
                                put("action", "use")
                                put("card", card.name)
                                put("remaining", current - 1)
                                if (note.isNotBlank()) put("note", note)
                                put("message", "已使用一张「${card.name}」，剩余 ${current - 1} 张。请依照卡牌规则行动。")
                            }.toString()))
                        }
                    }
                }

                "grant" -> {
                    val card = PrivilegeCards.find(side ?: "", cardId ?: "")
                    if (card == null) {
                        listOf(UIMessagePart.Text(buildJsonObject {
                            put("success", false)
                            put("error", "卡牌不存在：side=$side, card=$cardId")
                        }.toString()))
                    } else if (count == null || count == 0 || count < -99 || count > 99) {
                        listOf(UIMessagePart.Text(buildJsonObject {
                            put("success", false)
                            put("error", "count 必须是 -99~99 的非零整数（正数加卡、负数减卡）")
                        }.toString()))
                    } else {
                        val setting = settingsStore.settingsFlow.value.privilegeCardSetting
                        val current = setting.countOf(card.side, card.id)
                        val newCount = (current + count).coerceIn(0, PrivilegeCardSetting.MAX_COUNT)
                        val newLog = PrivilegeCardLog(
                            side = card.side, cardId = card.id, cardName = card.name,
                            delta = newCount - current, by = "ai", note = note,
                        )
                        val newSetting = setting.copy(
                            counts = setting.counts + ("${card.side}.${card.id}" to newCount),
                            logs = (listOf(newLog) + setting.logs).take(PrivilegeCardSetting.MAX_LOGS),
                        )
                        settingsStore.update { it.copy(privilegeCardSetting = newSetting) }
                        Logging.log(TAG, "AI granted ${newCount - current} x ${card.side}/${card.id}")
                        listOf(UIMessagePart.Text(buildJsonObject {
                            put("success", true)
                            put("action", "grant")
                            put("card", card.name)
                            put("remaining", newCount)
                            if (note.isNotBlank()) put("note", note)
                            put("message", "「${card.name}」${if (newCount - current >= 0) "+" else ""}${newCount - current} 张，现有 $newCount 张")
                        }.toString()))
                    }
                }

                else -> listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", false)
                    put("error", "未知 operation: $operation（支持 list/use/grant）")
                }.toString()))
            }
        } catch (e: Exception) {
            Logging.log(TAG, "Unexpected error: ${e.message}\n${e.stackTraceToString()}")
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", false)
                put("error", e.message ?: "Unknown error")
            }.toString()))
        }
    }
)
