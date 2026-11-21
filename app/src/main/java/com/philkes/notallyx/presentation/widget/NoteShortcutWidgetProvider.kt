package com.philkes.notallyx.presentation.widget

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.philkes.notallyx.NotallyXApplication
import com.philkes.notallyx.R
import com.philkes.notallyx.data.NotallyDatabase
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.Type
import com.philkes.notallyx.presentation.activity.ConfigureNoteShortcutWidgetActivity
import com.philkes.notallyx.presentation.viewmodel.preference.NotallyXPreferences
import com.philkes.notallyx.presentation.widget.WidgetProvider.Companion.extractWidgetColors
import com.philkes.notallyx.presentation.widget.WidgetProvider.Companion.getWidgetOpenNotePendingIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 1x1 widget that opens one chosen note inside the app, with the notes overview below it. */
class NoteShortcutWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val app = context.applicationContext as NotallyXApplication
        val preferences = NotallyXPreferences.getInstance(app)
        appWidgetIds.forEach { id ->
            updateWidget(app, appWidgetManager, id, preferences.isLockEnabled && app.locked.value)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val preferences = NotallyXPreferences.getInstance(context.applicationContext as Application)
        appWidgetIds.forEach(preferences::deleteNoteShortcutWidget)
    }

    companion object {

        /** Refreshes the shortcuts showing one of [noteIds], or all of them if it is null. */
        fun updateWidgets(app: Application, noteIds: LongArray?, locked: Boolean) {
            val manager = AppWidgetManager.getInstance(app)
            val preferences = NotallyXPreferences.getInstance(app)
            manager
                .getAppWidgetIds(ComponentName(app, NoteShortcutWidgetProvider::class.java))
                .filter { noteIds == null || preferences.getNoteShortcutWidget(it) in noteIds }
                .forEach { updateWidget(app, manager, it, locked) }
        }

        fun updateWidget(app: Application, manager: AppWidgetManager, id: Int, locked: Boolean) {
            MainScope().launch { renderWidget(app, manager, id, locked) }
        }

        private suspend fun renderWidget(
            app: Application,
            manager: AppWidgetManager,
            id: Int,
            locked: Boolean,
        ) {
            val preferences = NotallyXPreferences.getInstance(app)
            val noteId = preferences.getNoteShortcutWidget(id)
            if (noteId == 0L) return
            val database = NotallyDatabase.getDatabase(app).value ?: return
            val header =
                withContext(Dispatchers.IO) {
                    database.getBaseNoteDao().getWidgetNoteHeader(noteId)
                }
            if (preferences.getNoteShortcutWidget(id) != noteId) return
            val (backgroundColor, contentColor) =
                app.extractWidgetColors(header?.color ?: BaseNote.COLOR_DEFAULT, preferences)
            val view =
                RemoteViews(app.packageName, R.layout.widget_note_shortcut).apply {
                    setInt(R.id.WidgetShortcutBackground, "setColorFilter", backgroundColor)
                    setInt(R.id.WidgetShortcutIcon, "setColorFilter", contentColor)
                    setTextColor(R.id.WidgetShortcutTitle, contentColor)
                    when {
                        header == null -> {
                            // The note is gone, a tap lets the user pick another one
                            setImageViewResource(R.id.WidgetShortcutIcon, R.drawable.notebook)
                            setTextViewText(
                                R.id.WidgetShortcutTitle,
                                app.getString(R.string.select_note),
                            )
                            setOnClickPendingIntent(
                                R.id.WidgetShortcutRoot,
                                PendingIntent.getActivity(
                                    app,
                                    id,
                                    Intent(app, ConfigureNoteShortcutWidgetActivity::class.java)
                                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                                        .setFlags(
                                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                                Intent.FLAG_ACTIVITY_CLEAR_TASK
                                        ),
                                    PendingIntent.FLAG_UPDATE_CURRENT or
                                        PendingIntent.FLAG_IMMUTABLE,
                                ),
                            )
                        }
                        else -> {
                            // A locked app hides the title, opening the note asks to unlock
                            setImageViewResource(
                                R.id.WidgetShortcutIcon,
                                when {
                                    locked -> R.drawable.lock_big
                                    header.type == Type.LIST -> R.drawable.checkbox
                                    else -> R.drawable.notebook
                                },
                            )
                            setTextViewText(
                                R.id.WidgetShortcutTitle,
                                when {
                                    locked -> app.getString(R.string.locked)
                                    header.title.isNotBlank() -> header.title
                                    header.type == Type.LIST ->
                                        app.getString(R.string.widget_untitled_list)
                                    else -> app.getString(R.string.widget_untitled_note)
                                },
                            )
                            setOnClickPendingIntent(
                                R.id.WidgetShortcutRoot,
                                app.getWidgetOpenNotePendingIntent(
                                    noteId,
                                    header.type,
                                    withOverview = true,
                                ),
                            )
                        }
                    }
                }
            manager.updateAppWidget(id, view)
        }
    }
}
