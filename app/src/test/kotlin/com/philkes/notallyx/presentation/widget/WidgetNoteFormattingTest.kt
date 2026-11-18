package com.philkes.notallyx.presentation.widget

import android.app.Activity
import android.graphics.Typeface
import android.os.Parcel
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.philkes.notallyx.R
import com.philkes.notallyx.data.imports.google.GoogleKeepImporterTest.Companion.createBaseNote
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.NoteFormat
import com.philkes.notallyx.data.model.SpanRepresentation
import com.philkes.notallyx.test.app
import com.philkes.notallyx.test.database
import com.philkes.notallyx.test.preferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetNoteFormattingTest {
    @get:Rule val roborazziRule = RoborazziRule()

    @Test
    fun existingTextNoteUsesItsOwnFormatRegardlessOfTheNewNoteDefault() {
        val note =
            createBaseNote(
                body = "**literal** bold italic code",
                spans =
                    listOf(
                        SpanRepresentation(12, 16, bold = true),
                        SpanRepresentation(17, 23, italic = true),
                        SpanRepresentation(24, 28, monospace = true),
                    ),
            )
        val activity = hostActivity()
        try {
            for (enabled in listOf(false, true)) {
                preferences.newNotesUseMarkdown.save(enabled)
                val body = render(activity, note).findViewById<TextView>(R.id.Note).text as Spanned
                assertEquals(note.body, body.toString())
                val styles = body.getSpans(0, body.length, StyleSpan::class.java)
                assertEquals(setOf(Typeface.BOLD, Typeface.ITALIC), styles.map { it.style }.toSet())
                assertEquals(
                    "monospace",
                    body.getSpans(0, body.length, TypefaceSpan::class.java).single().family,
                )
            }
        } finally {
            preferences.newNotesUseMarkdown.save(false)
        }
    }

    @Test
    fun markdownNoteRendersHeadingsListsAndInlineStylesWithTheDefaultOff() {
        val activity = hostActivity()
        val note =
            createBaseNote(
                title = "Widget preview",
                body =
                    """
                    # Markdown

                    ## Details

                    **Bold**, *italic*, ~~old~~ and `code`.

                    - A bullet
                      - Nested item
                        - Deeper item

                    3. Third
                    4. Fourth

                    ```
                    x
                      y
                    ```

                    ![Image caption](https://example.com/image.png)
                    """
                        .trimIndent(),
            )
        try {
            preferences.newNotesUseMarkdown.save(false)
            assertEquals(
                note.body,
                render(activity, note).findViewById<TextView>(R.id.Note).text.toString(),
            )

            val widget = render(activity, note.copy(format = NoteFormat.MARKDOWN))
            val body = widget.findViewById<TextView>(R.id.Note).text as Spanned
            assertTrue(body.startsWith("Markdown\n\nDetails\n\nBold, italic, old and code."))
            assertTrue(body.contains("3. Third\n4. Fourth"))
            assertTrue(body.contains("x\n\u00a0\u00a0y"))
            assertTrue(body.endsWith("Image caption"))
            assertTrue(headingSize(body, "Markdown") > headingSize(body, "Details"))
            assertTrue(indent(body, "– Nested item") > indent(body, "• A bullet"))
            assertTrue(indent(body, "– Deeper item") > indent(body, "– Nested item"))
            assertTrue(body.getSpans(0, body.length, StrikethroughSpan::class.java).isNotEmpty())
            assertTrue(body.getSpans(0, body.length, TypefaceSpan::class.java).size == 2)

            val root = FrameLayout(activity)
            root.addView(widget, FrameLayout.LayoutParams(240, 280))
            activity.setContentView(root)
            ShadowLooper.idleMainLooper()
            widget.captureRoboImage("WidgetNoteFormattingTest/markdown.png")
        } finally {
            preferences.newNotesUseMarkdown.save(false)
        }
    }

    private fun headingSize(body: Spanned, heading: String): Float {
        val start = body.indexOf(heading)
        return body
            .getSpans(start, start + heading.length, RelativeSizeSpan::class.java)
            .single()
            .sizeChange
    }

    private fun indent(body: Spanned, item: String): Int {
        val start = body.indexOf(item)
        assertTrue("Missing list item: $item", start >= 0)
        return body
            .getSpans(start, start + item.length, LeadingMarginSpan.Standard::class.java)
            .single()
            .getLeadingMargin(true)
    }

    private fun hostActivity(): Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get().apply {
            setTheme(R.style.AppTheme)
        }

    private fun render(activity: Activity, note: BaseNote): FrameLayout {
        val id = runBlocking { database.getBaseNoteDao().insert(note) }
        val views = WidgetFactory(app, id).apply { onDataSetChanged() }.getViewAt(0)
        val copy =
            Parcel.obtain().let { parcel ->
                try {
                    views.writeToParcel(parcel, 0)
                    parcel.setDataPosition(0)
                    RemoteViews.CREATOR.createFromParcel(parcel)
                } finally {
                    parcel.recycle()
                }
            }
        return FrameLayout(activity).apply { addView(copy.apply(activity, this)) }
    }
}
