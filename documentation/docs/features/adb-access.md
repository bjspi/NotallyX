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

The index contains IDs, note types, folders and titles, including notes in the archive and bin. The individual note contains the body, dates, labels, formatting, checklist items, reminders and attachment metadata. Dates use Unix milliseconds. To read specific columns, pass `--projection title:body:spans`.

You can update `title` on any note, `body` and `spans` on text notes, and `items` on checklists. To send formatting, checklist items or text containing a colon, encode a UTF-8 JSON object as Base64 and bind it as `patch`. For example, in PowerShell

```powershell
$json = '{"body":"Updated: text","spans":[{"start":0,"end":7,"bold":true}]}'
$patch = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json))
adb shell content update --uri content://com.philkes.notallyx.debug.adb/notes/42 --bind "patch:s:$patch"
```

Use the JSON arrays returned by the query as templates for `spans` and `items`. Span positions are zero-based UTF-16 offsets with exclusive ends. A body update without spans clears the old formatting. Checklist items are listed in display order, with each child following its parent or sibling.

Download an attachment by note ID, category and stored name. Use `localName` from `images` or `files`, or `name` from `audios`. URI-encode the name if it contains reserved URI characters.

```sh
adb exec-out content read --uri content://com.philkes.notallyx.debug.adb/notes/42/attachments/audios/NAME.m4a > recording.m4a
```

Use PowerShell 7.4 or later, cmd.exe, or a POSIX shell for the binary redirection in this example.
