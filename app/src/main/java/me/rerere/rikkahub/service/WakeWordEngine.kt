/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * 唤醒词引擎封装 (sherpa-onnx KeywordSpotter, 纯离线).
 *
 * 模型: sherpa-onnx-kws-zipformer-wenetspeech-3.3M (int8, 约 5MB, 中文拼音 token).
 * assets/wakeword/ 下的模型文件 + 拼音表在首次使用时拷到 filesDir/wakeword/,
 * 用户昵称按"拼音表 -> 声母+带调韵母"切分成 token 序列生成 keywords.txt,
 * 因此设置里可以自定义任意汉字昵称, 无需重新训练模型.
 */
object WakeWordEngine {
    private const val TAG = "WakeWordEngine"
    private const val ASSET_DIR = "wakeword"

    private const val ENCODER_FILE = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    private const val DECODER_FILE = "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    private const val JOINER_FILE = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    private const val TOKENS_FILE = "tokens.txt"
    private const val PINYIN_MAP_FILE = "pinyin_map.txt"
    private const val KEYWORDS_FILE = "keywords.txt"

    private val MODEL_ASSETS = listOf(ENCODER_FILE, DECODER_FILE, JOINER_FILE, TOKENS_FILE, PINYIN_MAP_FILE)

    // 汉语拼音声母: 先匹配双字母 (zh/ch/sh), 再匹配单字母
    private val TWO_LETTER_INITIALS = listOf("zh", "ch", "sh")
    private val ONE_LETTER_INITIALS = "bpmfdtnlgkhjqxrzcsyw".toSet()

    // 轻声字 (的吗了呢等) 的无声调韵母大概率不在模型 token 里, 依次尝试 1-4 声变体
    private val TONE_MARKS: Map<Char, List<Char>> = mapOf(
        'a' to listOf('ā', 'á', 'ǎ', 'à'),
        'o' to listOf('ō', 'ó', 'ǒ', 'ò'),
        'e' to listOf('ē', 'é', 'ě', 'è'),
        'i' to listOf('ī', 'í', 'ǐ', 'ì'),
        'u' to listOf('ū', 'ú', 'ǔ', 'ù'),
    )

    fun getModelDir(context: Context): File = File(context.filesDir, "wakeword")

    fun getKeywordsFile(context: Context): File = File(getModelDir(context), KEYWORDS_FILE)

    /**
     * 把 assets/wakeword/ 的模型文件拷到 filesDir/wakeword/ (已存在且非空则跳过).
     * 注意: assets 默认被 aapt 压缩, 不能用 openFd 拿长度, 统一走流拷贝 + 临时文件改名防半截文件.
     * @return 是否全部就绪
     */
    fun ensureModelFiles(context: Context): Boolean = runCatching {
        val dir = getModelDir(context)
        dir.mkdirs()
        for (name in MODEL_ASSETS) {
            val target = File(dir, name)
            if (target.exists() && target.length() > 0) continue
            val tmp = File(dir, "$name.tmp")
            context.assets.open("$ASSET_DIR/$name").use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return@runCatching false
            }
        }
        true
    }.getOrDefault(false)

    /**
     * 校验昵称并生成 keywords.txt 中的一行 (如 "x iǎo j ú @小橘").
     * @throws IllegalArgumentException 昵称为空/含非汉字/拼音无法映射到模型 token 时
     */
    fun buildKeywordLine(context: Context, wakeWord: String): String {
        val text = wakeWord.trim()
        if (text.isEmpty()) throw IllegalArgumentException("唤醒词不能为空")
        if (text.length !in 2..6) throw IllegalArgumentException("唤醒词长度需在 2~6 个字之间")
        if (text.any { it !in '\u4E00'..'\u9FFF' }) throw IllegalArgumentException("唤醒词仅支持汉字")

        val tokens = loadTokens(context)
        val pinyinMap = loadPinyinMap(context)

        // 每个字 -> 一个音节 token 序列 (空格分隔)
        val syllableParts = text.map { ch ->
            val py = pinyinMap[ch] ?: throw IllegalArgumentException("『$ch』不在拼音表中，换个字试试")
            val initial = when {
                py.length >= 2 && py.take(2) in TWO_LETTER_INITIALS -> py.take(2)
                py.first() in ONE_LETTER_INITIALS -> py.take(1)
                else -> ""
            }
            val final = py.removePrefix(initial)
            if (final.isEmpty()) throw IllegalArgumentException("『$ch』($py) 无法解析，换个字试试")

            // 韵母候选: 原样优先; 若为无声调(轻声字), 依次尝试 4声/1声/2声/3声变体
            val candidates = mutableListOf(final)
            if (final.none { it in ALL_TONED_CHARS }) {
                // 标调位置: a > o > e > 末尾 i/u
                val markIdx = final.indexOfFirst { it in "aoei" }.takeIf { it >= 0 }
                    ?: final.length.minus(1).takeIf { final.lastOrNull() in "iu" }
                val toneVariants = markIdx?.let { TONE_MARKS[final[it]] }
                if (toneVariants != null) {
                    for (tone in intArrayOf(3, 0, 1, 2)) {
                        candidates.add(final.replaceRange(markIdx, markIdx + 1, toneVariants[tone].toString()))
                    }
                }
            }

            val finalToken = candidates.firstOrNull { it in tokens }
                ?: throw IllegalArgumentException("『$ch』($py) 的发音不在唤醒模型范围内，换个字试试")
            val parts = mutableListOf<String>()
            if (initial.isNotEmpty()) {
                if (initial !in tokens) throw IllegalArgumentException("『$ch』($py) 的声母不在模型范围内，换个字试试")
                parts.add(initial)
            }
            parts.add(finalToken)
            parts.joinToString(" ")
        }

        return "${syllableParts.joinToString(" ")} @$text"
    }

    /**
     * 校验 + 写 keywords.txt + 创建 KeywordSpotter 实例.
     * @throws IllegalArgumentException 昵称不可用时 (message 可直接展示给用户)
     * @throws IllegalStateException 模型文件未就绪/引擎创建失败时
     */
    fun create(context: Context, wakeWord: String): KeywordSpotter {
        val dir = getModelDir(context)
        if (!ensureModelFiles(context) || !File(dir, ENCODER_FILE).exists()) {
            throw IllegalStateException("唤醒模型文件未就绪")
        }
        val line = buildKeywordLine(context, wakeWord)
        val keywordsFile = getKeywordsFile(context)
        // keywords.txt 换内容即换唤醒词, 重建引擎时直接覆盖
        keywordsFile.writeText("$line\n")

        val spotter = KeywordSpotter(
            config = KeywordSpotterConfig(
                featConfig = FeatureConfig(),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(dir, ENCODER_FILE).absolutePath,
                        decoder = File(dir, DECODER_FILE).absolutePath,
                        joiner = File(dir, JOINER_FILE).absolutePath,
                    ),
                    tokens = File(dir, TOKENS_FILE).absolutePath,
                    numThreads = 1,
                    modelType = "zipformer2",
                ),
                keywordsFile = keywordsFile.absolutePath,
            )
        )
        Log.i(TAG, "KeywordSpotter created, keyword line: $line")
        return spotter
    }

    // 全部带调字符集合 (含 ü 系), 用于判断韵母是否无声调
    private val ALL_TONED_CHARS: Set<Char> = TONE_MARKS.values.flatten().toSet() + listOf('ǖ', 'ǘ', 'ǚ', 'ǜ')

    private fun loadTokens(context: Context): Set<String> {
        val file = File(getModelDir(context), TOKENS_FILE)
        val text = if (file.exists()) file.readText() else context.assets.open("$ASSET_DIR/$TOKENS_FILE").use { it.readBytes().decodeToString() }
        return text.lineSequence()
            .filter { it.isNotBlank() }
            .map { it.trim().split(Regex("\\s+"))[0] }
            .toSet()
    }

    private fun loadPinyinMap(context: Context): Map<Char, String> {
        val file = File(getModelDir(context), PINYIN_MAP_FILE)
        val text = if (file.exists()) file.readText() else context.assets.open("$ASSET_DIR/$PINYIN_MAP_FILE").use { it.readBytes().decodeToString() }
        val map = HashMap<Char, String>(25600)
        for (line in text.lineSequence()) {
            val parts = line.trim().split(' ')
            if (parts.size == 2 && parts[0].length == 1) map[parts[0][0]] = parts[1]
        }
        return map
    }
}
