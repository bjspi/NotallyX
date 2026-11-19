<h2 align="center">
    <img src="fastlane/metadata/android/en-US/images/icon.png" alt="icon" width="90"/>
    <br />
    <b><a href="https://crustack.github.io/NotallyX/">NotallyX | Minimalistic note taking app</a></b>
</h2>

## What this fork adds

### Markdown notes and previews

- Choose **Note format** in each text note’s menu to switch between Markdown and rich text. **Settings → Appearance → Markdown for new notes** sets the format for newly created text notes. Backups preserve each note’s format.
- The list and grid overviews, the opened note, and the home-screen widget render Markdown. Previews support headings, subheadings, nested and numbered lists, inline formatting, quotes, task checkboxes, and code with a subtle background. [Grid preview](app/src/screenshots/ScreenshotTests/markdownNote/grid.png)
- Tap directly in the text of an editable Markdown note to edit its source. Press Enter once for a line break and twice for a blank line. Closing the keyboard restores the rendered preview.
- Imported Markdown text notes retain their source. Markdown files containing only task entries can be imported as checklists. Exporting a Markdown note as `.md` preserves its source.

### Home-screen widget

- The title and controls share the top row. The widget renders Markdown notes and preserves formatting in rich-text notes.
- Tap the **down arrow** to the left of the note icon for a scrollable list of active note and checklist titles. Tap a title to switch the widget and close the list. Pinned notes come first, followed by the most recently edited. The note icon opens the full picker. [Quick switch](app/src/screenshots/WidgetNotePickerTest/open.png)
- In the **Select note** list, each row has a color stripe based on its note and a lightly tinted background that alternates between rows. The **home icon** in the list header opens the main notes overview.
- Tap the **Voice+ microphone** to record. Android may ask for microphone permission the first time. The widget shows the elapsed time. **Stop** attaches the M4A recording to the note that was selected when recording began, even if you switch the widget to a different note mid-recording.
- Tap the **trash icon** while recording to open a confirmation dialog. **Discard** ends the recording without attaching it. **Cancel** keeps it running.

### ADB access

- Enable **ADB access** under **Settings → Security** to use an authorized ADB shell. Unlock NotallyX first if app lock is enabled.
- Read notes and checklists by ID, including entries in the archive and bin. Download images, files, and audio attachments. Edit the title, body, format, or rich-text spans of existing notes and update checklist items. Create new Markdown notes or checklists. Markdown notes expose their original source, which can also be downloaded as `.md`. [ADB commands](documentation/docs/features/adb-access.md)

#### ADB reference for scripts and agents

Prerequisites: an ADB-authorized device, **Settings → Security → ADB access** on, and NotallyX unlocked when app lock is enabled. Every call must come from `adb shell` (UID 2000); other callers get a `SecurityException`. The authority depends on the build: `com.philkes.notallyx.adb` (release), `com.philkes.notallyx.debug.adb` (debug), `com.philkes.notallyx.beta.adb` (beta). In the examples, replace `$A` with `content://<authority>`, for example `content://com.philkes.notallyx.debug.adb`.

| Goal | Command | Result |
| --- | --- | --- |
| List all notes | `adb shell content query --uri $A/notes` | Rows with `id`, `type` (`NOTE`/`LIST`), `folder` (`NOTES`/`ARCHIVED`/`DELETED`), `title`, `format` (`text`/`markdown`) |
| Read one note | `adb shell content query --uri $A/notes/42` | `id, type, folder, title, body, timestamp, modifiedTimestamp, color, pinned, isPinnedToStatus, viewMode, labels, spans, items, reminders, images, files, audios, format`. Arrays are JSON, dates are Unix milliseconds |
| Read selected columns | `adb shell content query --uri $A/notes/42 --projection title:format:body` | Only these columns |
| Edit a simple field | `adb shell 'content update --uri $A/notes/42 --bind "title:s:New title"'` | `title` on any note; `body`, `format`, `spans` on text notes; `items` on checklists |
| Edit with any content | `adb shell content update --uri $A/notes/42 --bind "patch:s:<Base64 of UTF-8 JSON>"` | JSON object with any of `title`, `body`, `format`, `spans`, `items` |
| Create a note | `adb shell content insert --uri $A/notes --bind "patch:s:<Base64 of UTF-8 JSON>"` | New note in the notes folder: Markdown text note by default, `"format":"text"` for rich text, `items` for a checklist. The new ID is the highest ID in the index |
| Download a note as Markdown | `adb exec-out content read --uri $A/notes/42/markdown > note.md` | Markdown source, or Markdown generated from rich text and checklists |
| Download an attachment | `adb exec-out content read --uri $A/notes/42/attachments/<images\|files\|audios>/<name> > file` | `localName` from `images`/`files`, `name` from `audios` |
| Download a full backup | `adb exec-out content read --uri $A/backup.zip > NotallyX_Backup.zip` | Regular NotallyX backup plus `markdown/*.md`, encrypted with the backup password if one is set |

Rules for writing:

- Use `patch` for multiline text, colons, quotes, non-ASCII text, `spans` and `items`. Shell `--bind` values break on these. Build the JSON, encode it as UTF-8, then Base64. PowerShell: `[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json))`. POSIX: `printf '%s' "$json" | base64 -w0`.
- Example create patch: `{"title":"Shopping","body":"# Today\n\n- [ ] Milk\n- [x] Bread"}`. Example checklist: `{"title":"Todo","items":[{"body":"First","checked":false},{"body":"Sub task","isChild":true}]}`.
- `items` replaces the whole checklist. Their array order is the display order; a child item follows its parent, and the first item cannot be a child.
- `spans` uses zero-based UTF-16 offsets with exclusive `end`, as returned by the query. Updating `body` without `spans` clears rich-text formatting. Switching only `format` keeps body and spans.
- Edits refresh the home-screen widget and trigger the configured backup-on-save. Deleting notes over ADB is not supported.

Full guide: [ADB access](documentation/docs/features/adb-access.md).

### Appearance

- **Settings → Appearance → Blank line height in Markdown** sets how tall blank lines appear in rendered Markdown, from hidden to a full text line.
- Turn on **Settings → Appearance → Lined notes** to add faint horizontal lines to the text in opened notes. The lines follow wrapped and blank lines in both the Markdown preview and the editor.
- Select `dd.MM.yyyy` for dates such as `18.11.2026`. The overview and the opened-note view each have their own setting under **Settings → Appearance**.
- Drag in the color wheel or along the hue and brightness sliders. The color swatch and hex value update as you drag.

---

<div style="display: flex; justify-content: space-between; width: 100%;">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" alt="Image 6" style="width: 32%;"/>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" alt="Image 2" style="width: 32%;"/>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" alt="Image 3" style="width: 32%;"/>
</div>

<div style="display: flex; justify-content: space-between; width: 100%;">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" alt="Image 4" style="width: 32%;"/>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" alt="Image 5" style="width: 32%;"/>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/7.png" alt="Image 7" style="width: 32%;"/>
</div>


### Features
[Notally](https://github.com/OmGodse/Notally), but eXtended

<h4><a href="https://crustack.github.io/NotallyX/">See Documentation</a></h4>

* Create **rich text** notes with support for bold, italics, mono space and strike-through
* Create **task lists** and order them with subtasks (+ auto-sort checked items to the end)
* Set **reminders** with notifications for important notes
* Complement your notes with any type of file such as **pictures**, PDFs, etc.
* **Sort notes** by title, last modified date, creation date
* **Color, pin and label** your notes for quick organisation
* Add **clickable links** to notes with support for phone numbers, email addresses and web urls
* **Undo/Redo actions**
* Use **Home Screen Widget** to access important notes fast
* **Lock your notes via Biometric/PIN**
* Configurable **auto-backups**
* **Import from other apps** such as [Evernote](https://evernote.com/), [Google Keep](https://keep.google.com/), [Quillpad](https://quillpad.github.io/)
* Create quick audio notes
* Display the notes either in a **List or Grid**
* Quickly share notes by text
* Extensive preferences to adjust views to your liking
* Actions to quickly remove checked tasks
* Adaptive android app icon
* Support for Android 7.0 (API 24) and later
