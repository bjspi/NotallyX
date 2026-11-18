package com.philkes.notallyx.utils

import android.content.ContextWrapper
import android.content.Intent
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.presentation.activity.note.reminders.ReminderReceiver
import com.philkes.notallyx.presentation.viewmodel.preference.NotallyXPreferences
import com.philkes.notallyx.utils.backup.checkBackupOnSave

internal suspend fun ContextWrapper.onNoteSaved(
    preferences: NotallyXPreferences,
    note: BaseNote,
    original: BaseNote?,
    backupOnSave: Boolean = true,
    forceFullBackup: Boolean = false,
) {
    if (backupOnSave) {
        checkBackupOnSave(preferences, note, forceFullBackup)
    }
    if (!note.equalContents(original)) {
        sendBroadcast(
            Intent(this, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_UPDATE_NOTIFICATIONS
                putExtra(ReminderReceiver.EXTRA_NOTE_ID, note.id)
            }
        )
    }
}
