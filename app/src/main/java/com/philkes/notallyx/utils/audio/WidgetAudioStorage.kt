package com.philkes.notallyx.utils.audio

import android.content.ContextWrapper
import androidx.room.withTransaction
import com.philkes.notallyx.data.NotallyDatabase
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
                    val original = checkNotNull(dao.get(noteId)) { "Recording's note was deleted" }
                    val updated =
                        original.copy(
                            audios = original.audios + audio,
                            modifiedTimestamp =
                                maxOf(System.currentTimeMillis(), original.modifiedTimestamp + 1),
                        )
                    dao.updateAll(listOf(updated))
                    original to updated
                }
            } catch (e: Exception) {
                resolveAttachmentFile(SUBFOLDER_AUDIOS, audio.name)?.delete()
                throw e
            }
        WidgetProvider.sendBroadcast(this@saveWidgetRecording, longArrayOf(noteId))
        onNoteSaved(
            NotallyXPreferences.getInstance(this@saveWidgetRecording),
            updated,
            original,
            forceFullBackup = true,
        )
    }
}
