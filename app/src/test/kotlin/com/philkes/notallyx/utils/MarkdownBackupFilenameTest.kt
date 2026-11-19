package com.philkes.notallyx.utils

import com.philkes.notallyx.utils.backup.markdownBackupFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBackupFilenameTest {
    @Test
    fun keepsTitlesAndDisambiguatesCaseInsensitiveCollisions() {
        val usedNames = mutableSetOf<String>()
        assertEquals("Über die Notiz.md", markdownBackupFileName("Über die Notiz", 1, usedNames))
        assertEquals(
            "über die Notiz (2).md",
            markdownBackupFileName("über die Notiz.md", 2, usedNames),
        )
    }

    @Test
    fun makesTitlesSafeForExtraction() {
        val usedNames = mutableSetOf<String>()
        assertEquals("Note-1.md", markdownBackupFileName(" ... ", 1, usedNames))
        assertEquals("_NUL.txt.md", markdownBackupFileName("NUL.txt", 2, usedNames))
        assertEquals("_Ordner_Notiz_.md", markdownBackupFileName("../Ordner\\Notiz?", 3, usedNames))
        val longName = markdownBackupFileName("📕".repeat(100), 4, usedNames)
        assertTrue(longName.toByteArray().size <= 255)
        assertEquals("📕".repeat(55) + ".md", longName)
    }
}
