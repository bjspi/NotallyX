package com.philkes.notallyx.presentation.view.note

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun scalesBlankLinesAndHidesThemAtZero() {
        val source = "First\n\nSecond"

        val scaled = renderMarkdown(source, blankLineScale = 0.5f) as Spanned
        assertEquals("First\n\nSecond", scaled.toString())
        val gap = scaled.getSpans(0, scaled.length, RelativeSizeSpan::class.java).single()
        assertEquals(0.5f, gap.sizeChange)
        assertEquals("First\nSecond", renderMarkdown(source, blankLineScale = 0f).toString())
    }

    @Test
    fun rendersQuotesWithMutedStripeAndTasksAsCheckboxes() {
        val quote = renderMarkdown("> Quoted") as Spanned
        val stripe = quote.getSpans(0, quote.length, QuoteSpan::class.java).single()
        assertNotEquals(0xff0000ff.toInt(), stripe.color)
        assertTrue(
            quote.getSpans(0, quote.length, StyleSpan::class.java).any {
                it.style == Typeface.ITALIC
            }
        )

        assertEquals("☐ Open\n☑ Done", renderMarkdown("- [ ] Open\n- [x] Done").toString())
    }

    @Test
    fun marksInlineCodeWithMonospaceBackground() {
        val code = renderMarkdown("Use `ls -la` here") as Spanned
        assertTrue(code.getSpans(0, code.length, BackgroundColorSpan::class.java).isNotEmpty())
        assertTrue(
            code.getSpans(0, code.length, TypefaceSpan::class.java).any { it.family == "monospace" }
        )
    }
}
