package com.philkes.notallyx.presentation.widget

import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.RemoteViews
import android.widget.RemoteViewsService.RemoteViewsFactory
import com.philkes.notallyx.NotallyXApplication
import com.philkes.notallyx.R
import com.philkes.notallyx.data.NotallyDatabase
import com.philkes.notallyx.data.dao.WidgetNoteChoice
import com.philkes.notallyx.data.model.Type
import com.philkes.notallyx.presentation.activity.note.EditActivity.Companion.EXTRA_SELECTED_BASE_NOTE
import com.philkes.notallyx.presentation.viewmodel.preference.NotallyXPreferences
import com.philkes.notallyx.presentation.widget.WidgetProvider.Companion.extractWidgetColors

internal class WidgetNoteChoicesFactory(
    private val app: NotallyXApplication,
    private val widgetId: Int,
) : RemoteViewsFactory {

    private val preferences = NotallyXPreferences.getInstance(app)
    private var choices = emptyList<WidgetNoteChoice>()
    private var selectedId = 0L
    private var textColor = 0

    override fun onCreate() {}

    override fun onDataSetChanged() {
        choices = emptyList()
        selectedId = preferences.getWidgetData(widgetId)
        if (selectedId == 0L || (preferences.isLockEnabled && app.locked.value)) return
        val dao = NotallyDatabase.getDatabase(app).value?.getBaseNoteDao() ?: return
        val header = dao.getWidgetNoteHeader(selectedId) ?: return
        textColor = app.extractWidgetColors(header.color, preferences).second
        choices = dao.getWidgetNoteChoices()
    }

    override fun onDestroy() {
        choices = emptyList()
    }

    override fun getCount() = choices.size

    override fun getViewAt(position: Int): RemoteViews? {
        if (preferences.isLockEnabled && app.locked.value) return null
        val choice = choices.getOrNull(position) ?: return null
        val title =
            choice.title.ifBlank {
                app.getString(
                    if (choice.type == Type.LIST) R.string.widget_untitled_list
                    else R.string.widget_untitled_note
                )
            }
        val label =
            if (choice.id == selectedId) {
                SpannableString(title).apply {
                    setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else title
        return RemoteViews(app.packageName, R.layout.widget_note_choice).apply {
            setTextViewText(R.id.WidgetNoteChoice, label)
            setTextColor(R.id.WidgetNoteChoice, textColor)
            setOnClickFillInIntent(
                R.id.WidgetNoteChoice,
                Intent().putExtra(EXTRA_SELECTED_BASE_NOTE, choice.id),
            )
        }
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount() = 1

    override fun getItemId(position: Int) = choices[position].id

    override fun hasStableIds() = true
}
