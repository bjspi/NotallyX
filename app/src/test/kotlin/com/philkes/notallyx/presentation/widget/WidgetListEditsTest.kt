package com.philkes.notallyx.presentation.widget

import android.app.Activity
import android.content.Intent
import android.os.Parcel
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.philkes.notallyx.R
import com.philkes.notallyx.data.imports.google.GoogleKeepImporterTest.Companion.createBaseNote
import com.philkes.notallyx.data.model.ListItem
import com.philkes.notallyx.data.model.Type
import com.philkes.notallyx.test.app
import com.philkes.notallyx.test.database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetListEditsTest {
    @get:Rule val roborazziRule = RoborazziRule()

    private val todo =
        listOf(
            item("Milk", order = 0),
            item("Eggs", order = 1, isChild = true),
            item("Call bank", order = 2),
            item("Pay rent", order = 3, checked = true),
        )

    @Test
    fun newItemsGoBelowTheUncheckedOnesOrToTheEnd() {
        assertEquals(3, WidgetListEdits.addPosition(todo, autoSortByChecked = true))
        assertEquals(4, WidgetListEdits.addPosition(todo, autoSortByChecked = false))
        // The children of the last unchecked entry stay with it
        assertEquals(2, WidgetListEdits.addPosition(todo.take(2), autoSortByChecked = true))
        val allChecked = listOf(item("Done", order = 0, checked = true))
        assertEquals(0, WidgetListEdits.addPosition(allChecked, autoSortByChecked = true))

        val added = WidgetListEdits.addItems(todo, listOf("Bread", "Butter"), true)!!
        assertEquals(
            listOf("Milk", "Eggs", "Call bank", "Bread", "Butter", "Pay rent"),
            added.map { it.body },
        )
        assertEquals(listOf(4, 5), added.subList(3, 5).map { it.order })
        assertTrue(added.subList(3, 5).none { it.checked || it.isChild })
        assertNull(WidgetListEdits.addItems(todo, emptyList(), true))
    }

    @Test
    fun editsOnlyTheEntryTheWidgetShowed() {
        assertEquals(
            listOf("Milk", "Oat milk", "Call bank", "Pay rent"),
            WidgetListEdits.editItem(todo, 1, "Eggs", "Oat milk")!!.map { it.body },
        )
        assertTrue(WidgetListEdits.editItem(todo, 1, "Eggs", "Oat milk")!![1].isChild)
        assertNull(WidgetListEdits.editItem(todo, 1, "Bread", "Oat milk"))
        assertNull(WidgetListEdits.editItem(todo, 1, "Eggs", "Eggs"))
    }

    @Test
    fun deleteAsksFirstAndEditSavesTheNewText() {
        val dao = database.getBaseNoteDao()
        val id =
            runBlocking(Dispatchers.IO) {
                dao.insert(createBaseNote(type = Type.LIST, items = todo))
            }
        try {
            // Cancelling the confirmation keeps the entry
            runDialog(WidgetListItemActivity.deleteIntent(app, id, 3, "Pay rent")) {
                it.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            assertEquals(4, runBlocking(Dispatchers.IO) { dao.get(id)!!.items.size })

            runDialog(WidgetListItemActivity.deleteIntent(app, id, 3, "Pay rent")) {
                it.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            awaitItems(id, listOf("Milk", "Eggs", "Call bank"))

            runDialog(WidgetListItemActivity.editIntent(app, id, 2, "Call bank")) {
                val input = it.findViewById<EditText>(R.id.EditText)!!
                assertEquals("Call bank", input.text.toString())
                input.setText("Call the bank")
                it.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            awaitItems(id, listOf("Milk", "Eggs", "Call the bank"))

            // "Another" keeps the dialog open for the next entry
            runDialog(WidgetListItemActivity.addIntent(app, id)) {
                val input = it.findViewById<EditText>(R.id.EditText)!!
                input.setText("Bread")
                it.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                // The field is cleared once the entry is saved
                await { input.text.isEmpty().takeIf { empty -> empty } }
                awaitItems(id, listOf("Milk", "Eggs", "Call the bank", "Bread"))
                assertTrue(it.isShowing)
                input.setText("Butter")
                it.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            awaitItems(id, listOf("Milk", "Eggs", "Call the bank", "Bread", "Butter"))
        } finally {
            runBlocking(Dispatchers.IO) { dao.delete(id) }
        }
    }

    private fun runDialog(intent: Intent, action: (AlertDialog) -> Unit) {
        val previous = ShadowDialog.getLatestDialog()
        val activity =
            Robolectric.buildActivity(WidgetListItemActivity::class.java, intent).setup().get()
        val dialog = await {
            (ShadowDialog.getLatestDialog() as? AlertDialog)?.takeIf {
                it !== previous && it.isShowing
            }
        }
        action(dialog)
        await { activity.isFinishing.takeIf { it } }
    }

    private fun awaitItems(id: Long, bodies: List<String>) {
        await {
            runBlocking(Dispatchers.IO) { database.getBaseNoteDao().get(id)!!.items }
                .map { it.body }
                .takeIf { it == bodies }
        }
    }

    private fun <T : Any> await(value: () -> T?): T {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            value()?.let {
                return it
            }
            Thread.sleep(10)
        }
        return requireNotNull(value()) { "Timed out" }
    }

    @Test
    fun deletesOnlyTheCheckedEntryTheWidgetShowed() {
        assertEquals(
            listOf("Milk", "Eggs", "Call bank"),
            WidgetListEdits.deleteCheckedItem(todo, 3, "Pay rent")!!.map { it.body },
        )
        // An outdated widget must not delete another entry
        assertNull(WidgetListEdits.deleteCheckedItem(todo, 3, "Pay bills"))
        assertNull(WidgetListEdits.deleteCheckedItem(todo, 2, "Call bank"))
        assertNull(WidgetListEdits.deleteCheckedItem(todo, 9, "Pay rent"))

        val parent =
            listOf(
                item("Trip", order = 0, checked = true),
                item("Tickets", order = 1, isChild = true, checked = true),
                item("Hotel", order = 2, isChild = true, checked = true),
                item("Later", order = 3),
            )
        assertEquals(
            listOf("Later"),
            WidgetListEdits.deleteCheckedItem(parent, 0, "Trip")!!.map { it.body },
        )
    }

    @Test
    fun deletingAChildChecksTheParentOnceAllOtherChildrenAreChecked() {
        val items =
            listOf(
                item("Trip", order = 0),
                item("Tickets", order = 1, isChild = true, checked = true),
                item("Hotel", order = 2, isChild = true),
            )
        val withoutTickets = WidgetListEdits.deleteCheckedItem(items, 1, "Tickets")!!
        assertEquals(listOf("Trip", "Hotel"), withoutTickets.map { it.body })
        assertFalse(withoutTickets[0].checked)

        val checkedHotel = items.toMutableList().apply { set(2, this[2].copy(checked = true)) }
        val parentLeft = WidgetListEdits.deleteCheckedItem(checkedHotel, 1, "Tickets")!!
        assertTrue(parentLeft[0].checked)
        assertTrue(parentLeft[0].checkedTimestamp != null)
        // The given list stays untouched, it is the original for the save effects
        assertFalse(checkedHotel[0].checked)
    }

    @Test
    fun storesEditsOfListsOnly() {
        val dao = database.getBaseNoteDao()
        val (list, note) =
            runBlocking(Dispatchers.IO) {
                dao.insert(createBaseNote(type = Type.LIST, items = todo, modifiedTimestamp = 1)) to
                    dao.insert(createBaseNote(body = "Text"))
            }
        try {
            val added = runBlocking {
                app.editWidgetList(list) { WidgetListEdits.addItems(it, listOf("Bread"), true) }
            }
            assertTrue(added)
            val stored = runBlocking(Dispatchers.IO) { dao.get(list)!! }
            assertEquals(
                listOf("Milk", "Eggs", "Call bank", "Bread", "Pay rent"),
                stored.items.map { it.body },
            )
            assertTrue(stored.modifiedTimestamp > 1)
            assertFalse(runBlocking { app.editWidgetList(list) { null } })
            assertFalse(runBlocking { app.editWidgetList(note) { it } })
        } finally {
            runBlocking(Dispatchers.IO) { dao.delete(longArrayOf(list, note)) }
        }
    }

    @Test
    fun widgetShowsTrashForCheckedEntriesAndTheAddRowBelowTheUncheckedOnes() {
        val id =
            runBlocking(Dispatchers.IO) {
                database
                    .getBaseNoteDao()
                    .insert(
                        createBaseNote(title = "Short-term to-do", type = Type.LIST, items = todo)
                    )
            }
        try {
            val activity =
                Robolectric.buildActivity(Activity::class.java).setup().get().apply {
                    setTheme(R.style.AppTheme)
                }
            val factory = WidgetFactory(app, id).apply { onDataSetChanged() }
            assertEquals(todo.size + 1, factory.count)
            val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val rows =
                (0 until factory.count).map { position ->
                    applyParceled(factory.getViewAt(position), activity, column).also {
                        column.addView(it)
                    }
                }

            assertEquals(
                app.getString(R.string.add_item),
                rows[3].findViewById<TextView>(R.id.AddItemText).text.toString(),
            )
            val trashVisible =
                listOf(0, 1, 2, 4).map { position ->
                    rows[position].findViewById<View>(R.id.DeleteItem).visibility == View.VISIBLE
                }
            assertEquals(listOf(false, false, false, true), trashVisible)

            val root = FrameLayout(activity)
            root.addView(
                column,
                FrameLayout.LayoutParams(240, FrameLayout.LayoutParams.WRAP_CONTENT),
            )
            activity.setContentView(root)
            ShadowLooper.idleMainLooper()
            column.captureRoboImage("WidgetListEditsTest/list_rows.png")
        } finally {
            runBlocking(Dispatchers.IO) { database.getBaseNoteDao().delete(id) }
        }
    }

    private fun applyParceled(views: RemoteViews, activity: Activity, parent: LinearLayout): View {
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
        return copy.apply(activity, parent)
    }

    private fun item(
        body: String,
        order: Int,
        isChild: Boolean = false,
        checked: Boolean = false,
    ) = ListItem(body, checked, isChild, order, mutableListOf())
}
