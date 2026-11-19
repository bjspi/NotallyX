package com.philkes.notallyx.data.adb

import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.philkes.notallyx.data.imports.google.GoogleKeepImporterTest.Companion.createBaseNote
import com.philkes.notallyx.data.model.Audio
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.Folder
import com.philkes.notallyx.data.model.ListItem
import com.philkes.notallyx.data.model.NoteFormat
import com.philkes.notallyx.data.model.NoteViewMode
import com.philkes.notallyx.data.model.SpanRepresentation
import com.philkes.notallyx.data.model.Type
import com.philkes.notallyx.presentation.viewmodel.preference.BiometricLock
import com.philkes.notallyx.test.app
import com.philkes.notallyx.test.database
import com.philkes.notallyx.test.preferences
import com.philkes.notallyx.utils.getCurrentAudioDirectory
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdbNotesProviderTest {
    private val authority = "${app.packageName}.adb"
    private val notes = Uri.parse("content://$authority/notes")
    private val backup = Uri.parse("content://$authority/backup.zip")
    private val provider =
        Robolectric.buildContentProvider(AdbNotesProvider::class.java).create(authority).get()

    @Before
    fun resetAccess() {
        preferences.adbAccess.save(false)
        preferences.biometricLock.save(BiometricLock.DISABLED)
        app.locked.value = true
    }

    @Test
    fun requiresShellOptInAndUnlockedApp() {
        ShadowBinder.setCallingUid(2000)
        assertThrows(SecurityException::class.java) {
            provider.query(notes, null, null, null, null)
        }

        preferences.adbAccess.save(true)
        ShadowBinder.setCallingUid(10000)
        assertThrows(SecurityException::class.java) {
            provider.query(notes, null, null, null, null)
        }

        ShadowBinder.setCallingUid(2000)
        preferences.biometricLock.save(BiometricLock.ENABLED)
        app.locked.value = true
        assertThrows(SecurityException::class.java) {
            provider.query(notes, null, null, null, null)
        }

        app.locked.value = false
        provider.query(notes, null, null, null, null).use { assertEquals(5, it.columnCount) }
    }

    @Test
    fun backupRequiresShellOptInAnUnlockedAppAndReadMode() {
        ShadowBinder.setCallingUid(2000)
        assertThrows(SecurityException::class.java) { provider.openFile(backup, "r") }

        preferences.adbAccess.save(true)
        ShadowBinder.setCallingUid(10000)
        assertThrows(SecurityException::class.java) { provider.openFile(backup, "r") }

        ShadowBinder.setCallingUid(2000)
        preferences.biometricLock.save(BiometricLock.ENABLED)
        assertThrows(SecurityException::class.java) { provider.openFile(backup, "r") }

        app.locked.value = false
        assertThrows(FileNotFoundException::class.java) { provider.openFile(backup, "rw") }
    }

    @Test
    fun editsArchivedTextAndPreservesItsAudio() =
        runBlocking(Dispatchers.IO) {
            preferences.adbAccess.save(true)
            ShadowBinder.setCallingUid(2000)
            val audio = Audio("test.m4a", 1000, 20)
            val file = File(app.getCurrentAudioDirectory(), audio.name)
            file.parentFile!!.mkdirs()
            file.writeText("audio bytes")
            val dao = database.getBaseNoteDao()
            val id =
                dao.insert(
                    BaseNote(
                        id = 0,
                        type = Type.NOTE,
                        folder = Folder.ARCHIVED,
                        color = BaseNote.COLOR_DEFAULT,
                        title = "Original",
                        pinned = false,
                        timestamp = 10,
                        modifiedTimestamp = 10,
                        labels = listOf("Label"),
                        body = "Old",
                        spans = listOf(SpanRepresentation(0, 3, bold = true)),
                        items = emptyList(),
                        images = emptyList(),
                        files = emptyList(),
                        audios = listOf(audio),
                        reminders = emptyList(),
                        viewMode = NoteViewMode.EDIT,
                        isPinnedToStatus = false,
                    )
                )
            val uri = Uri.withAppendedPath(notes, id.toString())
            val patch = """{"body":"New: text","spans":[{"start":0,"end":3,"italic":true}]}"""
            val values =
                ContentValues().apply {
                    put("patch", Base64.encodeToString(patch.toByteArray(), Base64.NO_WRAP))
                }
            assertEquals(1, provider.update(uri, values, null, null))
            provider.query(uri, arrayOf("folder", "body", "labels"), null, null, null).use {
                assertTrue(it.moveToFirst())
                assertEquals("ARCHIVED", it.getString(0))
                assertEquals("New: text", it.getString(1))
                assertEquals("""["Label"]""", it.getString(2))
            }
            val updated = checkNotNull(dao.get(id))
            assertEquals(listOf(audio), updated.audios)
            assertEquals(listOf(SpanRepresentation(0, 3, italic = true)), updated.spans)
            assertTrue(updated.modifiedTimestamp > 10)
            val audioUri = Uri.parse("$uri/attachments/audios/${audio.name}")
            ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(audioUri, "r")).use {
                assertEquals("audio bytes", it.reader().readText())
            }
        }

    @Test
    fun markdownSourceRoundTripsThroughAdb() =
        runBlocking(Dispatchers.IO) {
            preferences.adbAccess.save(true)
            ShadowBinder.setCallingUid(2000)
            val dao = database.getBaseNoteDao()
            val id = dao.insert(createBaseNote(body = "Original"))
            val uri = Uri.withAppendedPath(notes, id.toString())
            val source = "# Über die Notiz\n\n- Item: one\n  - Two\n"
            val patch =
                org.json.JSONObject().put("format", "markdown").put("body", source).toString()
            val values =
                ContentValues().apply {
                    put("patch", Base64.encodeToString(patch.toByteArray(), Base64.NO_WRAP))
                }
            assertEquals(1, provider.update(uri, values, null, null))
            provider.query(uri, arrayOf("format", "body"), null, null, null).use {
                assertTrue(it.moveToFirst())
                assertEquals("markdown", it.getString(0))
                assertEquals(source, it.getString(1))
            }
            val download = Uri.withAppendedPath(uri, "markdown")
            ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(download, "r")).use {
                assertEquals(source, it.reader(Charsets.UTF_8).readText())
            }

            val switchBack = ContentValues().apply { put("format", "text") }
            assertEquals(1, provider.update(uri, switchBack, null, null))
            assertEquals(NoteFormat.TEXT, dao.get(id)?.format)
            assertEquals(source, dao.get(id)?.body)
        }

    @Test
    fun checklistEditRefreshesWidgetAndRenumbersMovedItems() =
        runBlocking(Dispatchers.IO) {
            preferences.adbAccess.save(true)
            app.locked.value = false
            ShadowBinder.setCallingUid(2000)
            val dao = database.getBaseNoteDao()
            val id =
                dao.insert(
                    createBaseNote(
                        type = Type.LIST,
                        items =
                            listOf(
                                ListItem("One", false, false, 0, mutableListOf()),
                                ListItem("Two", false, false, 1, mutableListOf()),
                            ),
                    )
                )
            val uri = Uri.withAppendedPath(notes, id.toString())
            // "Two" moved to the top but still carries its old order, "Three" duplicates one
            val items =
                """[{"body":"Two","order":1},{"body":"One","order":0},{"body":"Three","order":0}]"""
            shadowOf(app).clearBroadcastIntents()

            assertEquals(
                1,
                provider.update(uri, ContentValues().apply { put("items", items) }, null, null),
            )

            val updated = checkNotNull(dao.get(id)).items
            assertEquals(listOf("Two", "One", "Three"), updated.map { it.body })
            assertEquals(listOf(0, 1, 2), updated.map { it.order })
            val widgetUpdate =
                shadowOf(app).broadcastIntents.single {
                    it.action == "com.philkes.notallyx.ACTION_NOTE_MODIFIED"
                }
            assertEquals(
                listOf(id),
                widgetUpdate
                    .getLongArrayExtra("com.philkes.notallyx.EXTRA_MODIFIED_NOTES")
                    ?.toList(),
            )
        }

    @Test
    fun insertCreatesMarkdownNotesAndChecklists() =
        runBlocking(Dispatchers.IO) {
            preferences.adbAccess.save(true)
            app.locked.value = false
            ShadowBinder.setCallingUid(2000)
            val dao = database.getBaseNoteDao()
            val source = "# New\n\n> Quote"
            val patch = org.json.JSONObject().put("title", "PC").put("body", source).toString()
            val values =
                ContentValues().apply {
                    put("patch", Base64.encodeToString(patch.toByteArray(), Base64.NO_WRAP))
                }

            val created = checkNotNull(provider.insert(notes, values))

            val note = checkNotNull(dao.get(created.lastPathSegment!!.toLong()))
            assertEquals(Type.NOTE, note.type)
            assertEquals(Folder.NOTES, note.folder)
            assertEquals(NoteFormat.MARKDOWN, note.format)
            assertEquals("PC", note.title)
            assertEquals(source, note.body)

            val list =
                provider.insert(
                    notes,
                    ContentValues().apply { put("items", """[{"body":"Task","checked":true}]""") },
                )
            val checklist = checkNotNull(dao.get(checkNotNull(list).lastPathSegment!!.toLong()))
            assertEquals(Type.LIST, checklist.type)
            assertEquals(listOf("Task"), checklist.items.map { it.body })
            assertTrue(checklist.items.single().checked)
        }
}
