package app.struthio.nostr.core

/**
 * Just enough Markdown for NIP-23 articles. HTML isn't allowed in articles, so it's shown as text.
 * Blocks first, then the inline styling inside each.
 */
object Markdown {
    sealed interface Block {
        data class Heading(val level: Int, val text: List<Span>) : Block
        data class Paragraph(val text: List<Span>) : Block
        data class Quote(val text: List<Span>) : Block
        data class Item(val marker: String, val depth: Int, val text: List<Span>) : Block
        data class Code(val text: String) : Block
        data class Image(val url: String, val alt: String) : Block
        data object Rule : Block
    }

    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val strike: Boolean = false,
        /** A web address or a `nostr:` link. */
        val link: String? = null,
    )

    private val heading = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val bullet = Regex("""^(\s*)([-*+])\s+(.*)$""")
    private val numbered = Regex("""^(\s*)(\d{1,9})[.)]\s+(.*)$""")
    private val rule = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
    private val image = Regex("""^!\[([^\]]*)]\((\S+?)(?:\s+"[^"]*")?\)\s*$""")
    private val fence = Regex("""^\s*(```|~~~)""")

    fun parse(source: String): List<Block> {
        val blocks = ArrayList<Block>()
        val paragraph = ArrayList<String>()
        val lines = source.replace("\r\n", "\n").split('\n')

        fun flush() {
            if (paragraph.isNotEmpty()) {
                blocks += Block.Paragraph(inline(paragraph.joinToString(" ") { it.trim() }))
                paragraph.clear()
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                fence.containsMatchIn(line) -> {
                    flush()
                    val mark = fence.find(line)!!.groupValues[1]
                    val code = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(mark)) code += lines[i++]
                    blocks += Block.Code(code.joinToString("\n"))
                }
                trimmed.isEmpty() -> flush()
                heading.matches(trimmed) -> {
                    flush()
                    val m = heading.find(trimmed)!!
                    blocks += Block.Heading(m.groupValues[1].length, inline(m.groupValues[2]))
                }
                rule.matches(line) -> {
                    flush()
                    blocks += Block.Rule
                }
                image.matches(trimmed) -> {
                    flush()
                    val m = image.find(trimmed)!!
                    val url = m.groupValues[2]
                    if (url.startsWith("https://") || url.startsWith("http://")) blocks += Block.Image(url, m.groupValues[1])
                }
                trimmed.startsWith(">") -> {
                    flush()
                    val quoted = ArrayList<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        quoted += lines[i].trim().removePrefix(">").trim()
                        i++
                    }
                    blocks += Block.Quote(inline(quoted.joinToString(" ").trim()))
                    continue
                }
                bullet.matches(line) && !rule.matches(line) -> {
                    flush()
                    val m = bullet.find(line)!!
                    blocks += Block.Item("•", depthOf(m.groupValues[1]), inline(m.groupValues[3]))
                }
                numbered.matches(line) -> {
                    flush()
                    val m = numbered.find(line)!!
                    blocks += Block.Item(m.groupValues[2] + ".", depthOf(m.groupValues[1]), inline(m.groupValues[3]))
                }
                else -> paragraph += line
            }
            i++
        }
        flush()
        return blocks
    }

    private fun depthOf(indent: String) = (indent.replace("\t", "    ").length / 2).coerceAtMost(3)

    /** Inline styling: **bold**, *italic* or _italic_, `code`, ~~strike~~, [text](url), bare and nostr: links. */
    fun inline(text: String): List<Span> {
        val out = ArrayList<Span>()
        val buffer = StringBuilder()
        var bold = false
        var italic = false
        var strike = false

        fun emit(span: Span? = null) {
            if (buffer.isNotEmpty()) {
                out += Span(buffer.toString(), bold, italic, strike = strike)
                buffer.clear()
            }
            span?.let { out += it }
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            val rest = text.substring(i)
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_[]()#+-.!~>" -> {
                    buffer.append(text[i + 1])
                    i += 2
                    continue
                }
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        emit(Span(text.substring(i + 1, end), code = true))
                        i = end + 1
                        continue
                    }
                }
                rest.startsWith("**") || rest.startsWith("__") -> {
                    emit()
                    bold = !bold
                    i += 2
                    continue
                }
                rest.startsWith("~~") -> {
                    emit()
                    strike = !strike
                    i += 2
                    continue
                }
                (c == '*' || c == '_') && wordEdge(text, i) -> {
                    emit()
                    italic = !italic
                    i++
                    continue
                }
                c == '!' && rest.startsWith("![") -> {
                    // An image inside a paragraph shows as its description, linked.
                    val link = linkAt(text, i + 1)
                    if (link != null) {
                        emit(Span(link.first.ifBlank { "Image" }, bold, italic, link = link.second))
                        i = link.third
                        continue
                    }
                }
                c == '[' -> {
                    val link = linkAt(text, i)
                    if (link != null) {
                        emit(Span(link.first, bold, italic, link = link.second))
                        i = link.third
                        continue
                    }
                }
                rest.startsWith("https://") || rest.startsWith("http://") || rest.startsWith("nostr:") -> {
                    val end = text.indexOfFirst(i) { it.isWhitespace() || it in ")]>\"" }.let { if (it < 0) text.length else it }
                    val url = text.substring(i, end).trimEnd('.', ',', ';', ':', '!', '?')
                    emit(Span(url, bold, italic, link = url))
                    i += url.length
                    continue
                }
            }
            buffer.append(c)
            i++
        }
        emit()
        return out
    }

    private inline fun String.indexOfFirst(from: Int, test: (Char) -> Boolean): Int {
        for (j in from until length) if (test(this[j])) return j
        return -1
    }

    /** `*` or `_` marks emphasis at the edge of a word, not inside one like snake_case. */
    private fun wordEdge(text: String, at: Int): Boolean {
        val before = text.getOrNull(at - 1)
        val after = text.getOrNull(at + 1)
        val opens = after != null && !after.isWhitespace() && (before == null || !before.isLetterOrDigit())
        val closes = before != null && !before.isWhitespace() && (after == null || !after.isLetterOrDigit())
        return opens || closes
    }

    /**
     * `[text](url)` at [start]: the text, the url and where it ends. Also `[text][nostr:…]`, as the
     * NIP-23 example writes links to notes.
     */
    private fun linkAt(text: String, start: Int): Triple<String, String, Int>? {
        val labelEnd = text.indexOf(']', start + 1)
        if (labelEnd > start && text.startsWith("[", labelEnd + 1)) {
            val refEnd = text.indexOf(']', labelEnd + 2)
            val target = if (refEnd > 0) text.substring(labelEnd + 2, refEnd).trim() else ""
            if (target.startsWith("nostr:") || target.startsWith("https://")) {
                return Triple(text.substring(start + 1, labelEnd), target, refEnd + 1)
            }
        }
        val close = text.indexOf("](", start)
        if (close < 0) return null
        val end = text.indexOf(')', close + 2)
        if (end < 0) return null
        val label = text.substring(start + 1, close)
        if ('\n' in label) return null
        val url = text.substring(close + 2, end).trim().substringBefore(' ')
        if (!(url.startsWith("https://") || url.startsWith("http://") || url.startsWith("nostr:"))) return null
        return Triple(label, url, end + 1)
    }
}
