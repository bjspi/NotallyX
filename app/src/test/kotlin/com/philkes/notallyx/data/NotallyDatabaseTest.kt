package com.philkes.notallyx.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.philkes.notallyx.data.NotallyDatabase.Companion.setupEncryption
import com.philkes.notallyx.data.imports.google.GoogleKeepImporterTest.Companion.createBaseNote
import com.philkes.notallyx.data.model.NoteFormat
import com.philkes.notallyx.presentation.viewmodel.preference.BiometricLock
import com.philkes.notallyx.presentation.viewmodel.preference.NotallyXPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotallyDatabaseTest {

    @Test
    fun migrationFrom11KeepsExistingNotesAndSupportsPerNoteMarkdown() =
        runBlocking(Dispatchers.IO) {
            val application = ApplicationProvider.getApplicationContext<Application>()
            val db =
                Room.databaseBuilder(application, NotallyDatabase::class.java, "note-format-test")
                    .createFromInputStream {
                        requireNotNull(
                            javaClass.getResourceAsStream("/database/unencrypted/NotallyDatabase")
                        )
                    }
                    .addMigrations(NotallyDatabase.Companion.Migration12)
                    .build()
            try {
                val dao = db.getBaseNoteDao()
                val existing = dao.getAll()
                assertTrue(existing.isNotEmpty())
                assertTrue(existing.all { it.format == NoteFormat.TEXT })

                val source = "# Heading\n\n- Item"
                val id =
                    dao.insert(createBaseNote(body = source).copy(format = NoteFormat.MARKDOWN))
                val saved = checkNotNull(dao.get(id))
                assertEquals(NoteFormat.MARKDOWN, saved.format)
                assertEquals(source, saved.body)
            } finally {
                db.close()
                application.deleteDatabase("note-format-test")
            }
        }

    @Test
    fun biometricLockEncryptsDb_defaultIsFalse() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val preferences = NotallyXPreferences.getInstance(application)
        assertFalse(preferences.biometricLockEncryptsDb.value)
    }

    @Test
    fun setupEncryption_whenBiometricLockEncryptsDbIsFalse_doesNotEnableEncryptionOrModifyPreferences() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val preferences = NotallyXPreferences.getInstance(application)
        preferences.biometricLockEncryptsDb.save(false)
        preferences.biometricLock.save(BiometricLock.ENABLED)

        val builder = NotallyDatabase.builder(application, false)
        builder.setupEncryption(application, preferences, BiometricLock.ENABLED)

        assertEquals(BiometricLock.ENABLED, preferences.biometricLock.value)
    }
}
