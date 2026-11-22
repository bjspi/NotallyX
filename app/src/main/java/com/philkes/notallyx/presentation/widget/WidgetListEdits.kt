package com.philkes.notallyx.presentation.widget

import androidx.room.withTransaction
import com.philkes.notallyx.NotallyXApplication
import com.philkes.notallyx.data.NotallyDatabase
import com.philkes.notallyx.data.model.ListItem
import com.philkes.notallyx.data.model.Type
import com.philkes.notallyx.presentation.view.note.listitem.findChildrenPositions
import com.philkes.notallyx.presentation.view.note.listitem.findParentPosition
import com.philkes.notallyx.presentation.viewmodel.preference.NotallyXPreferences
import com.philkes.notallyx.utils.onNoteSaved
import com.philkes.notallyx.utils.uniqueCurrentMillis

/**
 * Edits of a stored list that the widget applies directly, without opening the list editor.
 *
 * The widget shows the stored items in their stored order. The editor normalizes ids, orders and
 * parent states again when it loads the list, so these edits only keep the stored list valid.
 */
internal object WidgetListEdits {

    /**
     * Where new items go, like "Add item" in the editor: below the last unchecked entry when
     * checked entries are sorted to the bottom, otherwise at the end of the list.
     */
    fun addPosition(items: List<ListItem>, autoSortByChecked: Boolean): Int {
        if (!autoSortByChecked) return items.size
        val lastUnchecked = items.indexOfLast { !it.isChild && !it.checked }
        if (lastUnchecked == -1) return 0
        return lastUnchecked + 1 + items.findChildrenPositions(lastUnchecked).size
    }

    /** Adds unchecked top-level entries at [addPosition], or returns null if none are given. */
    fun addItems(
        items: List<ListItem>,
        bodies: List<String>,
        autoSortByChecked: Boolean,
    ): List<ListItem>? {
        if (bodies.isEmpty()) return null
        var order = (items.maxOfOrNull { it.order ?: -1 } ?: -1) + 1
        val added = bodies.map { ListItem(it, false, false, order++, mutableListOf()) }
        return items.toMutableList().apply { addAll(addPosition(items, autoSortByChecked), added) }
    }

    /**
     * Replaces the text of the entry at [position], which must still have [expectedBody] (see
     * [deleteCheckedItem]). Returns null for an outdated position or an unchanged text.
     */
    fun editItem(
        items: List<ListItem>,
        position: Int,
        expectedBody: String,
        newBody: String,
    ): List<ListItem>? {
        val item = items.getOrNull(position) ?: return null
        if (item.body != expectedBody || newBody == expectedBody) return null
        return items.toMutableList().apply { set(position, item.copy(body = newBody)) }
    }

    /**
     * Deletes the checked entry at [position] together with its children.
     *
     * The widget only knows the position it rendered, so the entry must still be checked and have
     * [expectedBody]. Otherwise the list changed meanwhile and null is returned. A parent whose
     * remaining children are all checked becomes checked, as in the editor.
     */
    fun deleteCheckedItem(
        items: List<ListItem>,
        position: Int,
        expectedBody: String,
    ): List<ListItem>? {
        val item = items.getOrNull(position) ?: return null
        if (!item.checked || item.body != expectedBody) return null
        val result = items.toMutableList()
        if (!item.isChild) {
            val end = position + 1 + items.findChildrenPositions(position).size
            result.subList(position, end).clear()
            return result
        }
        result.removeAt(position)
        val parentPosition = items.findParentPosition(position) ?: return result
        val parent = result[parentPosition]
        val children = result.findChildrenPositions(parentPosition)
        if (!parent.checked && children.isNotEmpty() && children.all { result[it].checked }) {
            result[parentPosition] =
                parent.copy(checked = true, checkedTimestamp = uniqueCurrentMillis())
        }
        return result
    }
}

/**
 * Stores the result of [edit] for the list [noteId] and refreshes its widgets.
 *
 * Must run off the main thread. Returns false without a change while the app is locked, if the note
 * is gone or no list, or if [edit] returns null.
 */
internal suspend fun NotallyXApplication.editWidgetList(
    noteId: Long,
    edit: (List<ListItem>) -> List<ListItem>?,
): Boolean {
    val preferences = NotallyXPreferences.getInstance(this)
    if (preferences.isLockEnabled && locked.value) return false
    val database = NotallyDatabase.getDatabase(this).value ?: return false
    val dao = database.getBaseNoteDao()
    val (original, updated) =
        database.withTransaction {
            val original = dao.get(noteId)?.takeIf { it.type == Type.LIST }
            val items = original?.let { edit(it.items) } ?: return@withTransaction null
            val updated =
                original.copy(
                    items = items,
                    modifiedTimestamp =
                        maxOf(System.currentTimeMillis(), original.modifiedTimestamp + 1),
                )
            dao.updateAll(listOf(updated))
            original to updated
        } ?: return false
    WidgetProvider.sendBroadcast(this, longArrayOf(noteId))
    onNoteSaved(preferences, updated, original)
    return true
}
