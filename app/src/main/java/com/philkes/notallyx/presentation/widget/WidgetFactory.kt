package com.philkes.notallyx.presentation.widget

import android.content.res.ColorStateList
import android.graphics.Paint
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.philkes.notallyx.NotallyXApplication
import com.philkes.notallyx.R
import com.philkes.notallyx.data.NotallyDatabase
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.ListItem
import com.philkes.notallyx.data.model.Type
import com.philkes.notallyx.presentation.view.note.renderedBody
import com.philkes.notallyx.presentation.viewmodel.preference.NotallyXPreferences
import com.philkes.notallyx.presentation.viewmodel.preference.autoSortByCheckedEnabled
import com.philkes.notallyx.presentation.viewmodel.preference.displayBodySize
import com.philkes.notallyx.presentation.viewmodel.preference.markdownBlankLineScale
import com.philkes.notallyx.presentation.widget.WidgetProvider.Companion.extractWidgetColors
import com.philkes.notallyx.presentation.widget.WidgetProvider.Companion.getWidgetAddItemIntent
import com.philkes.notallyx.presentation.widget.WidgetProvider.Companion.getWidgetCheckedChangeIntent
import com.philkes.notallyx.presentation.widget.WidgetProvider.Companion.getWidgetDeleteItemIntent
import com.philkes.notallyx.presentation.widget.WidgetProvider.Companion.getWidgetEditItemIntent
import com.philkes.notallyx.presentation.widget.WidgetProvider.Companion.getWidgetOpenNoteIntent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class WidgetFactory(
    private val app: NotallyXApplication,
    private val id: Long,
) : RemoteViewsService.RemoteViewsFactory, KoinComponent {

    private var baseNote: BaseNote? = null
    /** Row of the "Add item" entry within a list, see [WidgetListEdits.addPosition]. */
    private var addItemPosition = 0
    private var database: NotallyDatabase? = null
    private val preferences: NotallyXPreferences by inject()

    init {
        database = NotallyDatabase.getDatabase(app).value
    }

    override fun onCreate() {}

    override fun onDestroy() {}

    override fun getCount(): Int {
        val copy = baseNote
        return if (copy != null) {
            when (copy.type) {
                Type.NOTE -> if (copy.body.isEmpty()) 0 else 1
                Type.LIST -> copy.items.size + 1
            }
        } else 0
    }

    override fun onDataSetChanged() {
        database = NotallyDatabase.getDatabase(app).value
        baseNote = database?.getBaseNoteDao()?.get(id)
        addItemPosition =
            baseNote?.let {
                WidgetListEdits.addPosition(it.items, preferences.autoSortByCheckedEnabled)
            } ?: 0
    }

    override fun getViewAt(position: Int): RemoteViews {
        val copy = baseNote
        requireNotNull(copy, { "baseNote is null" })

        return when (copy.type) {
            Type.NOTE -> getNoteView(copy)
            Type.LIST ->
                when {
                    position == addItemPosition -> getAddItemView(copy)
                    position < addItemPosition -> getListItemView(position, copy)
                    else -> getListItemView(position - 1, copy)
                }
        }
    }

    private fun getNoteView(note: BaseNote): RemoteViews {
        return RemoteViews(app.packageName, R.layout.widget_note).apply {
            val bodyTextSize = preferences.textSizeNoteEditor.value.displayBodySize

            setTextViewTextSize(R.id.Note, TypedValue.COMPLEX_UNIT_SP, bodyTextSize)
            if (note.body.isNotEmpty()) {
                setTextViewText(R.id.Note, note.renderedBody(preferences.markdownBlankLineScale))
                setViewVisibility(R.id.Note, View.VISIBLE)
            } else setViewVisibility(R.id.Note, View.GONE)

            setOnClickFillInIntent(R.id.LinearLayout, getWidgetOpenNoteIntent(note.type, note.id))

            val (_, controlsColor) = app.extractWidgetColors(note.color, preferences)
            setTextColor(R.id.Note, controlsColor)
        }
    }

    private fun getListItemView(index: Int, list: BaseNote): RemoteViews {
        val item = list.items[index]
        val view =
            if (item.isChild) {
                RemoteViews(app.packageName, R.layout.widget_list_child_item)
            } else {
                RemoteViews(app.packageName, R.layout.widget_list_item)
            }
        return view.apply {
            val (_, controlsColor) = app.extractWidgetColors(list.color, preferences)
            // The box checks the entry, its text opens it for editing
            setListItemTextView(item, R.id.CheckBoxText, controlsColor)
            setOnClickFillInIntent(
                R.id.CheckBoxText,
                getWidgetEditItemIntent(list.id, index, item.body),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setCompoundButtonChecked(R.id.CheckBox, item.checked)
                val checkIntent = getWidgetCheckedChangeIntent(list.id, index)
                setOnCheckedChangeResponse(
                    R.id.CheckBox,
                    RemoteViews.RemoteResponse.fromFillInIntent(checkIntent),
                )
                setColorStateList(
                    R.id.CheckBox,
                    "setButtonTintList",
                    ColorStateList.valueOf(controlsColor),
                )
            } else {
                setImageViewResource(
                    R.id.CheckBox,
                    if (item.checked) R.drawable.checkbox_fill else R.drawable.checkbox_outline,
                )
                setOnClickFillInIntent(R.id.CheckBox, getWidgetCheckedChangeIntent(list.id, index))
                setImageViewColor(R.id.CheckBox, controlsColor)
            }
            setViewVisibility(R.id.DeleteItem, if (item.checked) View.VISIBLE else View.GONE)
            if (item.checked) {
                setImageViewColor(R.id.DeleteItem, controlsColor)
                setOnClickFillInIntent(
                    R.id.DeleteItem,
                    getWidgetDeleteItemIntent(list.id, index, item.body),
                )
            }
        }
    }

    private fun getAddItemView(list: BaseNote): RemoteViews {
        return RemoteViews(app.packageName, R.layout.widget_list_add_item).apply {
            val (_, controlsColor) = app.extractWidgetColors(list.color, preferences)
            setTextViewTextSize(
                R.id.AddItemText,
                TypedValue.COMPLEX_UNIT_SP,
                preferences.textSizeNoteEditor.value.displayBodySize,
            )
            setTextColor(R.id.AddItemText, controlsColor)
            setImageViewColor(R.id.AddItemIcon, controlsColor)
            setOnClickFillInIntent(R.id.AddItem, getWidgetAddItemIntent(list.id))
        }
    }

    private fun RemoteViews.setListItemTextView(item: ListItem, textViewId: Int, fontColor: Int) {
        setTextViewTextSize(
            textViewId,
            TypedValue.COMPLEX_UNIT_SP,
            preferences.textSizeNoteEditor.value.displayBodySize,
        )
        setTextViewText(textViewId, item.body)
        setInt(
            textViewId,
            "setPaintFlags",
            if (item.checked) {
                Paint.STRIKE_THRU_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG
            } else {
                Paint.ANTI_ALIAS_FLAG
            },
        )
        setInt(textViewId, "setTextColor", fontColor)
    }

    private fun RemoteViews.setImageViewColor(viewId: Int, color: Int) {
        setInt(viewId, "setColorFilter", color)
    }

    override fun getViewTypeCount() = 4

    override fun hasStableIds(): Boolean {
        return false
    }

    override fun getLoadingView(): RemoteViews? {
        return null
    }

    override fun getItemId(position: Int): Long {
        return 1
    }
}
