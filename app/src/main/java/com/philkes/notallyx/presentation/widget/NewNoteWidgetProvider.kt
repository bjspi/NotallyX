package com.philkes.notallyx.presentation.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.philkes.notallyx.R
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.presentation.viewmodel.preference.NotallyXPreferences
import com.philkes.notallyx.presentation.widget.WidgetProvider.Companion.extractWidgetColors
import com.philkes.notallyx.presentation.widget.WidgetProvider.Companion.getWidgetNewNotePendingIntent

/** 1x1 widget that starts a new text note, with the notes overview below it. */
class NewNoteWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val app = context.applicationContext as Application
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, createView(app)) }
    }

    companion object {

        /** Re-tints all instances, e.g. after a theme change. */
        fun updateWidgets(app: Application) {
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, NewNoteWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val view = createView(app)
            ids.forEach { manager.updateAppWidget(it, view) }
        }

        private fun createView(app: Application): RemoteViews {
            val preferences = NotallyXPreferences.getInstance(app)
            val (backgroundColor, contentColor) =
                app.extractWidgetColors(BaseNote.COLOR_DEFAULT, preferences)
            return RemoteViews(app.packageName, R.layout.widget_new_note).apply {
                setInt(R.id.WidgetShortcutBackground, "setColorFilter", backgroundColor)
                setInt(R.id.WidgetShortcutIcon, "setColorFilter", contentColor)
                setOnClickPendingIntent(
                    R.id.WidgetShortcutRoot,
                    app.getWidgetNewNotePendingIntent(),
                )
            }
        }
    }
}
