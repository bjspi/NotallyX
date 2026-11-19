package com.philkes.notallyx.utils.audio

import android.content.ContextWrapper
import androidx.room.withTransaction
import com.philkes.notallyx.data.NotallyDatabase
import com.philkes.notallyx.data.model.Audio
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.Folder
import com.philkes.notallyx.data.model.NoteViewMode
import com.philkes.notallyx.data.model.Type
import com.philkes.notallyx.presentation.viewmodel.preference.NotallyXPreferences
import com.philkes.notallyx.presentation.widget.WidgetProvider
import com.philkes.notallyx.utils.SUBFOLDER_AUDIOS
import com.philkes.notallyx.utils.backup.importAudio
import com.philkes.notallyx.utils.onNoteSaved
import com.philkes.notallyx.utils.resolveAttachmentFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal suspend fun ContextWrapper.saveWidgetRecording(noteId: Long, recording: File) {
    withContext(Dispatchers.IO + NonCancellable) {
        check(!NotallyDatabase.isBeingReplaced()) { "Notes database is being replaced" }
        val database = checkNotNull(NotallyDatabase.getDatabase(this@saveWidgetRecording).value)
        val dao = database.getBaseNoteDao()
        val audio = importAudio(recording, deleteOriginalFile = false)
        val (original, updated) =
            try {
                database.withTransaction {
                    val now = System.currentTimeMillis()
                    val original = dao.get(noteId)
                    // Never drop a finished recording: if its note was deleted meanwhile, keep the
                    // audio in a new note instead
                    val updated =
                        original?.copy(
                            audios = original.audios + audio,
                            modifiedTimestamp = maxOf(now, original.modifiedTimestamp + 1),
                        ) ?: newNoteWithAudio(audio, now)
                    if (original == null) {
                            updated.copy(id = dao.insert(updated))
                        } else {
                            dao.updateAll(listOf(updated))
                            updated
                        }
                        .let { original to it }
                }
            } catch (e: Exception) {
                resolveAttachmentFile(SUBFOLDER_AUDIOS, audio.name)?.delete()
                throw e
            }
        WidgetProvider.sendBroadcast(this@saveWidgetRecording, longArrayOf(updated.id))
        onNoteSaved(
            NotallyXPreferences.getInstance(this@saveWidgetRecording),
            updated,
            original,
            forceFullBackup = true,
        )
    }
}

private fun newNoteWithAudio(audio: Audio, timestamp: Long) =
    BaseNote(
        id = 0,
        type = Type.NOTE,
        folder = Folder.NOTES,
        color = BaseNote.COLOR_DEFAULT,
        title = "",
        pinned = false,
        timestamp = timestamp,
        modifiedTimestamp = timestamp,
        labels = emptyList(),
        body = "",
        spans = emptyList(),
        items = emptyList(),
        images = emptyList(),
        files = emptyList(),
        audios = listOf(audio),
        reminders = emptyList(),
        viewMode = NoteViewMode.EDIT,
        isPinnedToStatus = false,
    )
