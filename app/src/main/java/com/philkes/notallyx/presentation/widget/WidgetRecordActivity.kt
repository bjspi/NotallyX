package com.philkes.notallyx.presentation.widget

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.philkes.notallyx.NotallyXApplication
import com.philkes.notallyx.R
import com.philkes.notallyx.data.NotallyDatabase
import com.philkes.notallyx.presentation.activity.note.EditActivity.Companion.EXTRA_SELECTED_BASE_NOTE
import com.philkes.notallyx.presentation.showToast
import com.philkes.notallyx.presentation.viewmodel.preference.NotallyXPreferences
import com.philkes.notallyx.utils.audio.AudioRecordService
import com.philkes.notallyx.utils.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WidgetRecordActivity : ComponentActivity() {
    private var permissionRequested = false
    private var starting = false

    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startRecording()
            } else {
                showToast(R.string.to_record_audio)
                finish()
            }
        }

    override fun onPostResume() {
        super.onPostResume()
        if (intent.action == ACTION_CONFIRM_DISCARD) return
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            startRecording()
        } else if (!permissionRequested) {
            permissionRequested = true
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this))
        permissionRequested = savedInstanceState?.getBoolean(REQUESTED) == true
        if (intent.action == ACTION_CONFIRM_DISCARD) confirmDiscard()
    }

    private fun confirmDiscard() {
        val startedAt = intent.getLongExtra(EXTRA_STARTED_AT, -1)
        val widgetId =
            intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
        val recording = AudioRecordService.widgetRecording
        if (
            recording?.startedAt != startedAt || recording.widgetId != widgetId || recording.saving
        ) {
            // Redraw controls left over from a recording that already ended or whose process died.
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                WidgetProvider.refreshRecordingWidget(this, widgetId)
            }
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.widget_discard_recording_title)
            .setMessage(R.string.widget_discard_recording_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.discard) { _, _ ->
                try {
                    startService(AudioRecordService.widgetDiscardIntent(this, widgetId, startedAt))
                } catch (e: IllegalStateException) {
                    log(TAG, throwable = e)
                    fail()
                }
            }
            .setOnDismissListener { finish() }
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(REQUESTED, permissionRequested)
    }

    private fun startRecording() {
        if (starting || isFinishing) return
        starting = true
        lifecycleScope.launch {
            val app = application as NotallyXApplication
            val preferences = NotallyXPreferences.getInstance(app)
            val widgetId =
                intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID,
                )
            val noteId = intent.getLongExtra(EXTRA_SELECTED_BASE_NOTE, 0)
            if (
                noteId <= 0 ||
                    preferences.getWidgetData(widgetId) != noteId ||
                    (preferences.isLockEnabled && app.locked.value)
            ) {
                finish()
                return@launch
            }
            try {
                val exists =
                    withContext(Dispatchers.IO) {
                        NotallyDatabase.getDatabase(app)
                            .value
                            ?.getBaseNoteDao()
                            ?.getColorOfNote(noteId) != null
                    }
                if (!exists) {
                    fail()
                    return@launch
                }
                val result =
                    object : ResultReceiver(Handler(Looper.getMainLooper())) {
                        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                            when (resultCode) {
                                AudioRecordService.WIDGET_STARTED -> finish()
                                AudioRecordService.WIDGET_BUSY -> {
                                    showToast(R.string.widget_recording_busy)
                                    finish()
                                }
                                else -> fail()
                            }
                        }
                    }
                lifecycle.withResumed {
                    if (
                        startService(
                            AudioRecordService.widgetStartIntent(app, widgetId, noteId, result)
                        ) == null
                    ) {
                        fail()
                    }
                }
            } catch (e: IllegalStateException) {
                log(TAG, throwable = e)
                fail()
            } catch (e: SecurityException) {
                log(TAG, throwable = e)
                fail()
            }
        }
    }

    private fun fail() {
        showToast(R.string.widget_recording_failed)
        finish()
    }

    companion object {
        private const val TAG = "WidgetRecordActivity"
        private const val REQUESTED = "requested"
        private const val ACTION_CONFIRM_DISCARD = "com.philkes.notallyx.action.CONFIRM_DISCARD"
        private const val EXTRA_STARTED_AT = "recordingStartedAt"

        internal fun discardConfirmationIntent(
            context: Context,
            recording: AudioRecordService.WidgetRecording,
        ): PendingIntent =
            PendingIntent.getActivity(
                context,
                recording.widgetId,
                Intent(context, WidgetRecordActivity::class.java).apply {
                    action = ACTION_CONFIRM_DISCARD
                    data = Uri.fromParts("notallyx-recording", recording.startedAt.toString(), null)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, recording.widgetId)
                    putExtra(EXTRA_STARTED_AT, recording.startedAt)
                },
                PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
