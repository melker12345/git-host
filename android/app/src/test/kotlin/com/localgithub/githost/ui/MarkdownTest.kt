package com.localgithub.githost.ui

import com.localgithub.githost.ui.MarkdownBlock.*
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {

    @Test
    fun fencedCodeKeepsLanguageAndVerbatimText() {
        val blocks = parseMarkdown("Run:\n\n```bash\ngit-host sync\n  # indented *not italic*\n```\n")
        assertEquals(
            listOf(Paragraph("Run:"), Code("bash", "git-host sync\n  # indented *not italic*")),
            blocks,
        )
    }

    @Test
    fun tableRowsBecomeOneTableWithoutTheSeparator() {
        val blocks = parseMarkdown("| a | b |\n|---|:-:|\n| 1 | 2 |\n| 3 | 4 |")
        assertEquals(listOf(Table(listOf(listOf("a", "b"), listOf("1", "2"), listOf("3", "4")))), blocks)
    }

    @Test
    fun nestedTaskAndContinuedListItems() {
        val blocks = parseMarkdown("- top\n  - nested\n- [x] done\n- wrapped\n  onto two lines")
        assertEquals(
            listOf(
                ListItem("•", "top", 0),
                ListItem("•", "nested", 1),
                ListItem("☑", "done", 0),
                ListItem("•", "wrapped onto two lines", 0),
            ),
            blocks,
        )
    }

    @Test
    fun quotesHeadingsAndHtmlLines() {
        val blocks = parseMarkdown("<p align=\"center\">\n# Title\n> one\n> two\n\nafter")
        assertEquals(listOf(Heading(1, "Title"), Quote("one two"), Paragraph("after")), blocks)
    }

    @Test
    fun hashWithoutSpaceIsNotAHeading() {
        assertEquals(listOf(Paragraph("#hashtag")), parseMarkdown("#hashtag"))
    }

    @Test
    fun snakeCaseIsNotItalic() {
        assertEquals("use file_name_here", inlineMarkdown("use file_name_here").text)
        assertEquals("an emphasis", inlineMarkdown("an _emphasis_").text)
        assertEquals("a \u2009code\u2009 span", inlineMarkdown("a `code` span").text)
    }
}
