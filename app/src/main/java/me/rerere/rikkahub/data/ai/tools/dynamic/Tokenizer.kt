package me.rerere.rikkahub.data.ai.tools.dynamic

import java.text.Normalizer
import java.util.Locale

/**
 * 检索用的分词器。
 *
 * 三个必须照做的细节（见 docs/references/capability-tool-search-research.md 5.3）：
 * 1. CJK 按**字符二元组**切分，不需要词典；
 * 2. ASCII 词按 **camelCase** 边界拆分，避免 `getAccessPolicy` 里的驼峰漏检；
 * 3. 全角字符先做 NFKC 归一。
 */
internal object Tokenizer {

    private val wordRegex = Regex("[\\p{L}\\p{N}\\p{M}]+")

    /** camelCase 拆分：小写/数字后紧跟大写，或连续大写后接「大写+小写」 */
    private val camelRegex = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")

    /** 判定单个字符是否属于 CJK（含日文假名、韩文音节）。用字面字符，避免转义歧义 */
    private fun isCjk(ch: Char): Boolean =
        ch in '぀'..'ヿ' ||
            ch in '㐀'..'䶿' ||
            ch in '一'..'鿿' ||
            ch in '豈'..'﫿' ||
            ch in '가'..'힯'

    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val tokens = ArrayList<String>()
        for (word in wordRegex.findAll(normalized).map { it.value }) {
            for (part in camelRegex.split(word)) {
                if (part.isEmpty()) continue
                val lowered = part.lowercase(Locale.ROOT)
                // 不能用 Regex.split：Kotlin/Java 会丢弃捕获组分隔符，CJK 段会整个消失
                var i = 0
                while (i < lowered.length) {
                    var j = i + 1
                    if (isCjk(lowered[i])) {
                        while (j < lowered.length && isCjk(lowered[j])) j++
                        tokens.addAll(cjkTokens(lowered.substring(i, j)))
                    } else {
                        while (j < lowered.length && !isCjk(lowered[j])) j++
                        tokens.add(lowered.substring(i, j))
                    }
                    i = j
                }
            }
        }
        return tokens
    }

    /** 二元组；单字时回退为单字本身，否则长度 1 的查询会什么都搜不到 */
    private fun cjkTokens(run: String): List<String> =
        if (run.length < 2) {
            listOf(run)
        } else {
            (0 until run.length - 1).map { run.substring(it, it + 2) }
        }
}
