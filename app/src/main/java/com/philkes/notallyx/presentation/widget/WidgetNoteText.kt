package com.philkes.notallyx.presentation.widget

import android.text.Spanned
import android.text.style.LeadingMarginSpan
import androidx.core.text.HtmlCompat
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.presentation.applySpans
import com.philkes.notallyx.presentation.dp
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ListBlock
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

private const val LIST_INDENT_TAG = "notallylist"

internal fun BaseNote.widgetBody(markdownEnabled: Boolean): CharSequence =
    when {
        spans.isNotEmpty() -> body.applySpans(spans)
        markdownEnabled -> renderWidgetMarkdown(body)
        else -> body
    }

private fun renderWidgetMarkdown(body: String): CharSequence {
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
private class WidgetHtmlRenderer(context: HtmlNodeRendererContext) : CoreHtmlNodeRenderer(context) {
    private val writer = context.writer
    private var itemNumber: Int? = null
    private var listDepth = -1

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
        visitChildren(list)
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
        generateSequence(remaining) { it.next }.forEach(context::render)
    }
}
