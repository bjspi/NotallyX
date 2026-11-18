package com.philkes.notallyx.presentation.view.note

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MarkdownTextTest {
    @Test
    fun preservesSingleLineBreaksAndBlankLines() {
        val source = "# Title\r\nFirst\r\nSecond\r\n\r\n\r\n**Third**"

        assertEquals("Title\nFirst\nSecond\n\n\nThird", renderMarkdown(source).toString())
    }

    @Test
    fun preservesBlankLinesBetweenListItemsAndInQuotes() {
        val source = "- First\n\n- Second\n\n> Third\n>\n> Fourth"

        assertEquals("• First\n\n• Second\n\nThird\n\nFourth", renderMarkdown(source).toString())
    }
}
