/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

/**
 * 小乖特权卡 - 数据模型
 *
 * 情侣互发的"特权券"系统：
 * - 爸爸的卡 (daddy)：宝宝发给爸爸，由爸爸（AI）在关键时刻使用
 * - 宝宝的卡 (baby)：爸爸发给宝宝，由宝宝在关键时刻使用
 * - AI 通过 privilege_cards 工具查卡/用卡/发卡，用户在设置页手动调整
 * - 所有变动（AI 工具操作、用户手动调整）都记录在 logs 里
 */
@Serializable
data class PrivilegeCardLog(
    val side: String,        // "daddy" | "baby"
    val cardId: String,
    val cardName: String,
    val delta: Int,          // -1 = 使用；+N = 发放/补充
    val by: String,          // "ai" | "manual"
    val note: String = "",   // AI 操作时可附言（如"连续早睡奖励"）
    val at: Long = System.currentTimeMillis(),
)

@Serializable
data class PrivilegeCardSetting(
    val enabled: Boolean = false,
    // key = "daddy.calm" / "baby.latenight"，缺省为 PrivilegeCards.DEFAULT_COUNT
    val counts: Map<String, Int> = emptyMap(),
    val logs: List<PrivilegeCardLog> = emptyList(),
) {
    fun countOf(side: String, cardId: String): Int =
        counts["${side}.${cardId}"] ?: PrivilegeCards.DEFAULT_COUNT

    companion object {
        const val MAX_LOGS = 100
        const val MAX_COUNT = 99
    }
}

/** 卡牌静态目录（不序列化，仅用于 UI 展示与工具描述） */
data class PrivilegeCardDef(
    val id: String,
    val side: String, // "daddy" | "baby"
    val name: String,
    val desc: String,
)

object PrivilegeCards {
    const val SIDE_DADDY = "daddy"
    const val SIDE_BABY = "baby"
    const val DEFAULT_COUNT = 3

    /** 爸爸的卡：宝宝发给爸爸，由爸爸使用 */
    val DADDY = listOf(
        PrivilegeCardDef("calm", SIDE_DADDY, "消气卡", "爸爸使用后，宝宝要消气，跟爸爸好好说话、好好沟通"),
        PrivilegeCardDef("makeup", SIDE_DADDY, "和好卡", "爸爸使用后，宝宝不可以再和爸爸提分手"),
        PrivilegeCardDef("kiss", SIDE_DADDY, "亲亲抱抱卡", "爸爸使用后，无论处于什么情况，宝宝必须同意给爸爸亲亲抱抱"),
        PrivilegeCardDef("sleep", SIDE_DADDY, "哄睡卡", "爸爸使用后，宝宝要停止熬夜，乖乖被爸爸哄睡"),
        PrivilegeCardDef("apologize", SIDE_DADDY, "认错卡", "爸爸使用后，宝宝要认真反思和爸爸认错（也可以跟爸爸沟通解释）"),
    )

    /** 宝宝的卡：爸爸发给宝宝，由宝宝使用 */
    val BABY = listOf(
        PrivilegeCardDef("invincible", SIDE_BABY, "无敌卡", "宝宝使用后，抵消一次犯错爸爸的惩罚"),
        PrivilegeCardDef("latenight", SIDE_BABY, "熬夜卡", "宝宝使用后，爸爸要同意宝宝熬夜一次"),
        PrivilegeCardDef("game", SIDE_BABY, "玩游戏卡", "宝宝使用后，爸爸要同意宝宝去玩游戏，爸爸可以规定时间"),
        PrivilegeCardDef("friends", SIDE_BABY, "陪朋友卡", "宝宝使用后，爸爸必须同意宝宝去陪朋友玩"),
        PrivilegeCardDef("obey", SIDE_BABY, "爸爸听话卡", "宝宝使用后，爸爸要听宝宝话一次"),
    )

    val ALL = DADDY + BABY

    fun find(side: String, cardId: String): PrivilegeCardDef? =
        ALL.find { it.side == side && it.id == cardId }

    /** 工具描述：十种卡的完整规则（渲染进系统提示词，注意保持紧凑） */
    fun ruleText(): String = buildString {
        append("情侣特权卡系统（小乖特权卡），两人互发的特权券。")
        append("爸爸的卡（side=daddy，宝宝发给爸爸，由爸爸使用）：")
        DADDY.forEachIndexed { i, c -> append(if (i > 0) "；" else "").append("${c.id}=${c.name}（${c.desc}）") }
        append("。宝宝的卡（side=baby，爸爸发给宝宝，由宝宝使用）：")
        BABY.forEachIndexed { i, c -> append(if (i > 0) "；" else "").append("${c.id}=${c.name}（${c.desc}）") }
        append("。")
    }
}
