package me.rerere.rikkahub.utils

import java.io.ByteArrayOutputStream

/** linkifyPaths 生成的内部链接前缀，渲染层据此把链接变成可点击的路径。 */
internal const val PATH_LINK_SCHEME = "rikkahub-path:"

private val FENCE = Regex("""^\s*(`{3,}|~{3,})""")
private val INLINE_CODE = Regex("""`([^`\n]+)`""")
private val MARKDOWN_LINK = Regex("""\[[^\]\n]*]\([^)\n]*\)""")

/**
 * 绝对路径或明确的相对路径。
 *
 * 允许路径里出现空格（如 `HOYO-MiX - 泊船入梦.flac`），遇到中文标点或换行才结束；
 * 前面紧跟单词字符/URL 片段的不算路径，避免把 `example.com/a` 的路径部分当路径。
 */
private val PATH_PATTERN =
    Regex("""(?<![\p{L}\p{N}_/:.@~-])(?:~|\.{1,2})?/(?=\S)[^<>"'`|*()\[\]{}，。、；：！？（）【】《》「」“”‘’\r\n]+""")

/** 路径末尾的标点不属于路径本身，链接时留在外面。 */
private const val TRAILING_PUNCTUATION = ".,;:!?。，、；：！？"

private const val PLACEHOLDER_START = '\u0000'
private const val PLACEHOLDER_END = '\u0001'

/**
 * 把 Markdown 里的文件/目录路径换成内部链接，交给渲染层变成可点击文本。
 *
 * - 只处理行内代码与正文里的绝对/相对路径；围栏代码块原样保留
 * - 已有链接（`[文本](地址)`）不重复处理
 * - 路径本体经过百分号编码放进链接目标，避免括号、中文等破坏 Markdown 结构
 */
internal fun linkifyPaths(markdown: String): String {
    if ('/' !in markdown) return markdown
    val lines = markdown.split('\n')
    var fence: String? = null
    return lines.joinToString("\n") { line ->
        val trimmed = line.trimStart()
        val marker = FENCE.find(trimmed)?.groupValues?.get(1)
        if (marker != null) {
            fence = when {
                fence == null -> marker
                trimmed.startsWith(fence!!) -> null
                else -> fence
            }
            line
        } else if (fence != null) {
            line
        } else {
            linkifyLine(line)
        }
    }
}

private fun linkifyLine(line: String): String {
    if ('/' !in line) return line
    val held = mutableListOf<String>()
    fun hold(rendered: String): String = "$PLACEHOLDER_START${held.size}$PLACEHOLDER_END".also {
        held += rendered
    }

    // 1. 行内代码：`/path/to/file`
    var text = INLINE_CODE.replace(line) { match ->
        val code = match.groupValues[1].trim()
        if (looksLikeOpenablePath(code)) hold(renderPathLink(code)) else match.value
    }
    // 2. 保护已有链接，避免把链接目标里的路径再包一层
    text = MARKDOWN_LINK.replace(text) { hold(it.value) }
    // 3. 正文里的路径
    text = PATH_PATTERN.replace(text) { match ->
        val raw = match.value
        val trailing = raw.takeLastWhile { it in TRAILING_PUNCTUATION }
        // 两个连续空格通常是路径后面的说明文字
        val candidate = raw.dropLast(trailing.length).substringBefore("  ").trimEnd()
        if (looksLikeOpenablePath(candidate)) hold(renderPathLink(candidate)) + trailing else raw
    }
    // 4. 还原占位符
    return buildString {
        var index = 0
        while (index < text.length) {
            if (text[index] == PLACEHOLDER_START) {
                val end = text.indexOf(PLACEHOLDER_END, index + 1)
                val slot = if (end > index) text.substring(index + 1, end).toIntOrNull() else null
                if (slot != null && slot in held.indices) {
                    append(held[slot])
                    index = end + 1
                    continue
                }
            }
            append(text[index])
            index++
        }
    }
}

private fun renderPathLink(path: String): String {
    val label = path.replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_")
    return "[$label]($PATH_LINK_SCHEME${encodePath(path)})"
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
