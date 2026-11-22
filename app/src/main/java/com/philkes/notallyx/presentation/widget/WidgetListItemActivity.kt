package com.philkes.notallyx.presentation.widget

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.philkes.notallyx.NotallyXApplication
import com.philkes.notallyx.R
import com.philkes.notallyx.data.NotallyDatabase
import com.philkes.notallyx.data.model.ListItem
import com.philkes.notallyx.data.model.Type
import com.philkes.notallyx.databinding.DialogInputBinding
import com.philkes.notallyx.presentation.activity.note.EditActivity.Companion.EXTRA_SELECTED_BASE_NOTE
import com.philkes.notallyx.presentation.showAndFocus
import com.philkes.notallyx.presentation.showToast
import com.philkes.notallyx.presentation.viewmodel.preference.NotallyXPreferences
import com.philkes.notallyx.presentation.viewmodel.preference.autoSortByCheckedEnabled
import com.philkes.notallyx.utils.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Changes one list entry straight from the widget: adds, edits or deletes it. A small dialog opens
 * above the home screen, the app itself stays in the background.
 * - Add: "Add" saves the entry and closes, "Another" saves it and stays open for the next one.
 * - Edit: the text of the tapped entry, saved with "Save".
 * - Delete: only after a confirmation.
 */
class WidgetListItemActivity : ComponentActivity() {
    private var saving = false
    private var dialog: AlertDialog? = null

    private val app
        get() = application as NotallyXApplication

    private val noteId
        get() = intent.getLongExtra(EXTRA_SELECTED_BASE_NOTE, 0)

    private val position
        get() = intent.getIntExtra(WidgetProvider.EXTRA_POSITION, -1)

    private val body
        get() = intent.getStringExtra(EXTRA_ITEM_BODY).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this))
        val preferences = NotallyXPreferences.getInstance(app)
        if (noteId <= 0 || (preferences.isLockEnabled && app.locked.value)) {
            finish()
            return
        }
        lifecycleScope.launch {
            val header =
                withContext(Dispatchers.IO) {
                    NotallyDatabase.getDatabase(app)
                        .value
                        ?.getBaseNoteDao()
                        ?.getWidgetNoteHeader(noteId)
                }
            if (header?.type != Type.LIST) {
                finish()
                return@launch
            }
            when (intent.action) {
                ACTION_ADD -> showAddDialog(header.title)
                ACTION_EDIT -> showEditDialog()
                ACTION_DELETE -> showDeleteDialog()
                else -> finish()
            }
        }
    }

    private fun showAddDialog(title: String) {
        val binding =
            inputBinding(text = "", hint = getString(R.string.add_item)) {
                save(binding = it, closeAfterwards = true)
            }
        dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle(title.ifBlank { getString(R.string.add_item) })
                .setView(binding.root)
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.widget_add_list_item_another, null)
                .setPositiveButton(R.string.widget_add_list_item_confirm, null)
                .setOnDismissListener { finish() }
                .showAndFocus(binding.EditText, allowFullSize = true)
                .apply {
                    bindToText(
                        binding,
                        getButton(AlertDialog.BUTTON_POSITIVE) to true,
                        getButton(AlertDialog.BUTTON_NEUTRAL) to false,
                    )
                }
    }

    private fun showEditDialog() {
        val binding =
            inputBinding(text = body, hint = null) { save(binding = it, closeAfterwards = true) }
        dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.widget_edit_list_item)
                .setView(binding.root)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .setOnDismissListener { finish() }
                .showAndFocus(binding.EditText, allowFullSize = true)
                .apply { bindToText(binding, getButton(AlertDialog.BUTTON_POSITIVE) to true) }
    }

    private fun showDeleteDialog() {
        dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.widget_delete_list_item)
                .setMessage(getString(R.string.widget_delete_list_item_message, body))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, null)
                .setOnDismissListener { finish() }
                .show()
                .apply {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        store(closeAfterwards = true, onSaved = {}) {
                            WidgetListEdits.deleteCheckedItem(it, position, body)
                        }
                    }
                }
    }

    /** The text field, whose keyboard action works like the main button. */
    private fun inputBinding(
        text: String,
        hint: String?,
        onDone: (DialogInputBinding) -> Unit,
    ): DialogInputBinding {
        val binding = DialogInputBinding.inflate(layoutInflater)
        binding.root.hint = hint
        binding.EditText.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            setText(text)
            setSelection(text.length)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    onDone(binding)
                    true
                } else false
            }
        }
        return binding
    }

    /**
     * Gives each button its own listener, so that the dialog only closes once the entry is saved,
     * and enables the buttons only while there is text. The flag tells whether a button closes.
     */
    private fun bindToText(
        binding: DialogInputBinding,
        vararg buttons: Pair<Button, Boolean>,
    ) {
        buttons.forEach { (button, closes) ->
            button.setOnClickListener { save(binding, closeAfterwards = closes) }
        }
        fun update() {
            val hasText = !binding.EditText.text.isNullOrBlank()
            buttons.forEach { (button, _) -> button.isEnabled = hasText }
        }
        update()
        binding.EditText.doAfterTextChanged { update() }
    }

    /** Saves the input as new entries or as the edited text, depending on the dialog. */
    private fun save(binding: DialogInputBinding, closeAfterwards: Boolean) {
        val lines =
            binding.EditText.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        if (intent.action == ACTION_EDIT) {
            val newBody = lines.joinToString(" ")
            if (newBody == body) {
                dialog?.dismiss()
                return
            }
            store(closeAfterwards, onSaved = {}) {
                WidgetListEdits.editItem(it, position, body, newBody)
            }
        } else {
            // One entry per line, so pasted lines become separate entries
            val autoSortByChecked = NotallyXPreferences.getInstance(app).autoSortByCheckedEnabled
            store(closeAfterwards, onSaved = { binding.EditText.text = null }) {
                WidgetListEdits.addItems(it, lines, autoSortByChecked)
            }
        }
    }

    private fun store(
        closeAfterwards: Boolean,
        onSaved: () -> Unit,
        edit: (List<ListItem>) -> List<ListItem>?,
    ) {
        if (saving) return
        saving = true
        lifecycleScope.launch {
            // Closing the dialog must not drop a change that is already being saved
            val saved =
                withContext(Dispatchers.IO + NonCancellable) {
                    try {
                        app.editWidgetList(noteId, edit)
                    } catch (e: SQLiteException) {
                        app.log(TAG, throwable = e)
                        false
                    } catch (e: IllegalStateException) {
                        app.log(TAG, throwable = e)
                        false
                    }
                }
            saving = false
            if (!saved) {
                // Mostly the list changed since the widget was drawn, so it gets the current one
                showToast(R.string.widget_list_edit_failed)
                WidgetProvider.sendBroadcast(app, longArrayOf(noteId))
            }
            if (saved && !closeAfterwards) {
                onSaved()
            } else {
                // Dismissing finishes the activity, see the dismiss listeners
                dialog?.dismiss() ?: finish()
            }
        }
    }

    companion object {
        private const val TAG = "WidgetListItemActivity"
        private const val ACTION_ADD = "com.philkes.notallyx.action.WIDGET_ADD_LIST_ITEM"
        private const val ACTION_EDIT = "com.philkes.notallyx.action.WIDGET_EDIT_LIST_ITEM"
        private const val ACTION_DELETE = "com.philkes.notallyx.action.WIDGET_DELETE_LIST_ITEM"
        private const val EXTRA_ITEM_BODY = "com.philkes.notallyx.EXTRA_ITEM_BODY"

        /** Adds entries to the list [noteId]. */
        internal fun addIntent(context: Context, noteId: Long) =
            intent(context, ACTION_ADD, noteId, position = -1, body = null)

        /** Edits the entry at [position], which the widget showed with [body]. */
        internal fun editIntent(context: Context, noteId: Long, position: Int, body: String) =
            intent(context, ACTION_EDIT, noteId, position, body)

        /** Deletes the checked entry at [position] after a confirmation. */
        internal fun deleteIntent(context: Context, noteId: Long, position: Int, body: String) =
            intent(context, ACTION_DELETE, noteId, position, body)

        private fun intent(
            context: Context,
            action: String,
            noteId: Long,
            position: Int,
            body: String?,
        ) =
            Intent(context, WidgetListItemActivity::class.java).apply {
                this.action = action
                data = Uri.fromParts("notallyx-widget-item", "$noteId/$position", null)
                putExtra(EXTRA_SELECTED_BASE_NOTE, noteId)
                putExtra(WidgetProvider.EXTRA_POSITION, position)
                putExtra(EXTRA_ITEM_BODY, body)
                // Its own task, so the open app stays as it is
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
    }
}
