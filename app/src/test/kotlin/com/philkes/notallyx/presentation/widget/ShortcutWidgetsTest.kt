package com.philkes.notallyx.presentation.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.philkes.notallyx.R
import com.philkes.notallyx.data.imports.google.GoogleKeepImporterTest.Companion.createBaseNote
import com.philkes.notallyx.data.model.Type
import com.philkes.notallyx.presentation.activity.main.MainActivity
import com.philkes.notallyx.presentation.activity.note.EditActivity.Companion.EXTRA_SELECTED_BASE_NOTE
import com.philkes.notallyx.presentation.activity.note.EditListActivity
import com.philkes.notallyx.presentation.activity.note.EditNoteActivity
import com.philkes.notallyx.test.app
import com.philkes.notallyx.test.database
import com.philkes.notallyx.test.preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
class ShortcutWidgetsTest {
    @get:Rule val roborazziRule = RoborazziRule()

    @Test
    fun noteShortcutShowsTitleFollowsChangesAndOpensNoteAboveOverview() {
        val groceries =
            createBaseNote(title = "Groceries", color = "#FFCC80").copy(type = Type.LIST)
        val note = runBlocking(Dispatchers.IO) { database.getBaseNoteDao().insert(groceries) }
        val manager = AppWidgetManager.getInstance(app)
        val host = shadowOf(manager)
        val widgetId =
            host.createWidget(NoteShortcutWidgetProvider::class.java, R.layout.widget_note_shortcut)
        try {
            preferences.setNoteShortcutWidget(widgetId, note)
            NoteShortcutWidgetProvider.updateWidget(app, manager, widgetId, locked = false)
            awaitTitle(host::getViewFor, widgetId, "Groceries")
            capture(host.getViewFor(widgetId), "note_shortcut")

            assertTrue(
                host.getViewFor(widgetId).findViewById<View>(R.id.WidgetShortcutRoot).performClick()
            )
            ShadowLooper.idleMainLooper()
            // Robolectric hands out the most recently started activity first
            val started = generateSequence { shadowOf(app).nextStartedActivity }.toList().reversed()
            assertEquals(
                listOf(MainActivity::class.java.name, EditListActivity::class.java.name),
                started.map { it.component?.className },
            )
            assertEquals(note, started.last().getLongExtra(EXTRA_SELECTED_BASE_NOTE, 0))

            // Note changes reach the shortcut through the shared widget update path
            runBlocking(Dispatchers.IO) {
                database
                    .getBaseNoteDao()
                    .insert(groceries.copy(id = note, title = "Weekly groceries"))
            }
            WidgetProvider.updateWidgets(app, longArrayOf(note), locked = false)
            awaitTitle(host::getViewFor, widgetId, "Weekly groceries")

            WidgetProvider.updateWidgets(app, locked = true)
            awaitTitle(host::getViewFor, widgetId, app.getString(R.string.locked))
        } finally {
            NoteShortcutWidgetProvider().onDeleted(app, intArrayOf(widgetId))
            runBlocking(Dispatchers.IO) { database.getBaseNoteDao().delete(longArrayOf(note)) }
        }
        assertEquals(0L, preferences.getNoteShortcutWidget(widgetId))
    }

    @Test
    fun newNoteWidgetCreatesTextNoteAboveOverview() {
        val manager = AppWidgetManager.getInstance(app)
        val host = shadowOf(manager)
        val widgetId =
            host.createWidget(NewNoteWidgetProvider::class.java, R.layout.widget_new_note)
        NewNoteWidgetProvider.updateWidgets(app)
        val widget = host.getViewFor(widgetId)
        capture(widget, "new_note")

        assertTrue(widget.findViewById<View>(R.id.WidgetShortcutRoot).performClick())
        ShadowLooper.idleMainLooper()
        // Robolectric hands out the most recently started activity first
        val started = generateSequence { shadowOf(app).nextStartedActivity }.toList().reversed()
        assertEquals(
            listOf(MainActivity::class.java.name, EditNoteActivity::class.java.name),
            started.map { it.component?.className },
        )
    }

    private fun awaitTitle(widget: (Int) -> View, id: Int, title: String) {
        val deadline = System.nanoTime() + 5_000_000_000L
        fun current() = widget(id).findViewById<TextView>(R.id.WidgetShortcutTitle).text.toString()
        while (System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            if (current() == title) return
            Thread.sleep(10)
        }
        assertEquals(title, current())
    }

    private fun capture(widget: View, name: String) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.AppTheme)
        (widget.parent as? FrameLayout)?.removeView(widget)
        val root = FrameLayout(activity)
        root.addView(widget, FrameLayout.LayoutParams(72, 72))
        activity.setContentView(root)
        ShadowLooper.idleMainLooper()
        widget.captureRoboImage("ShortcutWidgetsTest/$name.png")
    }
}
