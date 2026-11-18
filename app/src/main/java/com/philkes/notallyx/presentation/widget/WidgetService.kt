package com.philkes.notallyx.presentation.widget

import android.content.Intent
import android.widget.RemoteViewsService
import com.philkes.notallyx.NotallyXApplication
import com.philkes.notallyx.presentation.activity.note.EditActivity.Companion.EXTRA_SELECTED_BASE_NOTE

class WidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val id = intent.getLongExtra(EXTRA_SELECTED_BASE_NOTE, 0)
        return WidgetFactory(application as NotallyXApplication, id)
    }
}
