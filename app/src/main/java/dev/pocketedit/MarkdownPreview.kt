package dev.pocketedit

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class ListItem(val indent: Int, val bullet: String, val checked: Boolean?, val text: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

/** A small, forgiving Markdown parser — covers what notes usually use. */
object MarkdownParser {
    private val heading = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val rule = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val quote = Regex("""^\s*>\s?(.*)$""")
    private val listItem = Regex("""^(\s*)([-*+]|\d+[.)])\s+(\[[ xX]\]\s+)?(.*)$""")
    private val tableSep = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

    fun parse(text: String): List<MdBlock> {
        val lines = text.split('\n')
        val out = ArrayList<MdBlock>()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
            para.setLength(0)
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    flush()
                    val fence = trimmed.take(3)
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                        if (code.isNotEmpty()) code.append('\n')
                        code.append(lines[i]); i++
                    }
                    out += MdBlock.Code(code.toString())
                }
                line.isBlank() -> flush()
                heading.matches(line) -> {
                    flush()
                    val m = heading.find(line)!!
                    out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
                }
                rule.matches(line) -> { flush(); out += MdBlock.Rule }
                quote.matches(line) -> {
                    flush()
                    val q = StringBuilder()
                    while (i < lines.size && quote.matches(lines[i])) {
                        if (q.isNotEmpty()) q.append('\n')
                        q.append(quote.find(lines[i])!!.groupValues[1]); i++
                    }
                    out += MdBlock.Quote(q.toString())
                    continue
                }
                listItem.matches(line) -> {
                    flush()
                    val m = listItem.find(line)!!
                    val indent = m.groupValues[1].replace("\t", "    ").length / 2
                    val marker = m.groupValues[2]
                    val box = m.groupValues[3].trim()
                    val bullet = if (marker[0].isDigit()) marker.dropLast(1) + "." else "•"
                    val checked = if (box.isEmpty()) null else box.contains('x', ignoreCase = true)
                    out += MdBlock.ListItem(indent, bullet, checked, m.groupValues[4])
                }
                trimmed.startsWith("|") -> {
                    flush()
                    val rows = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                        if (!tableSep.matches(lines[i])) {
                            rows += lines[i].trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
                        }
                        i++
                    }
                    out += MdBlock.Table(rows)
                    continue
                }
                else -> {
                    if (para.isNotEmpty()) para.append(if (para.endsWith("  ")) "\n" else " ")
                    para.append(line.trim().let { if (line.endsWith("  ")) "$it  " else it })
                }
            }
            i++
        }
        flush()
        return out
    }

    private val inline = Regex(
        """`([^`]+)`""" +                                       // 1 code
            """|!\[([^\]]*)\]\(([^)\s]+)[^)]*\)""" +            // 2 alt, 3 image url
            """|\[([^\]]+)\]\(([^)\s]+)[^)]*\)""" +             // 4 text, 5 url
            """|<(https?://[^>\s]+)>""" +                        // 6 autolink
            """|(https?://[^\s<>()]+[^\s<>().,;:!?'"])""" +      // 7 bare url
            """|(\*\*|__)(?=\S)(.+?)(?<=\S)\8""" +              // 8 delim, 9 bold
            """|~~(?=\S)(.+?)(?<=\S)~~""" +                       // 10 strike
            """|(?<![\w*])\*(?=[^\s*])(.+?)(?<=[^\s*])\*(?![\w*])""" + // 11 italic
            """|(?<!\w)_(?=[^\s_])(.+?)(?<=[^\s_])_(?!\w)"""          // 12 italic
    )

    fun inline(text: String, c: SyntaxColors): AnnotatedString = buildAnnotatedString { appendInline(this, text, c) }

    private fun appendInline(b: AnnotatedString.Builder, text: String, c: SyntaxColors) {
        val linkStyles = TextLinkStyles(SpanStyle(color = c.link, textDecoration = TextDecoration.Underline))
        var last = 0
        for (m in inline.findAll(text)) {
            b.append(text.substring(last, m.range.first))
            val g = m.groups
            when {
                g[1] != null -> b.withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = c.code, background = c.codeBg)) {
                    append(g[1]!!.value)
                }
                g[3] != null -> b.withStyle(SpanStyle(color = c.quote, fontStyle = FontStyle.Italic)) {
                    append("🖼 " + (g[2]?.value?.ifBlank { null } ?: "image"))
                }
                g[5] != null -> b.withLink(LinkAnnotation.Url(g[5]!!.value, linkStyles)) { appendInline(this, g[4]!!.value, c) }
                g[6] != null -> b.withLink(LinkAnnotation.Url(g[6]!!.value, linkStyles)) { append(g[6]!!.value) }
                g[7] != null -> b.withLink(LinkAnnotation.Url(g[7]!!.value, linkStyles)) { append(g[7]!!.value) }
                g[9] != null -> b.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(this, g[9]!!.value, c) }
                g[10] != null -> b.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(this, g[10]!!.value, c) }
                g[11] != null -> b.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(this, g[11]!!.value, c) }
                g[12] != null -> b.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(this, g[12]!!.value, c) }
                else -> b.append(m.value)
            }
            last = m.range.last + 1
        }
        b.append(text.substring(last))
    }
}

@Composable
fun MarkdownPreview(text: String, fontSize: Int, colors: SyntaxColors, modifier: Modifier = Modifier) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    val body = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize.sp, lineHeight = (fontSize * 1.5).sp)

    SelectionContainer(modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            items(blocks) { block ->
                Box(Modifier.padding(vertical = 4.dp)) { BlockView(block, body, colors) }
            }
        }
    }
}

@Composable
private fun BlockView(block: MdBlock, body: TextStyle, c: SyntaxColors) {
    val base = body.fontSize.value
    when (block) {
        is MdBlock.Heading -> {
            val scale = when (block.level) { 1 -> 1.75f; 2 -> 1.5f; 3 -> 1.3f; 4 -> 1.15f; else -> 1.05f }
            Text(
                MarkdownParser.inline(block.text, c),
                style = body.copy(fontSize = (base * scale).sp, lineHeight = (base * scale * 1.3f).sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = if (block.level <= 2) 8.dp else 4.dp),
            )
        }
        is MdBlock.Paragraph -> Text(MarkdownParser.inline(block.text, c), style = body)
        is MdBlock.Code -> Surface(color = c.codeBg, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                block.code,
                style = body.copy(fontFamily = FontFamily.Monospace, fontSize = (base * 0.9f).sp),
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
            )
        }
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(c.quote))
            Text(
                MarkdownParser.inline(block.text, c),
                style = body.copy(fontStyle = FontStyle.Italic, color = c.quote),
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        is MdBlock.ListItem -> Row(Modifier.padding(start = (block.indent * 16).dp)) {
            val mark = when (block.checked) { true -> "☑"; false -> "☐"; null -> block.bullet }
            Text(mark, style = body.copy(color = c.marker), modifier = Modifier.width(28.dp))
            Text(
                MarkdownParser.inline(block.text, c),
                style = if (block.checked == true) body.copy(textDecoration = TextDecoration.LineThrough, color = c.quote) else body,
            )
        }
        is MdBlock.Table -> {
            val cols = block.rows.maxOfOrNull { it.size } ?: 0
            val widths = (0 until cols).map { col -> block.rows.maxOf { it.getOrNull(col)?.length ?: 0 } }
            val rendered = block.rows.mapIndexed { r, row ->
                (0 until cols).joinToString(" │ ") { (row.getOrNull(it) ?: "").padEnd(widths[it]) } +
                    if (r == 0 && block.rows.size > 1) "\n" + widths.joinToString("─┼─") { "─".repeat(it) } else ""
            }.joinToString("\n")
            Surface(color = c.codeBg, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    rendered,
                    style = body.copy(fontFamily = FontFamily.Monospace, fontSize = (base * 0.85f).sp),
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
                )
            }
        }
        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
    }
}
