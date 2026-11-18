package com.philkes.notallyx.presentation.widget

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.philkes.notallyx.NotallyXApplication
import com.philkes.notallyx.R
import com.philkes.notallyx.data.NotallyDatabase
import com.philkes.notallyx.data.dao.BaseNoteDao
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.Type
import com.philkes.notallyx.presentation.activity.ConfigureWidgetActivity
import com.philkes.notallyx.presentation.activity.note.EditActivity.Companion.EXTRA_SELECTED_BASE_NOTE
import com.philkes.notallyx.presentation.activity.note.EditListActivity
import com.philkes.notallyx.presentation.activity.note.EditNoteActivity
import com.philkes.notallyx.presentation.extractColor
import com.philkes.notallyx.presentation.getContrastFontColor
import com.philkes.notallyx.presentation.view.note.listitem.findChildrenPositions
import com.philkes.notallyx.presentation.view.note.listitem.findParentPosition
import com.philkes.notallyx.presentation.viewmodel.preference.NotallyXPreferences
import com.philkes.notallyx.presentation.viewmodel.preference.Theme
import com.philkes.notallyx.presentation.viewmodel.preference.displayTitleSize
import com.philkes.notallyx.utils.audio.AudioRecordService
import com.philkes.notallyx.utils.embedIntentExtras
import com.philkes.notallyx.utils.getOpenNotePendingIntent
import com.philkes.notallyx.utils.isSystemInDarkMode
import com.philkes.notallyx.utils.log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_NOTES_MODIFIED,
            Intent.ACTION_LOCALE_CHANGED -> {
                val app = context.applicationContext as NotallyXApplication
                val preferences = NotallyXPreferences.getInstance(app)
                val noteIds = intent.getLongArrayExtra(EXTRA_MODIFIED_NOTES)
                if (noteIds != null) {
                    updateWidgets(
                        context,
                        noteIds,
                        locked = preferences.isLockEnabled && app.locked.value,
                    )
                }
            }
            ACTION_OPEN_NOTE -> openActivity(context, intent, EditNoteActivity::class.java)
            ACTION_OPEN_LIST -> openActivity(context, intent, EditListActivity::class.java)
            ACTION_CHECKED_CHANGED -> checkChanged(intent, context)
            ACTION_SELECT_NOTE -> openActivity(context, intent, ConfigureWidgetActivity::class.java)
            ACTION_TOGGLE_NOTE_CHOICES,
            ACTION_PICK_WIDGET_NOTE -> handleNotePicker(context, intent)
        }
    }

    private fun handleNotePicker(context: Context, intent: Intent) {
        val app = context.applicationContext as NotallyXApplication
        val preferences = NotallyXPreferences.getInstance(app)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0)
        val currentNote = preferences.getWidgetData(widgetId)
        if (currentNote == 0L || (preferences.isLockEnabled && app.locked.value)) return
        if (
            intent.action == ACTION_PICK_WIDGET_NOTE &&
                intent.getLongExtra(EXTRA_PICKER_NOTE, 0) != currentNote
        )
            return

        val pendingResult = goAsync()
        MainScope().launch {
            try {
                val noteId =
                    if (intent.action == ACTION_PICK_WIDGET_NOTE) {
                        val choice = intent.getLongExtra(EXTRA_SELECTED_BASE_NOTE, 0)
                        val type =
                            NotallyDatabase.getDatabase(app)
                                .value
                                ?.getBaseNoteDao()
                                ?.getWidgetNoteChoiceType(choice)
                        if (
                            preferences.getWidgetData(widgetId) != currentNote ||
                                (preferences.isLockEnabled && app.locked.value)
                        )
                            return@launch
                        openNotePickers.remove(widgetId)
                        if (type == null) currentNote
                        else {
                            if (choice != currentNote)
                                preferences.updateWidget(widgetId, choice, type)
                            choice
                        }
                    } else {
                        if (intent.getBooleanExtra(EXTRA_OPEN_NOTE_PICKER, false)) {
                            openNotePickers[widgetId] = currentNote
                        } else {
                            openNotePickers.remove(widgetId)
                        }
                        currentNote
                    }
                renderWidget(app, AppWidgetManager.getInstance(app), widgetId, noteId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.log("WidgetProvider", throwable = e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun checkChanged(intent: Intent, context: Context) {
        val noteId = intent.getLongExtra(EXTRA_SELECTED_BASE_NOTE, 0)
        val position = intent.getIntExtra(EXTRA_POSITION, 0)
        var checked =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                intent.getBooleanExtra(RemoteViews.EXTRA_CHECKED, false)
            } else null
        val database = NotallyDatabase.getDatabase(context.applicationContext as Application).value
        val pendingResult = goAsync()
        GlobalScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val baseNoteDao = database?.getBaseNoteDao() ?: return@withContext
                    val note = baseNoteDao.get(noteId)!!
                    val item = note.items[position]
                    if (checked == null) {
                        checked = !item.checked
                    }
                    if (item.isChild) {
                        changeChildChecked(note, position, checked!!, baseNoteDao, noteId)
                    } else {
                        val childrenPositions = note.items.findChildrenPositions(position)
                        baseNoteDao.updateChecked(noteId, childrenPositions + position, checked!!)
                    }
                } finally {
                    val app = context.applicationContext as NotallyXApplication
                    val preferences = NotallyXPreferences.getInstance(app)
                    updateWidgets(
                        context,
                        longArrayOf(noteId),
                        locked = preferences.isLockEnabled && app.locked.value,
                    )
                    pendingResult.finish()
                }
            }
        }
    }

    private suspend fun changeChildChecked(
        note: BaseNote,
        childPosition: Int,
        checked: Boolean,
        baseNoteDao: BaseNoteDao,
        noteId: Long,
    ) {
        val parentPosition = note.items.findParentPosition(childPosition)!!
        val parent = note.items[parentPosition]
        val childrenPositions = note.items.findChildrenPositions(parentPosition)
        if (parent.checked != checked) {
            if (checked) {
                // If the last unchecked child is being checked also check parent
                if (childrenPositions.none { !note.items[it].checked && it != childPosition }) {
                    baseNoteDao.updateChecked(noteId, listOf(childPosition, parentPosition), true)
                } else {
                    baseNoteDao.updateChecked(noteId, childPosition, true)
                }
            } else {
                if (parent.checked) {
                    // If any child is unchecked the parent is unchecked too
                    baseNoteDao.updateChecked(noteId, listOf(childPosition, parentPosition), false)
                } else {
                    baseNoteDao.updateChecked(noteId, childPosition, false)
                }
            }
        } else {
            baseNoteDao.updateChecked(noteId, childPosition, false)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val app = context.applicationContext as Application
        val preferences = NotallyXPreferences.getInstance(app)

        appWidgetIds.forEach { id ->
            openNotePickers.remove(id)
            preferences.deleteWidget(id)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val app = context.applicationContext as NotallyXApplication
        val preferences = NotallyXPreferences.getInstance(app)

        appWidgetIds.forEach { id ->
            val noteId = preferences.getWidgetData(id)
            updateWidget(
                app,
                appWidgetManager,
                id,
                noteId,
                locked = preferences.isLockEnabled && app.locked.value,
            )
        }
    }

    companion object {

        private val openNotePickers = ConcurrentHashMap<Int, Long>()

        fun updateWidgets(context: Context, noteIds: LongArray? = null, locked: Boolean) {
            val app = context.applicationContext as Application
            val preferences = NotallyXPreferences.getInstance(app)

            val manager = AppWidgetManager.getInstance(context)
            val updatableWidgets =
                preferences.getUpdatableWidgets().filter { (id, noteId) ->
                    noteIds == null || noteId in noteIds || openNotePickers.containsKey(id)
                }

            updatableWidgets.forEach { (id, noteId) ->
                updateWidget(
                    app,
                    manager,
                    id,
                    noteId,
                    locked = locked,
                )
            }
        }

        fun updateWidget(
            context: ContextWrapper,
            manager: AppWidgetManager,
            id: Int,
            noteId: Long,
            locked: Boolean = false,
        ) {
            MainScope().launch { renderWidget(context, manager, id, noteId, locked) }
        }

        private suspend fun renderWidget(
            context: ContextWrapper,
            manager: AppWidgetManager,
            id: Int,
            noteId: Long,
            locked: Boolean = false,
        ) {
            // Widgets displaying the same note share the same factory since only the noteId is
            // embedded
            val intent = Intent(context, WidgetService::class.java)
            intent.putExtra(EXTRA_SELECTED_BASE_NOTE, noteId)
            intent.embedIntentExtras()

            val database = NotallyDatabase.getDatabase(context).value ?: return
            val header =
                withContext(Dispatchers.IO) {
                    database.getBaseNoteDao().getWidgetNoteHeader(noteId)
                }
            val app = context.applicationContext as NotallyXApplication
            val preferences = NotallyXPreferences.getInstance(app)
            if (preferences.getWidgetData(id) != noteId) return
            if (header == null) {
                preferences.deleteWidget(id)
            }
            if (header == null || openNotePickers[id] != noteId) openNotePickers.remove(id)
            if (!locked && !(preferences.isLockEnabled && app.locked.value)) {
                val showNoteChoices = openNotePickers.containsKey(id)
                val selectNote =
                    PendingIntent.getActivity(
                        context,
                        id,
                        createIntent(context, ConfigureWidgetActivity::class.java, noteId, id),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                val view =
                    RemoteViews(context.packageName, R.layout.widget).apply {
                        setRemoteAdapter(R.id.ListView, intent)
                        setViewVisibility(
                            R.id.ListView,
                            if (header == null || showNoteChoices) View.GONE else View.VISIBLE,
                        )
                        setViewVisibility(
                            R.id.Empty,
                            if (header == null) View.VISIBLE else View.GONE,
                        )
                        setOnClickPendingIntent(R.id.Empty, selectNote)
                        setPendingIntentTemplate(
                            R.id.ListView,
                            Intent(context, WidgetProvider::class.java).asPendingIntent(context),
                        )

                        val (backgroundColor, controlsColor) =
                            context.extractWidgetColors(
                                header?.color ?: BaseNote.COLOR_DEFAULT,
                                preferences,
                            )
                        setInt(R.id.Layout, "setBackgroundColor", backgroundColor)
                        setTextColor(R.id.WidgetTitle, controlsColor)
                        setInt(R.id.ChangeNote, "setColorFilter", controlsColor)
                        setInt(R.id.WidgetVoice, "setColorFilter", controlsColor)
                        setInt(R.id.WidgetDiscardRecording, "setColorFilter", controlsColor)
                        setTextColor(R.id.WidgetRecordingTimer, controlsColor)
                        if (header != null) {
                            val openNote = context.getOpenNotePendingIntent(noteId, header.type)
                            setOnClickPendingIntent(R.id.Layout, openNote)
                            setOnClickPendingIntent(R.id.WidgetTitle, openNote)
                            setTextViewText(R.id.WidgetTitle, header.title)
                            setTextViewTextSize(
                                R.id.WidgetTitle,
                                TypedValue.COMPLEX_UNIT_SP,
                                preferences.textSizeNoteEditor.value.displayTitleSize,
                            )
                        } else {
                            setOnClickPendingIntent(R.id.Layout, selectNote)
                            setOnClickPendingIntent(R.id.WidgetTitle, null)
                            setTextViewText(R.id.WidgetTitle, "")
                        }
                        setOnClickPendingIntent(R.id.ChangeNote, selectNote)
                        setNotePicker(
                            context,
                            id,
                            noteId.takeIf { header != null },
                            showNoteChoices,
                            controlsColor,
                        )
                        setRecordingControls(context, id, noteId.takeIf { header != null })
                    }
                manager.updateAppWidget(id, view)
                manager.notifyAppWidgetViewDataChanged(id, R.id.ListView)
                if (showNoteChoices) {
                    manager.notifyAppWidgetViewDataChanged(id, R.id.WidgetNoteChoices)
                }
            } else {
                openNotePickers.remove(id)
                val view =
                    RemoteViews(context.packageName, R.layout.widget_locked).apply {
                        header?.let {
                            val lockedPendingIntent =
                                context.getOpenNotePendingIntent(noteId, it.type)
                            setOnClickPendingIntent(R.id.Layout, lockedPendingIntent)
                            setOnClickPendingIntent(R.id.Text, lockedPendingIntent)
                        }
                        setTextViewCompoundDrawablesRelative(
                            R.id.Text,
                            0,
                            R.drawable.lock_big,
                            0,
                            0,
                        )
                    }
                manager.updateAppWidget(id, view)
            }
        }

        internal fun RemoteViews.setNotePicker(
            context: Context,
            widgetId: Int,
            noteId: Long?,
            open: Boolean,
            textColor: Int,
        ) {
            setViewVisibility(
                R.id.WidgetNoteDropdown,
                if (noteId != null) View.VISIBLE else View.GONE,
            )
            setViewVisibility(R.id.WidgetNotePicker, if (open) View.VISIBLE else View.GONE)
            if (noteId == null) return
            setInt(R.id.WidgetNoteDropdown, "setColorFilter", textColor)
            setContentDescription(
                R.id.WidgetNoteDropdown,
                context.getString(
                    if (open) R.string.widget_close_note_picker else R.string.widget_switch_note
                ),
            )
            setOnClickPendingIntent(
                R.id.WidgetNoteDropdown,
                PendingIntent.getBroadcast(
                    context,
                    widgetId,
                    Intent(context, WidgetProvider::class.java)
                        .setAction(ACTION_TOGGLE_NOTE_CHOICES)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                        .putExtra(EXTRA_OPEN_NOTE_PICKER, !open)
                        .apply { embedIntentExtras() },
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            if (!open) return
            val adapterIntent =
                Intent(context, WidgetService::class.java)
                    .setAction(WidgetService.ACTION_NOTE_CHOICES)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .apply { embedIntentExtras() }
            setRemoteAdapter(R.id.WidgetNoteChoices, adapterIntent)
            setEmptyView(R.id.WidgetNoteChoices, R.id.WidgetNoNotes)
            setTextColor(R.id.WidgetNoNotes, textColor)
            setPendingIntentTemplate(
                R.id.WidgetNoteChoices,
                PendingIntent.getBroadcast(
                    context,
                    widgetId,
                    Intent(context, WidgetProvider::class.java)
                        .setAction(ACTION_PICK_WIDGET_NOTE)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                        .putExtra(EXTRA_PICKER_NOTE, noteId)
                        .apply { embedIntentExtras() },
                    PendingIntent.FLAG_MUTABLE,
                ),
            )
        }

        internal fun RemoteViews.setRecordingControls(
            context: Context,
            widgetId: Int,
            noteId: Long?,
            session: AudioRecordService.WidgetRecording? = AudioRecordService.widgetRecording,
        ) {
            val recording = session?.takeIf { it.widgetId == widgetId }
            setViewVisibility(
                R.id.WidgetControls,
                if (noteId == null && recording == null) View.GONE else View.VISIBLE,
            )
            setViewVisibility(
                R.id.WidgetRecordingTimer,
                if (recording == null) View.GONE else View.VISIBLE,
            )
            setViewVisibility(
                R.id.WidgetDiscardRecording,
                if (recording == null) View.GONE else View.VISIBLE,
            )
            setOnClickPendingIntent(
                R.id.WidgetDiscardRecording,
                recording?.let { WidgetRecordActivity.discardConfirmationIntent(context, it) },
            )
            if (recording != null) {
                setChronometer(R.id.WidgetRecordingTimer, recording.startedAt, null, true)
                setImageViewResource(R.id.WidgetVoice, R.drawable.widget_stop_recording)
                setContentDescription(R.id.WidgetVoice, context.getString(R.string.stop))
                setOnClickPendingIntent(R.id.WidgetVoice, recording.stopIntent(context))
            } else {
                setChronometer(R.id.WidgetRecordingTimer, 0, null, false)
                setImageViewResource(R.id.WidgetVoice, R.drawable.record_audio)
                setContentDescription(
                    R.id.WidgetVoice,
                    context.getString(R.string.widget_voice_plus),
                )
                if (noteId != null) {
                    setOnClickPendingIntent(
                        R.id.WidgetVoice,
                        PendingIntent.getActivity(
                            context,
                            widgetId,
                            Intent(context, WidgetRecordActivity::class.java).apply {
                                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                                putExtra(EXTRA_SELECTED_BASE_NOTE, noteId)
                            },
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
            }
        }

        internal fun refreshRecordingWidget(context: Context, widgetId: Int) {
            val app = context.applicationContext as NotallyXApplication
            val preferences = NotallyXPreferences.getInstance(app)
            updateWidget(
                app,
                AppWidgetManager.getInstance(app),
                widgetId,
                preferences.getWidgetData(widgetId),
                locked = preferences.isLockEnabled && app.locked.value,
            )
        }

        fun getWidgetOpenNoteIntent(noteType: Type, noteId: Long): Intent {
            return Intent().setOpenNoteIntent(noteType, noteId)
        }

        fun getWidgetCheckedChangeIntent(listNoteId: Long, position: Int): Intent {
            return Intent().apply {
                action = ACTION_CHECKED_CHANGED
                putExtra(EXTRA_POSITION, position)
                putExtra(EXTRA_SELECTED_BASE_NOTE, listNoteId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
        }

        private fun Intent.setOpenNoteIntent(noteType: Type, noteId: Long) = apply {
            action =
                when (noteType) {
                    Type.LIST -> ACTION_OPEN_LIST
                    Type.NOTE -> ACTION_OPEN_NOTE
                }
            putExtra(EXTRA_SELECTED_BASE_NOTE, noteId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }

        private fun Intent.asPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                this,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )

        fun Context.extractWidgetColors(
            color: String,
            preferences: NotallyXPreferences,
        ): Pair<Int, Int> {
            val backgroundColor =
                if (color == BaseNote.COLOR_DEFAULT) {
                    val id =
                        when (preferences.theme.value) {
                            Theme.DARK -> R.color.ContainerDark
                            Theme.SUPER_DARK -> R.color.ContainerSuperDark
                            Theme.LIGHT -> R.color.ContainerLight
                            Theme.FOLLOW_SYSTEM -> {
                                if (isSystemInDarkMode()) R.color.ContainerDark
                                else R.color.ContainerLight
                            }
                        }
                    ContextCompat.getColor(this, id)
                } else extractColor(color)
            return Pair(backgroundColor, getContrastFontColor(backgroundColor))
        }

        private fun openActivity(context: Context, originalIntent: Intent, clazz: Class<*>) {
            val id = originalIntent.getLongExtra(EXTRA_SELECTED_BASE_NOTE, 0)
            val widgetId = originalIntent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0)
            context.startActivity(createIntent(context, clazz, id, widgetId, originalIntent))
        }

        private fun createIntent(
            context: Context,
            clazz: Class<*>,
            noteId: Long,
            widgetId: Int,
            originalIntent: Intent? = null,
        ): Intent {
            val intent = Intent(context, clazz)
            intent.putExtra(EXTRA_SELECTED_BASE_NOTE, noteId)
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            originalIntent?.let { intent.data = it.data }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            return intent
        }

        fun sendBroadcast(context: Context, ids: LongArray) =
            Intent(context, WidgetProvider::class.java).apply {
                action = ACTION_NOTES_MODIFIED
                putExtra(EXTRA_MODIFIED_NOTES, ids)
                context.sendBroadcast(this)
            }

        private const val EXTRA_MODIFIED_NOTES = "com.philkes.notallyx.EXTRA_MODIFIED_NOTES"
        private const val ACTION_NOTES_MODIFIED = "com.philkes.notallyx.ACTION_NOTE_MODIFIED"
        private const val EXTRA_PICKER_NOTE = "com.philkes.notallyx.EXTRA_PICKER_NOTE"
        private const val EXTRA_OPEN_NOTE_PICKER = "com.philkes.notallyx.EXTRA_OPEN_NOTE_PICKER"

        const val ACTION_OPEN_NOTE = "com.philkes.notallyx.ACTION_OPEN_NOTE"
        const val ACTION_OPEN_LIST = "com.philkes.notallyx.ACTION_OPEN_LIST"
        const val ACTION_SELECT_NOTE = "com.philkes.notallyx.ACTION_SELECT_NOTE"

        const val ACTION_CHECKED_CHANGED = "com.philkes.notallyx.ACTION_CHECKED_CHANGED"
        internal const val ACTION_TOGGLE_NOTE_CHOICES =
            "com.philkes.notallyx.ACTION_TOGGLE_NOTE_CHOICES"
        internal const val ACTION_PICK_WIDGET_NOTE = "com.philkes.notallyx.ACTION_PICK_WIDGET_NOTE"
        const val EXTRA_POSITION = "notallyx.intent.extra.com.philkes.notallyx.EXTRA_POSITION"
    }
}
