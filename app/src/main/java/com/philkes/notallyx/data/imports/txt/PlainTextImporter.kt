package com.philkes.notallyx.data.imports.txt

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.philkes.notallyx.data.imports.ExternalImporter
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.Folder
import com.philkes.notallyx.data.model.ListItem
import com.philkes.notallyx.data.model.NoteFormat
import com.philkes.notallyx.data.model.NoteViewMode
import com.philkes.notallyx.data.model.Type
import com.philkes.notallyx.presentation.view.misc.Progress
import com.philkes.notallyx.presentation.viewmodel.ExportMimeType
import com.philkes.notallyx.utils.MIME_TYPE_JSON
import com.philkes.notallyx.utils.readFileContents
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

class PlainTextImporter : ExternalImporter {

    override fun import(
        app: Application,
        source: Uri,
        destination: File,
        progress: MutableStateFlow<Progress?>?,
    ): Pair<List<BaseNote>, File?> {
        val notes = mutableListOf<BaseNote>()
        fun readTxtFiles(file: DocumentFile) {
            when {
                file.isDirectory -> {
                    file.listFiles().forEach { readTxtFiles(it) }
                }

                file.isFile -> {
                    val markdown = file.isMarkdownFile()
                    if (!markdown && file.type?.isTextMimeType() == false) {
                        return
                    }
                    val fileNameWithoutExtension = file.name?.substringBeforeLast(".") ?: ""
                    val content = app.contentResolver.readFileContents(file.uri)
                    val listItems = mutableListOf<ListItem>()
                    val listContent =
                        if (markdown)
                            content
                                .lines()
                                .filter(String::isNotBlank)
                                .takeIf { lines ->
                                    lines.isNotEmpty() && lines.all(MARKDOWN_TASK::matches)
                                }
                                ?.joinToString("\n")
                                ?.trimStart()
                        else content
                    listContent?.findListSyntaxRegex()?.let { listSyntaxRegex ->
                        listItems.addAll(listContent.extractListItems(listSyntaxRegex))
                    }
                    val timestamp = System.currentTimeMillis()

                    notes.add(
                        BaseNote(
                            id = 0L, // Auto-generated
                            type = if (listItems.isEmpty()) Type.NOTE else Type.LIST,
                            folder = Folder.NOTES,
                            color = BaseNote.COLOR_DEFAULT,
                            title = fileNameWithoutExtension,
                            pinned = false,
                            timestamp = timestamp,
                            modifiedTimestamp = timestamp,
                            labels = listOf(),
                            body = if (listItems.isEmpty()) content else "",
                            spans = emptyList(),
                            items = listItems,
                            images = listOf(),
                            files = listOf(),
                            audios = listOf(),
                            reminders = listOf(),
                            NoteViewMode.EDIT,
                            false,
                            if (markdown && listItems.isEmpty()) NoteFormat.MARKDOWN
                            else NoteFormat.TEXT,
                        )
                    )
                }
            }
        }
        val file =
            if (source.pathSegments.firstOrNull() == "tree") {
                DocumentFile.fromTreeUri(app, source)
            } else DocumentFile.fromSingleUri(app, source)
        file?.let { readTxtFiles(it) }
        return Pair(notes, null)
    }

    private fun String.isTextMimeType(): Boolean {
        return startsWith("text/") || this in APPLICATION_TEXT_MIME_TYPES
    }

    private fun DocumentFile.isMarkdownFile(): Boolean {
        return (type?.equals(ExportMimeType.MD.mimeType, ignoreCase = true) == true) ||
            (name
                ?.substringAfterLast('.', "")
                ?.equals(ExportMimeType.MD.fileExtension, ignoreCase = true) == true)
    }

    companion object {
        private val MARKDOWN_TASK = Regex("""\s*- \[[ x]\](?:\s.*)?""")
    }
}

val APPLICATION_TEXT_MIME_TYPES =
    arrayOf(
        MIME_TYPE_JSON,
        "application/xml",
        "application/javascript",
        "application/xhtml+xml",
        "application/yaml",
    )
