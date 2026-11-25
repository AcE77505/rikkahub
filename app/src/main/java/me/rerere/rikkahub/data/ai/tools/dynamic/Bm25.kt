package me.rerere.rikkahub.data.ai.tools.dynamic

import kotlin.math.ln

/**
 * 朴素的 BM25 实现，供「工具检索」使用。
 *
 * 与 Open WebUI 的实现对齐（`search_deferred_tools`）：
 * - 标准 BM25 打分（k1 = 1.5, b = 0.75）；
 * - **IDF 归零兜底**：小语料里某词出现在半数以上文档时 IDF 为 0，工具集只有几十个时几乎必然发生；
 *   因此额外加上「查询词与文档词的集合交集数」作为原始重叠加分，否则会出现「明明有匹配却搜不到」。
 */
internal object Bm25 {

    private const val K1 = 1.5
    private const val B = 0.75

    class Index(private val documents: List<List<String>>) {
        private val docCount = documents.size
        private val avgLength: Double =
            if (docCount == 0) 1.0 else documents.sumOf { it.size }.toDouble() / docCount
        private val termFrequencies: List<Map<String, Int>> = documents.map { doc ->
            doc.groupingBy { it }.eachCount()
        }
        private val docFrequencies: Map<String, Int> = buildMap {
            documents.forEach { doc ->
                doc.toSet().forEach { term -> put(term, (get(term) ?: 0) + 1) }
            }
        }

        fun score(queryTokens: List<String>): DoubleArray {
            if (docCount == 0 || queryTokens.isEmpty()) return DoubleArray(docCount)
            val querySet = queryTokens.toSet()
            return DoubleArray(docCount) { index ->
                val tf = termFrequencies[index]
                val length = documents[index].size.coerceAtLeast(1)
                var bm25 = 0.0
                for (term in queryTokens) {
                    val freq = tf[term] ?: 0
                    if (freq == 0) continue
                    val df = docFrequencies[term] ?: 0
                    // 加 0.5 平滑：避免 df 等于语料规模时出现负值
                    val idf = ln((docCount - df + 0.5) / (df + 0.5) + 1.0)
                    val denominator = freq + K1 * (1 - B + B * length / avgLength)
                    bm25 += idf * freq * (K1 + 1) / denominator
                }
                // IDF 归零兜底：原始词重合，至少让每个匹配词可排序
                val overlap = querySet.count { it in (tf?.keys ?: emptySet()) }
                bm25.coerceAtLeast(0.0) + overlap
            }
        }
    }

    /**
     * 对 [entries] 做检索，返回前 [count] 条。
     *
     * 精确 id / 名字命中直接返回，不参与打分——这是 Open WebUI 的做法，模型常常直接给准确名字。
     */
    fun search(
        entries: List<CapabilityEntry>,
        query: String,
        count: Int = 5,
    ): List<CapabilityEntry> {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || entries.isEmpty()) return emptyList()

        // 精确命中：id、name 或别名
        entries.firstOrNull { entry ->
            entry.id.equals(trimmed, ignoreCase = true) ||
                entry.name.equals(trimmed, ignoreCase = true) ||
                entry.aliases.any { it.equals(trimmed, ignoreCase = true) }
        }?.let { return listOf(it) }

        val queryTokens = Tokenizer.tokenize(trimmed)
        if (queryTokens.isEmpty()) return emptyList()

        val documents = entries.map { Tokenizer.tokenize(it.searchText) }
        val scores = Index(documents).score(queryTokens)
        return entries.indices
            .filter { scores[it] > 0.0 }
            .sortedWith(compareByDescending<Int> { scores[it] }.thenBy { entries[it].id })
            .take(count.coerceIn(1, MAX_RESULTS))
            .map { entries[it] }
    }

    const val MAX_RESULTS = 20
    const val DEFAULT_RESULTS = 5
}
