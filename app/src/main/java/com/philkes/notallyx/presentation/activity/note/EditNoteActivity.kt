package com.philkes.notallyx.presentation.activity.note

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.URLSpan
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.philkes.notallyx.R
import com.philkes.notallyx.data.model.NoteFormat
import com.philkes.notallyx.data.model.NoteViewMode
import com.philkes.notallyx.data.model.Type
import com.philkes.notallyx.data.model.getNoteIdFromUrl
import com.philkes.notallyx.data.model.getNoteTypeFromUrl
import com.philkes.notallyx.data.model.isNoteUrl
import com.philkes.notallyx.databinding.BottomTextFormattingMenuBinding
import com.philkes.notallyx.databinding.RecyclerToggleBinding
import com.philkes.notallyx.presentation.addIconButton
import com.philkes.notallyx.presentation.dp
import com.philkes.notallyx.presentation.hideKeyboard
import com.philkes.notallyx.presentation.repeatOnLifecycleScope
import com.philkes.notallyx.presentation.setCancelButton
import com.philkes.notallyx.presentation.setControlsContrastColorForAllViews
import com.philkes.notallyx.presentation.setOnNextAction
import com.philkes.notallyx.presentation.showKeyboard
import com.philkes.notallyx.presentation.showToast
import com.philkes.notallyx.presentation.view.misc.highlightableview.HighlightSpan
import com.philkes.notallyx.presentation.view.note.NoteLinesDrawable
import com.philkes.notallyx.presentation.view.note.TextFormattingAdapter
import com.philkes.notallyx.presentation.view.note.action.Action
import com.philkes.notallyx.presentation.view.note.action.AddNoteBottomSheet
import com.philkes.notallyx.presentation.view.note.renderMarkdown
import com.philkes.notallyx.presentation.withAlpha
import com.philkes.notallyx.utils.LinkMovementMethod
import com.philkes.notallyx.utils.copyToClipBoard
import com.philkes.notallyx.utils.findAllOccurrences
import com.philkes.notallyx.utils.log
import com.philkes.notallyx.utils.openNote
import com.philkes.notallyx.utils.wrapWithChooser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditNoteActivity : EditActivity(Type.NOTE) {

    private lateinit var textFormatMenu: View

    private var textFormattingAdapter: TextFormattingAdapter? = null

    private var searchResultIndices: List<Pair<Int, Int>> = emptyList()
    private var markdownPreviewJob: Job? = null
    private var markdownPreviewSource: String? = null
    private var renderedMarkdown: CharSequence = ""
    private var editingMarkdown = false
    private var keyboardVisible = false

    private val isMarkdown: Boolean
        get() = notallyModel.format == NoteFormat.MARKDOWN

    private val canFormatText: Boolean
        get() = canEdit && notallyModel.format == NoteFormat.TEXT

    override fun configureUI() {
        binding.EnterTitle.setOnNextAction {
            if (isMarkdown) startBodyEditing() else binding.EnterBody.requestFocus()
        }

        if (notallyModel.isNewNote && !isMarkdown) {
            binding.EnterBody.requestFocus()
        }
    }

    override fun toggleCanEdit(mode: NoteViewMode) {
        super.toggleCanEdit(mode)
        textFormatMenu.isVisible = canFormatText
        when {
            mode == NoteViewMode.EDIT && !isMarkdown -> showKeyboard(binding.EnterBody)
            mode == NoteViewMode.EDIT -> Unit
            binding.EnterBody.isFocused -> hideKeyboard(binding.EnterBody)
        }
        if (!canEdit) editingMarkdown = false
        binding.EnterBody.setCanEdit(mode == NoteViewMode.EDIT)
        setupEditor()
        updateMarkdownPreview()
    }

    override fun onKeyboardVisibilityChanged(visible: Boolean) {
        val dismissed = keyboardVisible && !visible
        keyboardVisible = visible
        if (dismissed) stopMarkdownEditing()
    }

    private fun startBodyEditing() {
        if (isMarkdown) {
            if (!canEdit) return
            editingMarkdown = true
            updateMarkdownPreview()
        }
        binding.EnterBody.apply {
            requestFocus()
            if (canEdit) {
                setSelection(length())
                showKeyboard(this)
            }
        }
    }

    private fun stopMarkdownEditing() {
        if (!editingMarkdown) return
        editingMarkdown = false
        updateMarkdownPreview()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.apply {
            putInt(EXTRA_SELECTION_START, binding.EnterBody.selectionStart)
            putInt(EXTRA_SELECTION_END, binding.EnterBody.selectionEnd)
        }
    }

    override suspend fun highlightSearchResults(search: String): Int {
        binding.EnterBody.clearHighlights()
        val inPreview = binding.MarkdownPreview.isVisible
        val text = if (inPreview) renderedMarkdown.toString() else notallyModel.body.toString()
        searchResultIndices =
            if (search.isEmpty()) emptyList()
            else withContext(Dispatchers.Default) { text.findAllOccurrences(search) }
        if (inPreview) {
            highlightMarkdownPreview(-1)
        } else {
            binding.EnterBody.highlight(searchResultIndices, -1)
        }
        return searchResultIndices.size
    }

    override fun selectSearchResult(resultPos: Int) {
        if (binding.MarkdownPreview.isVisible) {
            highlightMarkdownPreview(resultPos)
            searchResultIndices.getOrNull(resultPos)?.let { (start, _) ->
                binding.MarkdownPreview.layout?.let { layout ->
                    binding.ScrollView.scrollTo(
                        0,
                        binding.MarkdownPreview.top +
                            layout.getLineTop(layout.getLineForOffset(start)),
                    )
                }
            }
            return
        }
        if (resultPos < 0) {
            binding.EnterBody.unselectHighlight()
            return
        }
        searchResultIndices.getOrNull(resultPos)?.let { (startIdx, endIdx) ->
            val selectedLineTop = binding.EnterBody.select(startIdx, endIdx)
            selectedLineTop?.let { binding.ScrollView.scrollTo(0, it) }
        }
    }

    private fun highlightMarkdownPreview(selectedIndex: Int) {
        binding.MarkdownPreview.apply {
            text =
                SpannableString(renderedMarkdown).apply {
                    searchResultIndices.take(1000).forEachIndexed { index, (start, end) ->
                        if (end <= length) {
                            val color =
                                if (index == selectedIndex) highlightColor
                                else highlightColor.withAlpha(0.1f)
                            setSpan(
                                HighlightSpan(color),
                                start,
                                end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                    }
                }
        }
    }

    override fun setupListeners() {
        super.setupListeners()
        binding.EnterBody.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) stopMarkdownEditing()
        }
        binding.EnterBody.initHistory(changeHistory) { text ->
            val textChanged = !notallyModel.body.toString().contentEquals(text)
            notallyModel.body = text
            if (textChanged) {
                if (binding.MarkdownPreview.isVisible) updateMarkdownPreview()
                else updateSearchResults(search.query)
                updateJumpButtonsVisibility()
            }
        }
    }

    override fun setupAdditionalListeners() {
        super.setupAdditionalListeners()
        repeatOnLifecycleScope {
            preferences.linedNotes.flow.collect { enabled ->
                binding.EnterBody.background =
                    if (enabled) NoteLinesDrawable(binding.EnterBody) else null
                binding.MarkdownPreview.background =
                    if (enabled) NoteLinesDrawable(binding.MarkdownPreview) else null
            }
        }
    }

    override fun setStateFromModel(savedInstanceState: Bundle?) {
        editingMarkdown = false
        super.setStateFromModel(savedInstanceState)
        updateEditText()
        savedInstanceState?.let {
            val selectionStart = it.getInt(EXTRA_SELECTION_START, -1)
            val selectionEnd = it.getInt(EXTRA_SELECTION_END, -1)
            if (selectionStart > -1 && !isMarkdown) {
                binding.EnterBody.postOnAnimation {
                    binding.EnterBody.focusAndSelect(selectionStart, selectionEnd)
                }
            }
        }
    }

    private fun updateEditText() {
        binding.EnterBody.text = notallyModel.body
        setupEditor()
        updateMarkdownPreview()
    }

    override fun extraNoteActions(): List<Action> =
        listOf(
            Action(
                R.string.note_format,
                R.drawable.text_format,
                label =
                    getString(R.string.note_format_action, getString(notallyModel.format.label)),
            ) {
                chooseNoteFormat()
                true
            }
        )

    private fun chooseNoteFormat() {
        val formats = NoteFormat.entries
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.note_format)
            .setSingleChoiceItems(
                formats.map { getString(it.label) }.toTypedArray(),
                formats.indexOf(notallyModel.format),
            ) { dialog, selection ->
                dialog.dismiss()
                val previous = notallyModel.format
                val next = formats[selection]
                if (next == previous) return@setSingleChoiceItems
                lifecycleScope.launch {
                    notallyModel.format = next
                    try {
                        saveNote()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        notallyModel.format = previous
                        log("EditNoteActivity", throwable = e)
                        showToast(R.string.note_save_failed)
                    }
                    initBottomMenu()
                    editingMarkdown = false
                    setupEditor()
                    updateMarkdownPreview()
                }
            }
            .setCancelButton()
            .show()
    }

    private val NoteFormat.label: Int
        get() =
            when (this) {
                NoteFormat.TEXT -> R.string.note_format_text
                NoteFormat.MARKDOWN -> R.string.note_format_markdown
            }

    private fun updateMarkdownPreview() {
        val showPreview = isMarkdown && !editingMarkdown
        val visibilityChanged = binding.MarkdownPreview.isVisible != showPreview
        binding.EnterBody.isVisible = !showPreview
        binding.MarkdownPreview.isVisible = showPreview
        if (showPreview) {
            val source = notallyModel.body.toString()
            if (source == markdownPreviewSource && !visibilityChanged) return
            markdownPreviewJob?.cancel()
            markdownPreviewSource = source
            renderedMarkdown = ""
            searchResultIndices = emptyList()
            binding.MarkdownPreview.text = null
            markdownPreviewJob = lifecycleScope.launch {
                renderedMarkdown = withContext(Dispatchers.IO) { renderMarkdown(source) }
                binding.MarkdownPreview.text = renderedMarkdown
                if (isInSearchMode()) updateSearchResults(search.query)
                binding.MarkdownPreview.doOnLayout { updateJumpButtonsVisibility() }
            }
        } else if (visibilityChanged) {
            markdownPreviewJob?.cancel()
            if (isInSearchMode()) updateSearchResults(search.query)
            binding.EnterBody.doOnLayout { updateJumpButtonsVisibility() }
        }
    }

    private fun setupEditor() {
        binding.MarkdownPreview.apply {
            setTextIsSelectable(!canEdit)
            movementMethod =
                if (canEdit) null else android.text.method.LinkMovementMethod.getInstance()
            setOnClickListener(if (canEdit) View.OnClickListener { startBodyEditing() } else null)
        }
        setupMovementMethod()
        binding.EnterBody.customSelectionActionModeCallback =
            if (canFormatText) {
                FormattingActionModeCallback(this, binding.EnterBody)
            } else null

        binding.EnterBody.customInsertionActionModeCallback =
            if (canFormatText) {
                FormattingActionModeCallback(this, binding.EnterBody)
            } else null
        if (canFormatText) {
            binding.EnterBody.setOnSelectionChange { selStart, selEnd ->
                if (selEnd - selStart > 0) {
                    if (!textFormatMenu.isEnabled) {
                        initBottomTextFormattingMenu()
                    }
                    textFormatMenu.isEnabled = true
                    textFormattingAdapter?.updateTextFormattingToggles(selStart, selEnd)
                } else {
                    if (textFormatMenu.isEnabled) {
                        initBottomMenu()
                    }
                    textFormatMenu.isEnabled = false
                }
            }
        } else {
            binding.EnterBody.setOnSelectionChange { _, _ -> }
        }
        binding.ContentLayout.apply {
            setOnClickListener(
                if (isMarkdown) null else View.OnClickListener { startBodyEditing() }
            )
            isClickable = !isMarkdown
        }
    }

    override fun initBottomMenu() {
        super.initBottomMenu()
        binding.BottomAppBarCenter.visibility = VISIBLE
        binding.BottomAppBarLeft.apply {
            removeAllViews()
            addIconButton(R.string.add_item, R.drawable.add, colorInt, marginStart = 0) {
                AddNoteBottomSheet(actionHandler, colorInt)
                    .show(supportFragmentManager, AddNoteBottomSheet.TAG)
            }
            updateLayoutParams<ConstraintLayout.LayoutParams> { endToStart = -1 }
            textFormatMenu =
                addIconButton(R.string.edit, R.drawable.text_format, colorInt) {
                        initBottomTextFormattingMenu()
                    }
                    .apply {
                        isEnabled = binding.EnterBody.isActionModeOn
                        isVisible = canFormatText
                    }
        }
        setBottomAppBarColor(colorInt)
    }

    private fun initBottomTextFormattingMenu() {
        binding.BottomAppBarCenter.visibility = GONE
        val extractColor = colorInt
        binding.BottomAppBarRight.apply {
            removeAllViews()
            addView(
                RecyclerToggleBinding.inflate(layoutInflater, this, false).root.apply {
                    setIconResource(R.drawable.close)
                    contentDescription = context.getString(R.string.cancel)
                    setOnClickListener { initBottomMenu() }

                    updateLayoutParams<LinearLayout.LayoutParams> {
                        marginEnd = 0
                        marginStart = 10.dp
                    }
                    setControlsContrastColorForAllViews(extractColor)
                    setBackgroundColor(0)
                }
            )
        }
        binding.BottomAppBarLeft.apply {
            removeAllViews()
            updateLayoutParams<ConstraintLayout.LayoutParams> {
                endToStart = R.id.BottomAppBarRight
            }
            requestLayout()
            val layout = BottomTextFormattingMenuBinding.inflate(layoutInflater, this, false)
            layout.MainListView.apply {
                textFormattingAdapter =
                    TextFormattingAdapter(this@EditNoteActivity, binding.EnterBody, colorInt)
                adapter = textFormattingAdapter
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            }
            addView(layout.root)
        }
    }

    private fun setupMovementMethod() {
        val movementMethod = LinkMovementMethod { span ->
            val items =
                if (span.url.isNoteUrl()) {
                    if (canEdit) {
                        arrayOf(
                            getString(R.string.open_note),
                            getString(R.string.remove_link),
                            getString(R.string.change_note),
                            getString(R.string.edit),
                        )
                    } else arrayOf(getString(R.string.open_note))
                } else {
                    if (canEdit) {
                        arrayOf(
                            getString(R.string.open_link),
                            getString(R.string.copy),
                            getString(R.string.remove_link),
                            getString(R.string.edit),
                        )
                    } else arrayOf(getString(R.string.open_link), getString(R.string.copy))
                }
            MaterialAlertDialogBuilder(this)
                .setTitle(
                    if (span.url.isNoteUrl())
                        "${getString(R.string.note)}: ${
                            binding.EnterBody.getSpanText(span)
                        }"
                    else span.url
                )
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> openLink(span)
                        1 ->
                            if (span.url.isNoteUrl()) {
                                removeLink(span)
                            } else copyLink(span)
                        2 ->
                            if (span.url.isNoteUrl()) {
                                actionHandler.updateNoteLink(span)
                            } else removeLink(span)
                        3 -> editLink(span)
                    }
                }
                .show()
        }
        binding.EnterBody.movementMethod = movementMethod
    }

    private fun openLink(span: URLSpan) {
        span.url?.let {
            if (it.isNoteUrl()) {
                span.navigateToNote()
            } else {
                openLink(span.url)
            }
        }
    }

    private fun editLink(span: URLSpan) {
        binding.EnterBody.showEditDialog(span)
    }

    private fun copyLink(span: URLSpan) {
        copyToClipBoard(span.url)
        showToast(R.string.copied_link)
    }

    private fun removeLink(span: URLSpan) {
        binding.EnterBody.removeSpanWithHistory(
            span,
            span.url.isNoteUrl() || span.url == binding.EnterBody.getSpanText(span),
        )
    }

    private fun openLink(url: String) {
        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW, uri).wrapWithChooser(this)
        try {
            startActivity(intent)
        } catch (exception: Exception) {
            showToast(R.string.cant_open_link)
        }
    }

    private fun URLSpan.navigateToNote() {
        openNote(this.url.getNoteIdFromUrl(), this.url.getNoteTypeFromUrl())
    }

    companion object {
        private const val EXTRA_SELECTION_START = "notallyx.intent.extra.EXTRA_SELECTION_START"
        private const val EXTRA_SELECTION_END = "notallyx.intent.extra.EXTRA_SELECTION_END"
    }
}
