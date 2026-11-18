package com.philkes.notallyx.presentation.widget

import androidx.core.text.HtmlCompat
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.presentation.applySpans
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ListItem
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.CoreHtmlNodeRenderer
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlRenderer

private val markdownExtensions = listOf(StrikethroughExtension.create())
private val markdownParser = Parser.builder().extensions(markdownExtensions).build()
private val markdownRenderer =
    HtmlRenderer.builder()
        .extensions(markdownExtensions)
        .escapeHtml(true)
        .sanitizeUrls(true)
        .softbreak("<br />")
        .nodeRendererFactory(::WidgetHtmlRenderer)
        .build()

internal fun BaseNote.widgetBody(markdownEnabled: Boolean): CharSequence =
    when {
        spans.isNotEmpty() -> body.applySpans(spans)
        markdownEnabled -> {
            val html = markdownRenderer.render(markdownParser.parse(body))
            HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).trimEnd('\n')
        }
        else -> body
    }

// HtmlCompat supports only a subset of HTML that can become Android text spans.
private class WidgetHtmlRenderer(context: HtmlNodeRendererContext) : CoreHtmlNodeRenderer(context) {
    private val writer = context.writer
    private var itemNumber = 0

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

    override fun visit(list: OrderedList) {
        val outerItemNumber = itemNumber
        itemNumber = list.markerStartNumber ?: 1
        visitChildren(list)
        itemNumber = outerItemNumber
    }

    override fun visit(item: ListItem) {
        if (item.parent !is OrderedList) {
            super.visit(item)
            return
        }
        writer.tag("div")
        writer.text("${itemNumber++}. ")
        val first = item.firstChild
        if (first is Paragraph) {
            visitChildren(first)
            generateSequence(first.next) { it.next }.forEach(context::render)
        } else {
            visitChildren(item)
        }
        writer.tag("/div")
    }
}
