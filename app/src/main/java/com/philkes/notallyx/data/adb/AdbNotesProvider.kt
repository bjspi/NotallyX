package com.philkes.notallyx.data.adb

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import androidx.room.withTransaction
import com.philkes.notallyx.NotallyXApplication
import com.philkes.notallyx.data.NotallyDatabase
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.Converters
import com.philkes.notallyx.presentation.viewmodel.preference.NotallyXPreferences
import com.philkes.notallyx.presentation.widget.WidgetProvider
import com.philkes.notallyx.utils.SUBFOLDER_AUDIOS
import com.philkes.notallyx.utils.SUBFOLDER_FILES
import com.philkes.notallyx.utils.SUBFOLDER_IMAGES
import com.philkes.notallyx.utils.onNoteSaved
import com.philkes.notallyx.utils.resolveAttachmentFile
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class AdbNotesProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val app = requireAccess()
        require(selection == null && selectionArgs == null && sortOrder == null)
        val path = uri.pathSegments
        require(path.firstOrNull() == "notes" && path.size in 1..2)
        return runBlocking(Dispatchers.IO) {
            val dao = getDatabase(app).getBaseNoteDao()
            if (path.size == 1) {
                val cursor = cursor(projection, INDEX_COLUMNS)
                dao.getNoteIndex().forEach { note ->
                    cursor.addValues(
                        INDEX_COLUMNS,
                        listOf(note.id, note.type.name, note.folder.name, note.title),
                    )
                }
                cursor
            } else {
                val cursor = cursor(projection, NOTE_COLUMNS)
                dao.get(noteId(path))?.let { cursor.addValues(NOTE_COLUMNS, it.adbValues()) }
                cursor
            }
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        val app = requireAccess()
        require(selection == null && selectionArgs == null)
        val path = uri.pathSegments
        require(path.size == 2 && path[0] == "notes")
        val id = noteId(path)
        val changes = AdbNoteChanges(requireNotNull(values))
        return runBlocking(Dispatchers.IO) {
            val database = getDatabase(app)
            val dao = database.getBaseNoteDao()
            val (original, updated) =
                database.withTransaction {
                    val original = dao.get(id) ?: return@withTransaction null
                    val updated = changes.applyTo(original)
                    if (updated != original) {
                        dao.updateAll(listOf(updated))
                    }
                    original to updated
                } ?: return@runBlocking 0
            if (updated != original) {
                WidgetProvider.sendBroadcast(app, longArrayOf(id))
                app.onNoteSaved(NotallyXPreferences.getInstance(app), updated, original)
            }
            1
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val app = requireAccess()
        val path = uri.pathSegments
        if (mode != "r" || path.size != 5 || path[0] != "notes" || path[2] != "attachments") {
            throw FileNotFoundException()
        }
        val name = path[4]
        if (name == "." || name == ".." || name.any { it == '/' || it == '\\' || it == '\u0000' }) {
            throw FileNotFoundException()
        }
        return runBlocking(Dispatchers.IO) {
            val note =
                getDatabase(app).getBaseNoteDao().get(noteId(path)) ?: throw FileNotFoundException()
            val directory =
                when (path[3]) {
                    "images" -> SUBFOLDER_IMAGES.takeIf { note.images.any { it.localName == name } }
                    "files" -> SUBFOLDER_FILES.takeIf { note.files.any { it.localName == name } }
                    "audios" -> SUBFOLDER_AUDIOS.takeIf { note.audios.any { it.name == name } }
                    else -> null
                } ?: throw FileNotFoundException()
            val file = app.resolveAttachmentFile(directory, name) ?: throw FileNotFoundException()
            if (!file.isFile || file.canonicalFile != File(file.parentFile!!.canonicalFile, name)) {
                throw FileNotFoundException()
            }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    private fun requireAccess(): NotallyXApplication {
        if (Binder.getCallingUid() != SHELL_UID) {
            throw SecurityException("Only the ADB shell can access this provider")
        }
        val app = requireNotNull(context).applicationContext as NotallyXApplication
        val preferences = NotallyXPreferences.getInstance(app)
        if (!preferences.adbAccess.value || (preferences.isLockEnabled && app.locked.value)) {
            throw SecurityException("Enable ADB access and unlock NotallyX")
        }
        return app
    }

    private fun getDatabase(app: NotallyXApplication): NotallyDatabase {
        check(!NotallyDatabase.isBeingReplaced()) { "The notes database is being replaced" }
        return checkNotNull(NotallyDatabase.getDatabase(app).value) { "Notes database unavailable" }
    }

    private fun noteId(path: List<String>): Long =
        requireNotNull(path[1].toLongOrNull()?.takeIf { it > 0 }) { "Invalid note ID" }

    private fun cursor(projection: Array<out String>?, columns: List<String>): MatrixCursor {
        val requested = projection?.toList() ?: columns
        require(requested.all { it in columns }) { "Unknown column" }
        return MatrixCursor(requested.toTypedArray())
    }

    private fun MatrixCursor.addValues(columns: List<String>, values: List<Any>) {
        addRow(columnNames.map { values[columns.indexOf(it)] })
    }

    private fun BaseNote.adbValues(): List<Any> =
        listOf(
            id,
            type.name,
            folder.name,
            title,
            body,
            timestamp,
            modifiedTimestamp,
            color,
            if (pinned) 1 else 0,
            if (isPinnedToStatus) 1 else 0,
            viewMode.name,
            Converters.labelsToJson(labels),
            Converters.spansToJson(spans),
            Converters.itemsToJson(items),
            Converters.remindersToJson(reminders),
            Converters.filesToJson(images),
            Converters.filesToJson(files),
            Converters.audiosToJson(audios),
        )

    companion object {
        // Android's reserved UID for processes launched by adb shell.
        private const val SHELL_UID = 2000
        private val INDEX_COLUMNS = listOf("id", "type", "folder", "title")
        private val NOTE_COLUMNS =
            INDEX_COLUMNS +
                listOf(
                    "body",
                    "timestamp",
                    "modifiedTimestamp",
                    "color",
                    "pinned",
                    "isPinnedToStatus",
                    "viewMode",
                    "labels",
                    "spans",
                    "items",
                    "reminders",
                    "images",
                    "files",
                    "audios",
                )
    }
}
