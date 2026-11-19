package com.philkes.notallyx.utils.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.ResultReceiver
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.philkes.notallyx.NotallyXApplication
import com.philkes.notallyx.R
import com.philkes.notallyx.presentation.activity.note.EditActivity.Companion.EXTRA_SELECTED_BASE_NOTE
import com.philkes.notallyx.presentation.showToast
import com.philkes.notallyx.presentation.viewmodel.preference.NotallyXPreferences
import com.philkes.notallyx.presentation.widget.WidgetProvider
import com.philkes.notallyx.utils.audio.Status.PAUSED
import com.philkes.notallyx.utils.audio.Status.RECORDING
import com.philkes.notallyx.utils.getTempAudioFile
import com.philkes.notallyx.utils.log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AudioRecordService : Service() {

    val status: StateFlow<Status>
        field = MutableStateFlow(Status.READY)
    private var lastStart = 0L
    private var audioDuration = 0L

    private var recorder: MediaRecorder? = null
    private val scope = MainScope()
    private var widgetFile: File? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_WIDGET -> startWidgetRecording(intent)
            ACTION_STOP_WIDGET ->
                stopWidgetRecording(intent.getLongExtra(EXTRA_STARTED_AT, -1), intent.widgetId())
            ACTION_DISCARD_WIDGET ->
                discardWidgetRecording(
                    intent.getLongExtra(EXTRA_STARTED_AT, -1),
                    intent.widgetId(),
                )
            else -> {
                if (recorder == null && widgetRecording == null) {
                    prepareEditorRecording()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun discardWidgetRecording(startedAt: Long, widgetId: Int) {
        val session = widgetRecording
        if (session?.startedAt == startedAt && !session.saving && status.value == RECORDING) {
            finishWidgetRecording()
        } else if (session == null) {
            refreshStaleWidget(widgetId)
        }
    }

    /**
     * Redraws a widget whose recording controls outlived their session, e.g. after the process was
     * killed mid-recording, and stops this otherwise idle service.
     */
    private fun refreshStaleWidget(widgetId: Int) {
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            WidgetProvider.refreshRecordingWidget(this, widgetId)
        }
        if (recorder == null) stopSelf()
    }

    private fun prepareEditorRecording() {
        try {
            prepare(getTempAudioFile())
        } catch (e: IOException) {
            log(TAG, throwable = e)
            stopSelf()
        } catch (e: RuntimeException) {
            log(TAG, throwable = e)
            stopSelf()
        }
    }

    private fun prepare(output: File) {
        val mediaRecorder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else MediaRecorder()

        recorder = mediaRecorder
        try {
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(output.path)
                prepare()
            }
        } catch (e: Exception) {
            releaseRecorder()
            throw e
        }
    }

    override fun onDestroy() {
        scope.cancel()
        releaseRecorder()
        widgetFile?.delete()
        clearWidgetRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = LocalBinder(this)

    val editorAvailable: Boolean
        get() = recorder != null && widgetRecording == null

    fun start() {
        checkNotNull(recorder).start()
        status.value = RECORDING
        lastStart = SystemClock.elapsedRealtime()
    }

    fun resume() {
        checkNotNull(recorder).resume()
        status.value = RECORDING
        lastStart = SystemClock.elapsedRealtime()
    }

    fun pause() {
        checkNotNull(recorder).pause()
        status.value = PAUSED
        audioDuration += SystemClock.elapsedRealtime() - lastStart
        lastStart = 0L
    }

    fun stop() {
        checkNotNull(recorder).stop()
        releaseRecorder()
        status.value = Status.READY
        stopSelf()
    }

    fun getBase(): Long {
        return if (lastStart != 0L) {
            lastStart - audioDuration
        } else SystemClock.elapsedRealtime() - audioDuration
    }

    private fun startWidgetRecording(intent: Intent) {
        val result =
            IntentCompat.getParcelableExtra(intent, EXTRA_RESULT, ResultReceiver::class.java)
        if (recorder != null || widgetRecording != null) {
            result?.send(WIDGET_BUSY, null)
            return
        }
        val widgetId = intent.widgetId()
        val noteId = intent.getLongExtra(EXTRA_SELECTED_BASE_NOTE, 0)
        val app = application as NotallyXApplication
        val preferences = NotallyXPreferences.getInstance(app)
        if (
            noteId <= 0 ||
                preferences.getWidgetData(widgetId) != noteId ||
                (preferences.isLockEnabled && app.locked.value)
        ) {
            result?.send(WIDGET_FAILED, null)
            stopSelf()
            return
        }
        try {
            deleteOrphanedWidgetRecordings()
            val file = File.createTempFile(WIDGET_FILE_PREFIX, ".m4a", cacheDir)
            widgetFile = file
            prepare(file)
            checkNotNull(recorder).setOnErrorListener { _, what, extra ->
                log(TAG, "Widget recorder error: $what / $extra")
                showToast(R.string.widget_recording_failed)
                finishWidgetRecording()
            }
            val session = WidgetRecording(widgetId, noteId, SystemClock.elapsedRealtime())
            showRecordingNotification(session)
            start()
            widgetRecording = session
            WidgetProvider.refreshRecordingWidget(this, widgetId)
            result?.send(WIDGET_STARTED, null)
        } catch (e: IOException) {
            failWidgetStart(e, result)
        } catch (e: RuntimeException) {
            failWidgetStart(e, result)
        }
    }

    private fun showRecordingNotification(session: WidgetRecording) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        RECORDING_CHANNEL,
                        getString(R.string.audio_recordings),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
        }
        val notification =
            NotificationCompat.Builder(this, RECORDING_CHANNEL)
                .setSmallIcon(R.drawable.record_audio)
                .setContentTitle(getString(R.string.recording))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setUsesChronometer(true)
                .addAction(
                    R.drawable.widget_stop_recording,
                    getString(R.string.stop),
                    session.stopIntent(this),
                )
                .build()
        ServiceCompat.startForeground(
            this,
            RECORDING_NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else 0,
        )
    }

    private fun failWidgetStart(error: Exception, result: ResultReceiver?) {
        log(TAG, throwable = error)
        result?.send(WIDGET_FAILED, null)
        finishWidgetRecording()
    }

    /** Removes partial recordings left behind when the process died mid-recording. */
    private fun deleteOrphanedWidgetRecordings() {
        cacheDir
            .listFiles { file -> file.name.startsWith(WIDGET_FILE_PREFIX) }
            ?.forEach { it.delete() }
    }

    private fun stopWidgetRecording(startedAt: Long, widgetId: Int) {
        val session = widgetRecording
        if (session == null) {
            refreshStaleWidget(widgetId)
            return
        }
        if (session.startedAt != startedAt || session.saving || status.value != RECORDING) return
        try {
            checkNotNull(recorder).stop()
        } catch (e: RuntimeException) {
            log(TAG, throwable = e)
            showToast(R.string.widget_recording_failed)
            finishWidgetRecording()
            return
        }
        releaseRecorder()
        status.value = Status.READY
        widgetRecording = session.copy(saving = true)
        WidgetProvider.refreshRecordingWidget(this, session.widgetId)
        val file = checkNotNull(widgetFile)
        scope.launch {
            try {
                application.saveWidgetRecording(session.noteId, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, throwable = e)
                showToast(R.string.widget_recording_failed)
            } finally {
                finishWidgetRecording()
            }
        }
    }

    private fun finishWidgetRecording() {
        releaseRecorder()
        status.value = Status.READY
        widgetFile?.delete()
        widgetFile = null
        clearWidgetRecording()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseRecorder() {
        recorder?.release()
        recorder = null
    }

    private fun clearWidgetRecording() {
        val recording = widgetRecording ?: return
        widgetRecording = null
        WidgetProvider.refreshRecordingWidget(this, recording.widgetId)
    }

    /** @property saving `true` once the recording was stopped and is being attached. */
    internal data class WidgetRecording(
        val widgetId: Int,
        val noteId: Long,
        val startedAt: Long,
        val saving: Boolean = false,
    ) {
        fun stopIntent(context: Context): PendingIntent =
            PendingIntent.getService(
                context,
                widgetId,
                Intent(context, AudioRecordService::class.java).apply {
                    action = ACTION_STOP_WIDGET
                    data = Uri.fromParts("notallyx-recording", startedAt.toString(), null)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    putExtra(EXTRA_STARTED_AT, startedAt)
                },
                PendingIntent.FLAG_IMMUTABLE,
            )
    }

    companion object {
        private const val TAG = "AudioRecordService"
        private const val ACTION_START_WIDGET = "com.philkes.notallyx.action.START_WIDGET_RECORDING"
        private const val ACTION_STOP_WIDGET = "com.philkes.notallyx.action.STOP_WIDGET_RECORDING"
        private const val ACTION_DISCARD_WIDGET =
            "com.philkes.notallyx.action.DISCARD_WIDGET_RECORDING"
        private const val EXTRA_RESULT = "recordingResult"
        private const val EXTRA_STARTED_AT = "recordingStartedAt"
        private const val RECORDING_NOTIFICATION_ID = 10101
        private const val RECORDING_CHANNEL = "widget_recording"
        private const val WIDGET_FILE_PREFIX = "widget-recording-"

        internal const val WIDGET_STARTED = 1
        internal const val WIDGET_BUSY = 2
        private const val WIDGET_FAILED = 3

        internal var widgetRecording: WidgetRecording? = null
            private set

        internal fun widgetDiscardIntent(context: Context, widgetId: Int, startedAt: Long): Intent =
            Intent(context, AudioRecordService::class.java).apply {
                action = ACTION_DISCARD_WIDGET
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                putExtra(EXTRA_STARTED_AT, startedAt)
            }

        private fun Intent.widgetId() =
            getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

        internal fun widgetStartIntent(
            context: Context,
            widgetId: Int,
            noteId: Long,
            result: ResultReceiver,
        ): Intent =
            Intent(context, AudioRecordService::class.java).apply {
                action = ACTION_START_WIDGET
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                putExtra(EXTRA_SELECTED_BASE_NOTE, noteId)
                putExtra(EXTRA_RESULT, result)
            }
    }
}
