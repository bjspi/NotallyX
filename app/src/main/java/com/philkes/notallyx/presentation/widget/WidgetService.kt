package com.philkes.notallyx.presentation.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.widget.RemoteViewsService
import com.philkes.notallyx.NotallyXApplication
import com.philkes.notallyx.presentation.activity.note.EditActivity.Companion.EXTRA_SELECTED_BASE_NOTE

class WidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val app = application as NotallyXApplication
        if (intent.action == ACTION_NOTE_CHOICES) {
            return WidgetNoteChoicesFactory(
                app,
                intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID,
                ),
            )
        }
        val id = intent.getLongExtra(EXTRA_SELECTED_BASE_NOTE, 0)
        return WidgetFactory(app, id)
    }

    companion object {
        internal const val ACTION_NOTE_CHOICES = "com.philkes.notallyx.ACTION_WIDGET_NOTE_CHOICES"
    }
}
