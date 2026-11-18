package com.philkes.notallyx.utils.audio

import com.philkes.notallyx.data.imports.google.GoogleKeepImporterTest.Companion.createBaseNote
import com.philkes.notallyx.data.model.Type
import com.philkes.notallyx.test.app
import com.philkes.notallyx.test.database
import com.philkes.notallyx.test.preferences
import com.philkes.notallyx.utils.getCurrentAudioDirectory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetAudioStorageTest {
    @Test
    fun savesToSessionNoteAfterWidgetSelectionChanges() =
        runBlocking(Dispatchers.IO) {
            val dao = database.getBaseNoteDao()
            val noteId = dao.insert(createBaseNote(title = "Original note", body = "Original body"))
            val otherNoteId = dao.insert(createBaseNote(title = "Other note"))
            val widget = AudioRecordService.WidgetRecording(widgetId = 11, noteId, startedAt = 1)
            preferences.updateWidget(11, noteId, Type.NOTE)
            preferences.updateWidget(12, otherNoteId, Type.NOTE)
            preferences.updateWidget(11, otherNoteId, Type.NOTE)

            val recording = File.createTempFile("widget-recording", ".m4a", app.cacheDir)
            try {
                recording.writeText("audio bytes")
                app.saveWidgetRecording(widget.noteId, recording)

                val savedNote = checkNotNull(dao.get(noteId))
                val attachment = savedNote.audios.single()
                assertTrue(attachment.name.endsWith(".m4a"))
                assertEquals(
                    "audio bytes",
                    File(app.getCurrentAudioDirectory(), attachment.name).readText(),
                )
                assertEquals("Original body", savedNote.body)
                assertTrue(checkNotNull(dao.get(otherNoteId)).audios.isEmpty())
            } finally {
                recording.delete()
                preferences.deleteWidget(11)
                preferences.deleteWidget(12)
            }
        }
}
