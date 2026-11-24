package com.philkes.notallyx.presentation.widget

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.philkes.notallyx.R
import com.philkes.notallyx.data.imports.google.GoogleKeepImporterTest.Companion.createBaseNote
import com.philkes.notallyx.data.model.Folder
import com.philkes.notallyx.data.model.Type
import com.philkes.notallyx.presentation.activity.main.MainActivity
import com.philkes.notallyx.presentation.activity.note.EditActivity.Companion.EXTRA_SELECTED_BASE_NOTE
import com.philkes.notallyx.test.app
import com.philkes.notallyx.test.database
import com.philkes.notallyx.test.preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetNotePickerTest {
    @get:Rule val roborazziRule = RoborazziRule()

    @Test
    fun dropdownShowsTitlesAndSwitchesOnlyItsOwnWidgetWithoutOpeningAnActivity() {
        val notes =
            runBlocking(Dispatchers.IO) {
                database
                    .getBaseNoteDao()
                    .insert(
                        listOf(
                            createBaseNote(
                                    title = "Trip ideas",
                                    body = "Private note body",
                                    color = "#82B1FF",
                                )
                                .copy(pinned = true, modifiedTimestamp = Long.MAX_VALUE - 1),
                            createBaseNote(title = "Shopping", color = "#FFCC80")
                                .copy(
                                    type = Type.LIST,
                                    pinned = true,
                                    modifiedTimestamp = Long.MAX_VALUE,
                                ),
                            createBaseNote(title = "Deleted").copy(folder = Folder.DELETED),
                            createBaseNote(title = "Archived").copy(folder = Folder.ARCHIVED),
                            createBaseNote(title = "")
                                .copy(pinned = true, modifiedTimestamp = Long.MAX_VALUE - 2),
                        )
                    )
            }
        val (note, list) = notes
        val manager = AppWidgetManager.getInstance(app)
        val host = shadowOf(manager)
        val (widgetId, otherWidgetId) =
            host.createWidgets(WidgetProvider::class.java, R.layout.widget, 2)
        preferences.updateWidget(widgetId, note, Type.NOTE)
        preferences.updateWidget(otherWidgetId, note, Type.NOTE)
        try {
            WidgetProvider.updateWidget(app, manager, widgetId, note)
            awaitTitle({ host.getViewFor(widgetId) }, "Trip ideas")
            val widget = host.getViewFor(widgetId)
            val dropdown = widget.findViewById<View>(R.id.WidgetNoteDropdown)
            assertEquals(View.VISIBLE, dropdown.visibility)
            assertEquals(View.GONE, widget.findViewById<View>(R.id.WidgetNotePicker).visibility)
            assertTrue(widget.findViewById<View>(R.id.WidgetOpenApp).performClick())
            ShadowLooper.idleMainLooper()
            val opened = checkNotNull(shadowOf(app).nextStartedActivity)
            assertEquals(MainActivity::class.java.name, opened.component?.className)
            assertEquals(MainActivity.ACTION_OPEN_NOTES_OVERVIEW, opened.action)
            // Title and arrow share one click target that toggles the note picker
            assertEquals(widget.findViewById<View>(R.id.WidgetTitleArea), dropdown.parent)
            assertTrue(widget.findViewById<View>(R.id.WidgetTitleArea).performClick())
            awaitWidget {
                host.getViewFor(widgetId).findViewById<View>(R.id.WidgetNotePicker).visibility ==
                    View.VISIBLE
            }
            assertEquals(
                View.GONE,
                host.getViewFor(widgetId).findViewById<View>(R.id.ListView).visibility,
            )

            val factory = WidgetNoteChoicesFactory(app, widgetId)
            runBlocking(Dispatchers.IO) { factory.onDataSetChanged() }
            val choiceIds = (0 until factory.getCount()).map(factory::getItemId)
            assertEquals(listOf(list, note, notes[4]), choiceIds.filter { it in notes })
            assertEquals(list, factory.getItemId(0))
            val root = host.getViewFor(widgetId)
            assertEquals(
                app.getString(R.string.select_note),
                root.findViewById<TextView>(R.id.WidgetNotePickerTitle).text.toString(),
            )
            val choices = root.findViewById<ListView>(R.id.WidgetNoteChoices)
            val rows = (0..2).map { index -> factory.getViewAt(index)!!.apply(app, choices) }
            assertEquals(
                listOf("Shopping", "Trip ideas", app.getString(R.string.widget_untitled_note)),
                rows.map { it.findViewById<TextView>(R.id.WidgetNoteChoice).text.toString() },
            )
            val rowColors = rows.map { (it.background as ColorDrawable).color }
            val stripeColors = rows.map {
                (it.findViewById<View>(R.id.WidgetNoteChoiceStripe).background as ColorDrawable)
                    .color
            }
            assertEquals(3, rowColors.distinct().size)
            assertEquals(3, stripeColors.distinct().size)
            assertNotEquals(rowColors, stripeColors)
            val template = choices.tag as PendingIntent
            capture(root, choices, factory)

            // A tap beside the notes closes the picker and keeps the shown note
            fun pickerVisibility() =
                host.getViewFor(widgetId).findViewById<View>(R.id.WidgetNotePicker).visibility
            assertTrue(root.findViewById<View>(R.id.WidgetNotePicker).performClick())
            awaitWidget { pickerVisibility() == View.GONE }
            awaitTitle({ host.getViewFor(widgetId) }, "Trip ideas")
            assertEquals(note, preferences.getWidgetData(widgetId))
            assertTrue(
                host.getViewFor(widgetId).findViewById<View>(R.id.WidgetTitleArea).performClick()
            )
            awaitWidget { pickerVisibility() == View.VISIBLE }

            template.send(app, 0, Intent().putExtra(EXTRA_SELECTED_BASE_NOTE, list))
            awaitTitle({ host.getViewFor(widgetId) }, "Shopping")
            assertEquals(
                View.GONE,
                host.getViewFor(widgetId).findViewById<View>(R.id.WidgetNotePicker).visibility,
            )
            assertEquals(list, preferences.getWidgetData(widgetId))
            assertEquals(Type.LIST, preferences.getWidgetNoteType(widgetId))
            assertEquals(note, preferences.getWidgetData(otherWidgetId))
            assertNull(shadowOf(app).nextStartedActivity)
        } finally {
            WidgetProvider().onDeleted(app, intArrayOf(widgetId, otherWidgetId))
            runBlocking(Dispatchers.IO) { database.getBaseNoteDao().delete(notes.toLongArray()) }
        }
    }

    private fun awaitTitle(widget: () -> View, title: String) {
        awaitWidget { widget().findViewById<TextView>(R.id.WidgetTitle).text.toString() == title }
    }

    private fun awaitWidget(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("Widget update did not arrive", condition())
    }

    private fun capture(widget: View, choices: ListView, factory: WidgetNoteChoicesFactory) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.AppTheme)
        choices.adapter =
            object : BaseAdapter() {
                override fun getCount() = factory.getCount()

                override fun getItem(position: Int) = factory.getViewAt(position)!!

                override fun getItemId(position: Int) = factory.getItemId(position)

                override fun getView(position: Int, convertView: View?, parent: ViewGroup) =
                    getItem(position).apply(parent.context, parent)
            }
        val root = FrameLayout(activity)
        root.addView(widget, FrameLayout.LayoutParams(244, 192))
        activity.setContentView(root)
        ShadowLooper.idleMainLooper()
        widget.captureRoboImage("WidgetNotePickerTest/open.png")
    }
}
