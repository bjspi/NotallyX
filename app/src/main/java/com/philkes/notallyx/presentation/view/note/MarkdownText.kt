package com.philkes.notallyx.presentation.view.note

import android.text.Spanned
import android.text.style.LeadingMarginSpan
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

internal fun BaseNote.renderedBody(): CharSequence =
    if (format == NoteFormat.MARKDOWN) renderMarkdown(body) else body.applySpans(spans)

internal fun renderMarkdown(body: String): CharSequence {
    val html = markdownRenderer.render(markdownParser.parse(body))
    var itemStart = 0
    return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT, null) {
            opening,
            tag,
            text,
            _ ->
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

    override fun visit(quote: BlockQuote) {
        writer.tag("blockquote")
        renderBlocks(quote.firstChild)
        writer.tag("/blockquote")
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
                    repeat((firstLine - end - 1).coerceAtLeast(0)) { writer.raw("<br />") }
                }
                context.render(block)
                previousLine = spans.lastOrNull()?.lineIndex ?: previousLine
            }
    }

    override fun visit(code: Code) {
        writer.tag("tt")
        writer.text(code.literal.replace(' ', '\u00a0'))
        writer.tag("/tt")
    }

    override fun visit(block: FencedCodeBlock) = codeBlock(block.literal)

    override fun visit(block: IndentedCodeBlock) = codeBlock(block.literal)

    private fun codeBlock(text: String) {
        writer.tag("div")
        writer.tag("tt")
        text.removeSuffix("\n").split('\n').forEachIndexed { index, line ->
            if (index > 0) writer.raw("<br />")
            writer.text(line.replace("\t", "    ").replace(' ', '\u00a0'))
        }
        writer.tag("/tt")
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
        val marker = if (number != null) "$number." else if (listDepth == 0) "•" else "–"
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
