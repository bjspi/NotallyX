package com.philkes.notallyx.presentation.widget

import android.os.ResultReceiver
import androidx.appcompat.app.AlertDialog
import com.philkes.notallyx.data.imports.google.GoogleKeepImporterTest.Companion.createBaseNote
import com.philkes.notallyx.data.model.Type
import com.philkes.notallyx.test.app
import com.philkes.notallyx.test.database
import com.philkes.notallyx.test.preferences
import com.philkes.notallyx.utils.audio.AudioRecordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetDiscardRecordingTest {
    @Test
    fun cancelKeepsRecordingAndConfirmDiscardsItWithoutSaving() {
        val widgetId = 23
        val dao = database.getBaseNoteDao()
        val noteId = runBlocking(Dispatchers.IO) { dao.insert(createBaseNote(title = "Voice")) }
        preferences.updateWidget(widgetId, noteId, Type.NOTE)
        val serviceController = Robolectric.buildService(AudioRecordService::class.java).create()
        try {
            val service = serviceController.get()
            service.onStartCommand(
                AudioRecordService.widgetStartIntent(app, widgetId, noteId, ResultReceiver(null)),
                0,
                1,
            )
            val recording = checkNotNull(AudioRecordService.widgetRecording)
            val confirmation = WidgetRecordActivity.discardConfirmationIntent(app, recording)
            val intent = shadowOf(confirmation).savedIntent
            val files =
                checkNotNull(app.cacheDir.listFiles()).filter {
                    it.name.startsWith("widget-recording-")
                }
            assertEquals(1, files.size)

            Robolectric.buildActivity(WidgetRecordActivity::class.java, intent).setup().use {
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                ShadowLooper.idleMainLooper()
                assertNull(shadowOf(it.get()).nextStartedService)
                assertEquals(recording, AudioRecordService.widgetRecording)
            }

            Robolectric.buildActivity(WidgetRecordActivity::class.java, intent).setup().use {
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                ShadowLooper.idleMainLooper()
                val discard = checkNotNull(shadowOf(it.get()).nextStartedService)
                service.onStartCommand(discard, 0, 2)
            }

            assertNull(AudioRecordService.widgetRecording)
            assertTrue(files.none { it.exists() })
            assertTrue(
                runBlocking(Dispatchers.IO) { checkNotNull(dao.get(noteId)).audios.isEmpty() }
            )
        } finally {
            serviceController.destroy()
            preferences.deleteWidget(widgetId)
        }
    }
}
