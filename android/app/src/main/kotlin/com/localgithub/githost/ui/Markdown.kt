package com.localgithub.githost.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.localgithub.githost.ui.theme.GitHub

/*
 * A small Markdown renderer for README files.
 *
 * It covers what READMEs actually use (headings, paragraphs, nested and task
 * lists, fenced code, tables, block quotes, rules, and inline bold, italic,
 * code and links) and degrades to plain text for the rest. Raw HTML lines,
 * common at the top of GitHub READMEs, are dropped. Links and images show
 * their text only: the app has no browser or network access to follow them.
 */

/** One block-level element of a Markdown document. */
sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
    /** A list item; [marker] is "•", "1." or a checkbox, [depth] its nesting level. */
    data class ListItem(val marker: String, val text: String, val depth: Int) : MarkdownBlock()
    /** A fenced code block; [language] is the fence's info string, possibly empty. */
    data class Code(val language: String, val text: String) : MarkdownBlock()
    data class Quote(val text: String) : MarkdownBlock()
    /** A table; the first row is the header. */
    data class Table(val rows: List<List<String>>) : MarkdownBlock()
    object Rule : MarkdownBlock()
}

private val NUMBERED_RE = Regex("""(\d+)[.)]\s+(.*)""")
private val BULLET_RE = Regex("""[-*+]\s+(.*)""")
private val TASK_RE = Regex("""\[([ xX])]\s+(.*)""")
private val TABLE_SEPARATOR_RE = Regex("""\|?(\s*:?-+:?\s*\|)*\s*:?-+:?\s*\|?""")
private val HTML_LINE_RE = Regex("""</?[a-zA-Z][^>]*>.*""")

/** Splits Markdown source into blocks. Never fails; unknown syntax becomes text. */
fun parseMarkdown(src: String): List<MarkdownBlock> {
    val blocks = ArrayList<MarkdownBlock>()
    val para = StringBuilder()
    val quote = StringBuilder()
    val table = ArrayList<List<String>>()

    fun flush() {
        if (para.isNotBlank()) blocks += MarkdownBlock.Paragraph(para.toString().trim())
        para.clear()
        if (quote.isNotBlank()) blocks += MarkdownBlock.Quote(quote.toString().trim())
        quote.clear()
        if (table.isNotEmpty()) blocks += MarkdownBlock.Table(table.toList())
        table.clear()
    }

    var fence: String? = null          // the opening fence while inside a code block
    var language = ""
    val code = StringBuilder()

    for (raw in src.lines()) {
        val line = raw.trimEnd()
        val t = line.trim()

        // Fenced code: everything up to the matching fence is verbatim.
        if (fence != null) {
            if (t.startsWith(fence)) {
                blocks += MarkdownBlock.Code(language, code.toString().trimEnd('\n'))
                code.clear()
                fence = null
            } else {
                code.appendLine(raw)
            }
            continue
        }
        if (t.startsWith("```") || t.startsWith("~~~")) {
            flush()
            fence = t.takeWhile { it == t[0] }
            language = t.drop(fence.length).trim().substringBefore(' ')
            continue
        }

        val indent = line.takeWhile { it == ' ' || it == '\t' }
            .fold(0) { n, c -> n + if (c == '\t') 4 else 1 }
        when {
            t.isEmpty() -> flush()
            HTML_LINE_RE.matches(t) -> flush()
            t.startsWith("#") && t.trimStart('#').let { it.isEmpty() || it.startsWith(" ") } -> {
                flush()
                val level = t.takeWhile { it == '#' }.length.coerceIn(1, 6)
                blocks += MarkdownBlock.Heading(level, t.drop(level).trim().trimEnd('#').trim())
            }
            t == "---" || t == "***" || t == "___" -> { flush(); blocks += MarkdownBlock.Rule }
            t.startsWith(">") -> {
                if (para.isNotEmpty() || table.isNotEmpty()) flush()
                if (quote.isNotEmpty()) quote.append(' ')
                quote.append(t.removePrefix(">").trim())
            }
            t.startsWith("|") -> {
                if (para.isNotEmpty() || quote.isNotEmpty()) flush()
                if (!TABLE_SEPARATOR_RE.matches(t)) table += splitTableRow(t)
            }
            BULLET_RE.matches(t) -> {
                flush()
                val body = BULLET_RE.matchEntire(t)!!.groupValues[1]
                val task = TASK_RE.matchEntire(body)
                blocks += if (task != null) {
                    val done = task.groupValues[1] != " "
                    MarkdownBlock.ListItem(if (done) "☑" else "☐", task.groupValues[2], indent / 2)
                } else {
                    MarkdownBlock.ListItem("•", body, indent / 2)
                }
            }
            NUMBERED_RE.matches(t) -> {
                flush()
                val m = NUMBERED_RE.matchEntire(t)!!
                blocks += MarkdownBlock.ListItem("${m.groupValues[1]}.", m.groupValues[2], indent / 3)
            }
            // An indented line right after a list item continues that item.
            indent > 0 && para.isEmpty() && blocks.lastOrNull() is MarkdownBlock.ListItem -> {
                val item = blocks.removeAt(blocks.lastIndex) as MarkdownBlock.ListItem
                blocks += item.copy(text = item.text + " " + t)
            }
            else -> {
                if (quote.isNotEmpty() || table.isNotEmpty()) flush()
                if (para.isNotEmpty()) para.append(' ')
                para.append(t)
            }
        }
    }
    if (fence != null && code.isNotBlank()) blocks += MarkdownBlock.Code(language, code.toString().trimEnd('\n'))
    flush()
    return blocks
}

private fun splitTableRow(row: String): List<String> =
    row.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

// --- inline formatting -------------------------------------------------------

private val IMAGE_RE = Regex("""!\[([^]]*)]\([^)]+\)""")
private val LINK_RE = Regex("""\[([^]]+)]\(([^)]+)\)""")
private val BOLD_RE = Regex("""(\*\*|__)(.+?)\1""")
// Underscores only delimit italics at word boundaries, so snake_case and
// file_names render as written.
private val ITALIC_RE = Regex("""\*(?!\s)(.+?)(?<!\s)\*|(?<![A-Za-z0-9])_(?!\s)(.+?)(?<!\s)_(?![A-Za-z0-9])""")
private val CODE_RE = Regex("`([^`]+)`")

private val InlineCodeStyle = SpanStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 0.9.em,
    background = GitHub.InlineCode,
    color = GitHub.Fg,
)

/** Renders bold, italic, inline code and link text; drops image and link targets. */
fun inlineMarkdown(text: String): AnnotatedString {
    var s = IMAGE_RE.replace(text) { it.groupValues[1] }
    // Mark link text with control characters so it survives the scan below.
    s = LINK_RE.replace(s) { "\u0001${it.groupValues[1]}\u0002" }
    return buildAnnotatedString {
        var i = 0
        while (i < s.length) {
            val rest = s.substring(i)
            val code = CODE_RE.find(rest)?.takeIf { it.range.first == 0 }
            val bold = BOLD_RE.find(rest)?.takeIf { it.range.first == 0 }
            val italic = if (i > 0 && s[i] == '_' && s[i - 1].isLetterOrDigit()) null
                         else ITALIC_RE.find(rest)?.takeIf { it.range.first == 0 }
            when {
                code != null -> {
                    // Thin spaces pad the background so it does not hug the glyphs.
                    withStyle(InlineCodeStyle) { append(" ${code.groupValues[1]} ") }
                    i += code.value.length
                }
                bold != null -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = GitHub.Fg)) {
                        append(inlineMarkdown(bold.groupValues[2]))
                    }
                    i += bold.value.length
                }
                italic != null -> {
                    val body = italic.groupValues[1].ifEmpty { italic.groupValues[2] }
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(body) }
                    i += italic.value.length
                }
                s[i] == '\u0001' -> {
                    val end = s.indexOf('\u0002', i).let { if (it < 0) s.length else it }
                    withStyle(SpanStyle(color = GitHub.Link)) { append(s.substring(i + 1, end)) }
                    i = (end + 1).coerceAtMost(s.length)
                }
                else -> { append(s[i]); i++ }
            }
        }
    }
}

// --- rendering ---------------------------------------------------------------

/** Renders a Markdown document as a column of blocks. */
@Composable
fun MarkdownView(text: String) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEachIndexed { index, b ->
            when (b) {
                is MarkdownBlock.Heading -> MarkdownHeading(b, first = index == 0)
                is MarkdownBlock.Paragraph -> Text(inlineMarkdown(b.text),
                    style = MaterialTheme.typography.bodyMedium, color = GitHub.FgSecondary,
                    lineHeight = 22.sp)
                is MarkdownBlock.ListItem -> MarkdownListItem(b)
                is MarkdownBlock.Code -> MarkdownCodeBlock(b.language, b.text)
                is MarkdownBlock.Quote -> MarkdownQuote(b.text)
                is MarkdownBlock.Table -> MarkdownTable(b.rows)
                MarkdownBlock.Rule -> HorizontalDivider(color = GitHub.Border,
                    modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun MarkdownHeading(h: MarkdownBlock.Heading, first: Boolean) {
    val style = when (h.level) {
        1 -> MaterialTheme.typography.titleLarge
        2 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }
    // Headings open a new section, so give them more air above than below.
    Column(Modifier.padding(top = if (first) 0.dp else 8.dp)) {
        Text(inlineMarkdown(h.text), style = style, color = GitHub.Fg, fontWeight = FontWeight.SemiBold)
        if (h.level <= 2) HorizontalDivider(color = GitHub.BorderMuted, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun MarkdownListItem(item: MarkdownBlock.ListItem) {
    Row(Modifier.padding(start = (4 + item.depth * 16).dp)) {
        Text(item.marker, style = MaterialTheme.typography.bodyMedium, color = GitHub.FgMuted,
            modifier = Modifier.width(22.dp))
        Text(inlineMarkdown(item.text), style = MaterialTheme.typography.bodyMedium,
            color = GitHub.FgSecondary, lineHeight = 22.sp)
    }
}

@Composable
private fun MarkdownQuote(text: String) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(GitHub.Border))
        Text(inlineMarkdown(text), style = MaterialTheme.typography.bodyMedium, color = GitHub.FgMuted,
            modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp))
    }
}

/**
 * A fenced code block: a header with the language and a copy button, above
 * the code itself, which scrolls sideways instead of wrapping so that
 * commands and indentation stay intact.
 */
@Composable
private fun MarkdownCodeBlock(language: String, text: String) {
    val context = LocalContext.current
    Surface(
        color = GitHub.CanvasSubtle,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, GitHub.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(GitHub.CodeHeader).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(language.ifEmpty { "text" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    color = GitHub.FgMuted, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("code", text))
                }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy code",
                        modifier = Modifier.size(14.dp), tint = GitHub.FgMuted)
                }
            }
            HorizontalDivider(color = GitHub.BorderMuted)
            Text(
                text,
                modifier = Modifier.horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
                color = GitHub.Fg,
                softWrap = false,
            )
        }
    }
}

/**
 * A table drawn as a grid. Columns are sized from their longest cell (within
 * limits) and the whole grid scrolls sideways when it is wider than the screen.
 */
@Composable
private fun MarkdownTable(rows: List<List<String>>) {
    val columns = rows.maxOf { it.size }
    val widths = (0 until columns).map { c ->
        val longest = rows.maxOf { it.getOrNull(c)?.length ?: 0 }
        (longest * 7 + 24).coerceIn(56, 240).dp
    }
    Surface(
        color = GitHub.Canvas,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, GitHub.Border),
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            rows.forEachIndexed { r, row ->
                if (r > 0) HorizontalDivider(color = GitHub.BorderMuted)
                Row(Modifier.background(if (r == 0) GitHub.CanvasSubtle else GitHub.Canvas)) {
                    for (c in 0 until columns) {
                        Text(
                            inlineMarkdown(row.getOrElse(c) { "" }),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (r == 0) GitHub.Fg else GitHub.FgSecondary,
                            fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.width(widths[c]).padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
