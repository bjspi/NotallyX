package com.philkes.notallyx.presentation.view.note

import android.graphics.Typeface
import android.os.Build
import android.text.Editable
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.core.text.HtmlCompat
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.NoteFormat
import com.philkes.notallyx.presentation.applySpans
import com.philkes.notallyx.presentation.dp
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.CoreHtmlNodeRenderer
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlRenderer

private val markdownExtensions = listOf(StrikethroughExtension.create())
private val markdownParser =
    Parser.builder()
        .extensions(markdownExtensions)
        .includeSourceSpans(IncludeSourceSpans.BLOCKS)
        .build()
private val markdownRenderer =
    HtmlRenderer.builder()
        .extensions(markdownExtensions)
        .escapeHtml(true)
        .sanitizeUrls(true)
        .softbreak("<br />")
        .nodeRendererFactory(::MarkdownHtmlRenderer)
        .build()

private const val LIST_INDENT_TAG = "notallylist"
private const val QUOTE_TAG = "notallyquote"
private const val CODE_TAG = "notallycode"
// Faint grey behind code, visible on light and dark backgrounds alike
private const val CODE_BACKGROUND_COLOR = 0x33888888
private val TASK_MARKER = Regex("""^\[([ xX])]\s+""")
private const val BLANK_LINE_TAG = "notallyblank"
// Neutral half-transparent grey that reads as a subtle accent on light and dark backgrounds
private const val QUOTE_STRIPE_COLOR = 0x80888888.toInt()

/** @param blankLineScale height of blank Markdown lines relative to a regular text line */
internal fun BaseNote.renderedBody(blankLineScale: Float = 1f): CharSequence =
    if (format == NoteFormat.MARKDOWN) renderMarkdown(body, blankLineScale)
    else body.applySpans(spans)

/** @param blankLineScale height of blank lines relative to a regular text line, 0 hides them */
internal fun renderMarkdown(body: String, blankLineScale: Float = 1f): CharSequence {
    val html = markdownRenderer.render(markdownParser.parse(body))
    var itemStart = 0
    val quoteStarts = ArrayDeque<Int>()
    val codeStarts = ArrayDeque<Int>()
    return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT, null) {
            opening,
            tag,
            text,
            _ ->
            when (tag) {
                BLANK_LINE_TAG -> {
                    if (!opening && blankLineScale > 0f) {
                        val start = text.length
                        text.append('\n')
                        if (blankLineScale != 1f) {
                            text.setSpan(
                                RelativeSizeSpan(blankLineScale),
                                start,
                                text.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                    }
                    return@fromHtml
                }
                CODE_TAG -> {
                    if (opening) {
                        codeStarts.addLast(text.length)
                    } else {
                        val start = codeStarts.removeLastOrNull() ?: return@fromHtml
                        if (text.length > start) {
                            text.setSpan(
                                TypefaceSpan("monospace"),
                                start,
                                text.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                            text.setSpan(
                                BackgroundColorSpan(CODE_BACKGROUND_COLOR),
                                start,
                                text.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                    }
                    return@fromHtml
                }
                QUOTE_TAG -> {
                    if (opening) {
                        quoteStarts.addLast(text.length)
                    } else {
                        val start = quoteStarts.removeLastOrNull() ?: return@fromHtml
                        if (text.length > start) text.setQuoteSpans(start, text.length)
                    }
                    return@fromHtml
                }
            }
            if (!tag.startsWith(LIST_INDENT_TAG)) return@fromHtml
            val depth = tag.removePrefix(LIST_INDENT_TAG).toIntOrNull() ?: return@fromHtml
            if (opening) {
                itemStart = text.length
            } else if (text.length > itemStart) {
                val firstLineMargin = depth * 16.dp
                text.setSpan(
                    LeadingMarginSpan.Standard(firstLineMargin, firstLineMargin + 16.dp),
                    itemStart,
                    text.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        .trimEnd('\n')
}

// HtmlCompat supports only a subset of HTML that can become Android text spans.
private class MarkdownHtmlRenderer(context: HtmlNodeRendererContext) :
    CoreHtmlNodeRenderer(context) {
    private val writer = context.writer
    private var itemNumber: Int? = null
    private var listDepth = -1

    override fun visit(document: Document) = renderBlocks(document.firstChild)

    // Rendered as a custom tag: HtmlCompat's blockquote uses the platform's bright blue stripe
    override fun visit(quote: BlockQuote) {
        writer.tag("div")
        writer.tag(QUOTE_TAG)
        renderBlocks(quote.firstChild)
        writer.tag("/$QUOTE_TAG")
        writer.tag("/div")
    }

    private fun renderBlocks(first: Node?, precedingLine: Int? = null) {
        var previousLine = precedingLine
        generateSequence(first) { it.next }
            .forEach { block ->
                val spans = block.sourceSpans
                val firstLine = spans.firstOrNull()?.lineIndex
                val end = previousLine
                if (firstLine != null && end != null) {
                    // HtmlCompat supplies one newline between adjacent blocks in compact mode.
                    repeat((firstLine - end - 1).coerceAtLeast(0)) {
                        writer.raw("<$BLANK_LINE_TAG></$BLANK_LINE_TAG>")
                    }
                }
                context.render(block)
                previousLine = spans.lastOrNull()?.lineIndex ?: previousLine
            }
    }

    override fun visit(code: Code) {
        writer.tag(CODE_TAG)
        writer.text(code.literal.replace(' ', '\u00a0'))
        writer.tag("/$CODE_TAG")
    }

    override fun visit(block: FencedCodeBlock) = codeBlock(block.literal)

    override fun visit(block: IndentedCodeBlock) = codeBlock(block.literal)

    private fun codeBlock(text: String) {
        writer.tag("div")
        writer.tag(CODE_TAG)
        text.removeSuffix("\n").split('\n').forEachIndexed { index, line ->
            if (index > 0) writer.raw("<br />")
            writer.text(line.replace("\t", "    ").replace(' ', '\u00a0'))
        }
        writer.tag("/$CODE_TAG")
        writer.tag("/div")
    }

    override fun visit(image: Image) = visitChildren(image)

    override fun visit(breakNode: ThematicBreak) {
        writer.raw("<div>────</div>")
    }

    override fun visit(list: OrderedList) = renderList(list, list.markerStartNumber ?: 1)

    override fun visit(list: BulletList) = renderList(list, null)

    private fun renderList(list: ListBlock, firstNumber: Int?) {
        val outerItemNumber = itemNumber
        itemNumber = firstNumber
        listDepth++
        renderBlocks(list.firstChild)
        listDepth--
        itemNumber = outerItemNumber
    }

    override fun visit(item: ListItem) {
        val number = itemNumber
        // GFM task entries such as "- [x] Done" show a checkbox instead of the bullet
        val taskText = (item.firstChild as? Paragraph)?.firstChild as? Text
        val task = taskText?.literal?.let(TASK_MARKER::find)
        val marker =
            when {
                task != null -> if (task.groupValues[1] == " ") "☐" else "☑"
                number != null -> "$number."
                listDepth == 0 -> "•"
                else -> "–"
            }
        if (task != null) taskText.literal = taskText.literal.substring(task.range.last + 1)
        if (number != null) itemNumber = number + 1

        val indentTag = "$LIST_INDENT_TAG$listDepth"
        writer.tag("div")
        writer.tag(indentTag)
        writer.text("$marker ")
        val first = item.firstChild
        if (first is Paragraph) visitChildren(first)
        writer.raw("<br />")
        writer.tag("/$indentTag")
        writer.tag("/div")

        val remaining = if (first is Paragraph) first.next else first
        renderBlocks(remaining, (first as? Paragraph)?.sourceSpans?.lastOrNull()?.lineIndex)
    }
}

/** Marks a quote with a thin muted stripe and italic text; both spans also render in widgets. */
private fun Editable.setQuoteSpans(start: Int, end: Int) {
    val stripe =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            QuoteSpan(QUOTE_STRIPE_COLOR, 3.dp, 12.dp)
        } else QuoteSpan(QUOTE_STRIPE_COLOR)
    setSpan(stripe, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}
