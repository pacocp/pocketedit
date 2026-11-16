package dev.pocketedit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration

data class SyntaxColors(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val heading: Color,
    val link: Color,
    val code: Color,
    val codeBg: Color,
    val quote: Color,
    val marker: Color,
    val matchBg: Color,
    val currentMatchBg: Color,
) {
    companion object {
        val Light = SyntaxColors(
            keyword = Color(0xFFCF222E), string = Color(0xFF0A3069), comment = Color(0xFF6E7781),
            number = Color(0xFF0550AE), heading = Color(0xFF0550AE), link = Color(0xFF0969DA),
            code = Color(0xFF953800), codeBg = Color(0x14000000), quote = Color(0xFF57606A),
            marker = Color(0xFF8250DF), matchBg = Color(0x66FFD33D), currentMatchBg = Color(0xFFFFB000),
        )
        val Dark = SyntaxColors(
            keyword = Color(0xFFFF7B72), string = Color(0xFFA5D6FF), comment = Color(0xFF8B949E),
            number = Color(0xFF79C0FF), heading = Color(0xFF79C0FF), link = Color(0xFF58A6FF),
            code = Color(0xFFFFA657), codeBg = Color(0x22FFFFFF), quote = Color(0xFF9DA7B3),
            marker = Color(0xFFD2A8FF), matchBg = Color(0x66BB8009), currentMatchBg = Color(0xFFB88400),
        )
    }
}

typealias Spans = List<AnnotatedString.Range<SpanStyle>>

object Highlighter {
    /** Above this size we skip syntax colouring to keep typing smooth. */
    const val MAX_HIGHLIGHT_CHARS = 150_000

    fun spans(text: String, lang: Lang, c: SyntaxColors): Spans = when {
        text.length > MAX_HIGHLIGHT_CHARS -> emptyList()
        lang == Lang.MARKDOWN -> markdown(text, c)
        lang.isCode -> code(text, lang, c)
        else -> emptyList()
    }

    // ---------- code ----------

    private val regexCache = HashMap<Lang, Regex>()

    private fun codeRegex(lang: Lang): Regex = regexCache.getOrPut(lang) {
        val never = """[^\s\S]""" // matches nothing; keeps group numbering stable
        val block = when (lang.comments) {
            CommentStyle.C -> """/\*[\s\S]*?(?:\*/|\z)"""
            CommentStyle.MARKUP -> """<!--[\s\S]*?(?:-->|\z)"""
            else -> never
        }
        val line = when (lang.comments) {
            CommentStyle.C -> """//[^\n]*"""
            CommentStyle.HASH -> """#[^\n]*"""
            CommentStyle.SQL -> """--[^\n]*"""
            else -> never
        }
        val str = buildString {
            if (lang == Lang.PYTHON) append("\"\"\"[\\s\\S]*?(?:\"\"\"|\\z)|'''[\\s\\S]*?(?:'''|\\z)|")
            append(""""(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'""")
            if (lang != Lang.MARKUP) append("""|`(?:\\.|[^`\\\n])*`""")
        }
        val num = if (lang == Lang.MARKUP) never
        else """\b(?:0[xX][0-9a-fA-F_]+|\d[\d_]*(?:\.\d+)?(?:[eE][+-]?\d+)?)[fFLlUu]?\b"""
        val word = if (lang == Lang.MARKUP) """</?[A-Za-z][\w:.-]*|[A-Za-z_:][\w:.-]*(?==)"""
        else """@?[A-Za-z_$][\w$]*"""
        Regex("($block)|($line)|($str)|($num)|($word)")
    }

    private fun code(text: String, lang: Lang, c: SyntaxColors): Spans {
        val out = ArrayList<AnnotatedString.Range<SpanStyle>>()
        val comment = SpanStyle(color = c.comment, fontStyle = FontStyle.Italic)
        val string = SpanStyle(color = c.string)
        val number = SpanStyle(color = c.number)
        val keyword = SpanStyle(color = c.keyword, fontWeight = FontWeight.SemiBold)
        val annotation = SpanStyle(color = c.marker)
        val key = SpanStyle(color = c.keyword)

        for (m in codeRegex(lang).findAll(text)) {
            val r = m.range
            val style = when {
                m.groups[1] != null || m.groups[2] != null -> comment
                m.groups[3] != null -> if (lang == Lang.JSON && followedByColon(text, r.last + 1)) key else string
                m.groups[4] != null -> number
                else -> {
                    val w = m.value
                    when {
                        lang == Lang.MARKUP -> keyword
                        w.startsWith("@") -> annotation
                        lang == Lang.CONFIG && atLineStart(text, r.first) && followedBy(text, r.last + 1, ":=") -> key
                        w in lang.keywords -> keyword
                        else -> null
                    }
                }
            }
            if (style != null) out += AnnotatedString.Range(style, r.first, r.last + 1)
        }
        return out
    }

    private fun followedByColon(text: String, from: Int) = followedBy(text, from, ":")

    private fun followedBy(text: String, from: Int, chars: String): Boolean {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return i < text.length && text[i] in chars
    }

    private fun atLineStart(text: String, pos: Int): Boolean {
        var i = pos - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '\t' || text[i] == '-')) i--
        return i < 0 || text[i] == '\n'
    }

    // ---------- markdown ----------

    private val ML = setOf(RegexOption.MULTILINE)
    private val mdHeading = Regex("""^#{1,6}\s.*$""", ML)
    private val mdQuote = Regex("""^\s*>.*$""", ML)
    private val mdList = Regex("""^\s*(?:[-*+]|\d+[.)])\s(?:\[[ xX]\]\s)?""", ML)
    private val mdRule = Regex("""^\s*(?:-{3,}|\*{3,}|_{3,})\s*$""", ML)
    private val mdBold = Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1""")
    private val mdItalic = Regex("""(?<![*\w])\*(?=[^*\s])(.+?)(?<=[^*\s])\*(?![*\w])|(?<![_\w])_(?=[^_\s])(.+?)(?<=[^_\s])_(?![_\w])""")
    private val mdStrike = Regex("""~~(?=\S)(.+?)(?<=\S)~~""")
    private val mdLink = Regex("""!?\[[^\]\n]*\]\([^)\n]*\)|https?://[^\s)>\]]+""")
    private val mdInlineCode = Regex("""`[^`\n]+`""")
    private val mdFence = Regex("""^\s*```[^\n]*\n[\s\S]*?(?:^\s*```\s*$|\z)""", ML)

    private fun markdown(text: String, c: SyntaxColors): Spans {
        val out = ArrayList<AnnotatedString.Range<SpanStyle>>()
        fun add(re: Regex, style: SpanStyle) {
            for (m in re.findAll(text)) if (!m.range.isEmpty()) out += AnnotatedString.Range(style, m.range.first, m.range.last + 1)
        }
        add(mdHeading, SpanStyle(color = c.heading, fontWeight = FontWeight.Bold))
        add(mdQuote, SpanStyle(color = c.quote, fontStyle = FontStyle.Italic))
        add(mdList, SpanStyle(color = c.marker, fontWeight = FontWeight.Bold))
        add(mdRule, SpanStyle(color = c.marker))
        add(mdBold, SpanStyle(fontWeight = FontWeight.Bold))
        add(mdItalic, SpanStyle(fontStyle = FontStyle.Italic))
        add(mdStrike, SpanStyle(textDecoration = TextDecoration.LineThrough))
        add(mdLink, SpanStyle(color = c.link))
        val codeStyle = SpanStyle(color = c.code, background = c.codeBg, fontFamily = FontFamily.Monospace)
        add(mdInlineCode, codeStyle)
        add(mdFence, codeStyle) // last, so it wins over anything inside code blocks
        return out
    }
}

/**
 * Colours the editor text without changing it (identity offset mapping).
 * Caches the last result so cursor moves don't re-run the regexes.
 */
class EditorTransformation(
    private val lang: Lang,
    private val colors: SyntaxColors,
    private val highlight: Boolean,
    private val matches: List<IntRange>,
    private val currentMatch: Int,
) : VisualTransformation {

    private var cachedText: String? = null
    private var cachedSyntax: Spans = emptyList()

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (cachedText !== raw && cachedText != raw) {
            cachedSyntax = if (highlight) Highlighter.spans(raw, lang, colors) else emptyList()
            cachedText = raw
        }
        val spans = if (matches.isEmpty()) cachedSyntax else {
            val list = ArrayList<AnnotatedString.Range<SpanStyle>>(cachedSyntax.size + matches.size)
            list.addAll(cachedSyntax)
            matches.forEachIndexed { i, r ->
                if (r.last < raw.length) {
                    val bg = if (i == currentMatch) colors.currentMatchBg else colors.matchBg
                    list += AnnotatedString.Range(SpanStyle(background = bg), r.first, r.last + 1)
                }
            }
            list
        }
        return TransformedText(AnnotatedString(raw, spans), OffsetMapping.Identity)
    }
}
