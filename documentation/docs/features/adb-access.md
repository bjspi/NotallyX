---
title: ADB access
---

Enable **ADB access** in NotallyX under **Settings → Security**. If you use the app lock, open and unlock NotallyX before connecting. The connection uses Android's authorized ADB shell and the app's current storage settings.

Use the provider authority `com.philkes.notallyx.adb` for the release build, `com.philkes.notallyx.debug.adb` for debug, or `com.philkes.notallyx.beta.adb` for beta. These examples use a debug build and note ID `42`.

```sh
adb shell content query --uri content://com.philkes.notallyx.debug.adb/notes
adb shell content query --uri content://com.philkes.notallyx.debug.adb/notes/42
adb shell 'content update --uri content://com.philkes.notallyx.debug.adb/notes/42 --bind "title:s:Edited on PC"'
```

The index contains IDs, note types, folders, titles and formats, including notes in the archive and bin. The individual note contains the body, dates, labels, formatting, checklist items, reminders and attachment metadata. Dates use Unix milliseconds. To read specific columns, pass `--projection title:format:body:spans`.

You can update `title` on any note, `format`, `body` and `spans` on text notes, and `items` on checklists. A text note’s `format` is either `text` or `markdown`. For Markdown notes, `body` contains the Markdown source. To send multiline Markdown, formatting, checklist items or text containing a colon, encode a UTF-8 JSON object as Base64 and bind it as `patch`. For example, in PowerShell

```powershell
$json = '{"format":"markdown","body":"# Updated\n\n- First item"}'
$patch = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json))
adb shell content update --uri content://com.philkes.notallyx.debug.adb/notes/42 --bind "patch:s:$patch"
```

Create a new note with `content insert` on the `notes` URI. It accepts the same fields and `patch` as an update. Without `items`, the note is a text note in Markdown unless you pass `format:s:text`; with `items`, it is a checklist. New notes land in the notes folder and receive the next free ID, which the index shows as its highest ID.

```powershell
$json = '{"title":"From the PC","body":"# Heading\n\n> A quote\n\n- First item\n- Second item"}'
$patch = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json))
adb shell content insert --uri content://com.philkes.notallyx.debug.adb/notes --bind "patch:s:$patch"
```

Use the JSON arrays returned by the query as templates for `spans` and `items`. Span positions are zero-based UTF-16 offsets with exclusive ends. A body update without spans clears the old rich-text formatting. A format-only update keeps the source and its saved spans. Checklist items are listed in display order, with each child following its parent or sibling.

Download a note as a UTF-8 Markdown file. This preserves the source for a Markdown note and exports existing formatting for text notes and checklists.

```sh
adb exec-out content read --uri content://com.philkes.notallyx.debug.adb/notes/42/markdown > note.md
```

Download an attachment by note ID, category and stored name. Use `localName` from `images` or `files`, or `name` from `audios`. URI-encode the name if it contains reserved URI characters.

```sh
adb exec-out content read --uri content://com.philkes.notallyx.debug.adb/notes/42/attachments/audios/NAME.m4a > recording.m4a
```

Download a fresh NotallyX backup ZIP containing a SQLite snapshot and available attachments.
The ZIP also contains the original UTF-8 source of each Markdown note under `markdown/`. Filenames use the note title and add a number when titles have the same filename. Untitled notes use `Note-<id>.md`.
The export uses the configured backup password, if set. Because ZIP filenames remain visible in password-protected archives, these exports name every Markdown file `Note-<id>.md` instead of using its title.

```sh
adb exec-out content read --uri content://com.philkes.notallyx.debug.adb/backup.zip > NotallyX_Backup.zip
```

Use PowerShell 7.4 or later, cmd.exe, or a POSIX shell for binary redirection.
