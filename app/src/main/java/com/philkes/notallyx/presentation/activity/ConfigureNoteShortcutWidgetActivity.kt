package com.philkes.notallyx.presentation.activity

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.presentation.activity.note.PickNoteActivity
import com.philkes.notallyx.presentation.widget.NoteShortcutWidgetProvider

/** Lets the user choose the note a 1x1 note shortcut widget opens. */
class ConfigureNoteShortcutWidgetActivity : PickNoteActivity() {

    private val id by lazy {
        intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
    }

    override fun onClick(position: Int) {
        if (position == -1 || id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val baseNote = adapter.getItem(position) as BaseNote
        preferences.setNoteShortcutWidget(id, baseNote.id)
        NoteShortcutWidgetProvider.updateWidget(
            application,
            AppWidgetManager.getInstance(this),
            id,
            locked = false,
        )
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }
}
