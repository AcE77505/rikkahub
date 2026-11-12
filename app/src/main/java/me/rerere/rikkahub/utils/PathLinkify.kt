package me.rerere.rikkahub.utils

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.io.ByteArrayOutputStream

/** 路径链接在内存节点里的内部标记，渲染层据此把链接变成可点击的路径。 */
internal const val PATH_LINK_SCHEME = "rikkahub-path:"

/** 模型模仿内部链接写出的 `[文本](rikkahub-path:路径)`，渲染前要还原。 */
private val INTERNAL_PATH_LINK = Regex("""\[([^\]\n]*)]\(\s*${PATH_LINK_SCHEME}\s*([^)\n]*)\)""")

/** 正文里裸写的内部 scheme（`rikkahub-path:/x` 或 `rikkahub-path=/x`）。 */
private val BARE_PATH_SCHEME = Regex("""(?:${PATH_LINK_SCHEME}|rikkahub-path=)\s*(?=/)""")

/**
 * 绝对路径或明确的相对路径。
 *
 * 允许路径里出现空格（如 `HOYO-MiX - 泊船入梦.flac`），遇到中文标点或换行才结束；
 * 前面紧跟单词字符/URL 片段的不算路径，避免把 `example.com/a` 的路径部分当路径。
 */
internal val PATH_PATTERN =
    Regex("""(?<![\p{L}\p{N}_/:.@~-])(?:~|\.{1,2})?/(?=\S)[^<>"'`|*()\[\]{}，。、；：！？（）【】《》「」“”‘’\r\n]+""")

/** 路径末尾的标点不属于路径本身，链接时留在外面。 */
internal const val TRAILING_PUNCTUATION = ".,;:!?。，、；：！？"

/**
 * 模型有时会模仿客户端的内部链接，自己写出 `[文本](rikkahub-path:路径)` 或裸的
 * `rikkahub-path:/路径`。这些标记只属于渲染层，不能出现在用户可见的文本里：
 * 这里把它们还原成普通文本，交给展示或导出。
 */
internal fun stripInternalPathLinks(line: String): String {
    if ("rikkahub-path" !in line) return line
    val withoutLinks = INTERNAL_PATH_LINK.replace(line) { match ->
        val label = match.groupValues[1]
        // 文本里自带路径就保留原样；只有「查看」这类描述时用链接目标，别丢掉地址
        if (findPathRanges(label).isNotEmpty()) label else decodePath(match.groupValues[2].trim())
    }
    return BARE_PATH_SCHEME.replace(withoutLinks, "")
}

/** 纯文本里可打开的路径范围，供带点击的 Text 使用（不处理 Markdown 结构）。 */
internal fun findPathRanges(text: String): List<IntRange> =
    PATH_PATTERN.findAll(text).mapNotNull { match ->
        val raw = match.value
        val trailing = raw.takeLastWhile { it in TRAILING_PUNCTUATION }
        val candidate = raw.dropLast(trailing.length).substringBefore("  ").trimEnd()
        if (looksLikeOpenablePath(candidate)) {
            match.range.first until (match.range.first + candidate.length)
        } else {
            null
        }
    }.toList()

/** 这段文本是否可能是可以打开的文件/目录路径。 */
internal fun looksLikeOpenablePath(text: String): Boolean {
    val value = text.trim()
    if (value.length !in 2..4096) return false
    // 路径可以含空格，但不能跨行
    if ('\n' in value || '\r' in value) return false
    if ("://" in value) return false
    if (value.startsWith("//")) return false
    val relative = value.startsWith("./") || value.startsWith("../") || value.startsWith("~/")
    val absolute = value.startsWith("/")
    if (!relative && !absolute) return false
    // 「/ 说明文字」这种斜杠后接空白的不是路径
    val contentStart = when {
        value.startsWith("../") -> 3
        relative -> 2
        else -> 1
    }
    if (value.length <= contentStart || value[contentStart].isWhitespace()) return false
    return true
}

internal fun isPathLink(destination: String): Boolean = destination.startsWith(PATH_LINK_SCHEME)

internal fun decodePathLink(destination: String): String =
    decodePath(destination.removePrefix(PATH_LINK_SCHEME))

private const val HEX = "0123456789ABCDEF"
private const val SAFE_PATH_CHARS =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~/"

internal fun encodePath(path: String): String = buildString {
    for (byte in path.toByteArray(Charsets.UTF_8)) {
        val code = byte.toInt() and 0xFF
        val char = code.toChar()
        if (char in SAFE_PATH_CHARS) {
            append(char)
        } else {
            append('%').append(HEX[code shr 4]).append(HEX[code and 0x0F])
        }
    }
}

internal fun decodePath(value: String): String {
    val bytes = ByteArrayOutputStream(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%' && index + 2 < value.length) {
            val decoded = value.substring(index + 1, index + 3).toIntOrNull(16)
            if (decoded != null) {
                bytes.write(decoded)
                index += 3
                continue
            }
        }
        bytes.write(char.toString().toByteArray(Charsets.UTF_8))
        index++
    }
    return bytes.toString(Charsets.UTF_8.name())
}

/**
 * 去掉消息里的内部路径链接标记，用于复制、导出、Web 端这类用户可见的文本出口。
 *
 * 只在文本确实带 `rikkahub-path` 时才复制 parts，避免每次复制/导出都重建消息对象。
 */
internal fun UIMessage.withoutInternalPathLinks(): UIMessage {
    if (parts.none { it is UIMessagePart.Text && "rikkahub-path" in it.text }) return this
    return copy(
        parts = parts.map { part ->
            if (part is UIMessagePart.Text) part.copy(text = stripInternalPathLinks(part.text)) else part
        }
    )
}
