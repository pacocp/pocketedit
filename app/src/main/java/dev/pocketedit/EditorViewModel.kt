package dev.pocketedit

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Things that need to happen after the "unsaved changes?" question is answered. */
sealed interface PendingAction {
    data object New : PendingAction
    data object OpenPicker : PendingAction
    data class OpenUri(val uri: Uri) : PendingAction
    data object Reload : PendingAction
    data object Exit : PendingAction
}

sealed interface EditorDialog {
    data class Unsaved(val then: PendingAction) : EditorDialog
    data object Conflict : EditorDialog
}

sealed interface UiEvent {
    data object LaunchOpenPicker : UiEvent
    data class LaunchSaveAs(val suggestedName: String, val mime: String) : UiEvent
    data class Message(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null) : UiEvent
    data object Finish : UiEvent
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = FileRepository(app)
    private val prefs = Prefs(app)
    private val drafts = DraftStore(app)

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    var initialized = false

    // ---------- document state ----------

    var value by mutableStateOf(TextFieldValue(""))
        private set
    var doc by mutableStateOf(DocInfo.untitled())
        private set
    private var savedText by mutableStateOf("")
    val isDirty by derivedStateOf { value.text != savedText }

    var busy by mutableStateOf(false)
        private set
    var dialog by mutableStateOf<EditorDialog?>(null)
        private set
    var settings by mutableStateOf(prefs.loadSettings())
        private set
    var recent by mutableStateOf(prefs.loadRecent())
        private set
    var preview by mutableStateOf(false)

    /** Action to run once a "Save as" (triggered from the unsaved dialog) succeeds. */
    private var afterSaveAs: PendingAction? = null

    // ---------- undo / redo ----------

    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()
    private var undoSize by mutableIntStateOf(0)
    private var redoSize by mutableIntStateOf(0)
    val canUndo get() = undoSize > 0
    val canRedo get() = redoSize > 0
    private var lastEditAt = 0L
    private var lastSnapshotAt = 0L

    private fun syncStackSizes() {
        undoSize = undoStack.size; redoSize = redoStack.size
    }

    private fun snapshot(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        // A new undo step starts after a 1s pause, every 4s of continuous typing, or when forced.
        if (force || undoStack.isEmpty() || now - lastEditAt > 1000 || now - lastSnapshotAt > 4000) {
            undoStack.addLast(value)
            if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
            lastSnapshotAt = now
        }
        lastEditAt = now
        redoStack.clear()
        syncStackSizes()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(value)
        value = prev
        lastEditAt = 0
        syncStackSizes()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(value)
        value = next
        lastEditAt = 0
        syncStackSizes()
    }

    private fun resetHistory() {
        undoStack.clear(); redoStack.clear(); lastEditAt = 0; syncStackSizes()
    }

    // ---------- editing ----------

    fun onValueChange(new: TextFieldValue) {
        if (new.text == value.text) {
            value = new // selection / composition change only
            return
        }
        snapshot()
        value = autoIndent(value, new) ?: new
    }

    /** Keeps indentation on Enter; continues Markdown lists (and ends them on an empty item). */
    private fun autoIndent(old: TextFieldValue, new: TextFieldValue): TextFieldValue? {
        val t = new.text
        val cur = new.selection.start
        if (!new.selection.collapsed || t.length != old.text.length + 1 || cur <= 0 || t[cur - 1] != '\n') return null
        if (!t.regionMatches(0, old.text, 0, cur - 1) || !t.regionMatches(cur, old.text, cur - 1, t.length - cur)) return null

        val lineStart = t.lastIndexOf('\n', cur - 2) + 1
        val prevLine = t.substring(lineStart, cur - 1)
        val indent = prevLine.takeWhile { it == ' ' || it == '\t' }

        var insert = indent
        if (doc.lang == Lang.MARKDOWN) {
            val m = LIST_ITEM.find(prevLine)
            if (m != null) {
                val g = m.groupValues
                val ind = g[1]
                val box = g[5]
                val rest = g[6]
                if (rest.isBlank()) {
                    // Enter on an empty bullet: remove the bullet instead of adding another.
                    val text = t.removeRange(lineStart, cur)
                    return TextFieldValue(text, TextRange(lineStart))
                }
                val marker = if (g[2].isNotEmpty()) g[2] else "${(g[3].toIntOrNull() ?: 0) + 1}${g[4]}"
                insert = ind + marker + " " + if (box.isNotEmpty()) "[ ] " else ""
            }
        } else if (doc.lang.isCode) {
            val trimmed = prevLine.trimEnd()
            val opens = trimmed.endsWith("{") || trimmed.endsWith("[") || trimmed.endsWith("(") ||
                (doc.lang == Lang.PYTHON && trimmed.endsWith(":"))
            if (opens) insert += indentUnit()
        }
        if (insert.isEmpty()) return null
        val text = t.substring(0, cur) + insert + t.substring(cur)
        return TextFieldValue(text, TextRange(cur + insert.length))
    }

    private fun indentUnit(): String =
        if (Regex("""(?m)^\t""").containsMatchIn(value.text.take(20_000))) "\t" else "    "

    /** Inserts text from the symbol bar. Pairs wrap the selection or put the cursor in between. */
    fun insertSymbol(sym: String) {
        val v = value
        val sel = v.selection
        val (start, end) = sel.min to sel.max
        val pair = PAIRS[sym]
        val (text, cursor) = when {
            sym == "TAB" -> {
                val unit = indentUnit()
                (v.text.replaceRange(start, end, unit)) to TextRange(start + unit.length)
            }
            pair != null -> {
                val inner = v.text.substring(start, end)
                v.text.replaceRange(start, end, pair.first + inner + pair.second) to
                    if (inner.isEmpty()) TextRange(start + pair.first.length)
                    else TextRange(start + pair.first.length, start + pair.first.length + inner.length)
            }
            else -> v.text.replaceRange(start, end, sym) to TextRange(start + sym.length)
        }
        snapshot(force = true)
        value = TextFieldValue(text, cursor)
    }

    // ---------- find & replace ----------

    var findVisible by mutableStateOf(false)
    var query by mutableStateOf("")
    var replacement by mutableStateOf("")
    var matchCase by mutableStateOf(false)

    val matches: List<IntRange> by derivedStateOf {
        if (!findVisible || query.isEmpty()) emptyList() else {
            val text = value.text
            val out = ArrayList<IntRange>()
            var i = text.indexOf(query, 0, !matchCase)
            while (i >= 0 && out.size < MAX_MATCHES) {
                out += i until i + query.length
                i = text.indexOf(query, i + query.length, !matchCase)
            }
            out
        }
    }

    val currentMatch: Int by derivedStateOf {
        val s = value.selection
        matches.indexOfFirst { it.first == s.min && it.last + 1 == s.max }
    }

    fun toggleFind() {
        findVisible = !findVisible
        if (findVisible) {
            val s = value.selection
            if (!s.collapsed && s.length < 200) query = value.text.substring(s.min, s.max)
        }
    }

    fun findNext() {
        val m = matches
        if (m.isEmpty()) return
        val idx = currentMatch
        val next = if (idx >= 0) m[(idx + 1) % m.size]
        else m.firstOrNull { it.first >= value.selection.min } ?: m.first()
        select(next)
    }

    fun findPrev() {
        val m = matches
        if (m.isEmpty()) return
        val idx = currentMatch
        val prev = if (idx >= 0) m[(idx - 1 + m.size) % m.size]
        else m.lastOrNull { it.first < value.selection.min } ?: m.last()
        select(prev)
    }

    private fun select(r: IntRange) {
        value = value.copy(selection = TextRange(r.first, r.last + 1), composition = null)
    }

    fun replaceCurrent() {
        val idx = currentMatch
        if (idx < 0) { findNext(); return }
        val r = matches[idx]
        snapshot(force = true)
        val text = value.text.replaceRange(r.first, r.last + 1, replacement)
        value = TextFieldValue(text, TextRange(r.first + replacement.length))
        findNext()
    }

    fun replaceAll() {
        val m = matches
        if (m.isEmpty()) return
        snapshot(force = true)
        val text = value.text
        val sb = StringBuilder()
        var last = 0
        for (r in m) {
            sb.append(text, last, r.first).append(replacement); last = r.last + 1
        }
        sb.append(text, last, text.length)
        value = TextFieldValue(sb.toString(), TextRange(0))
        message("Replaced ${m.size} occurrence${if (m.size == 1) "" else "s"}")
    }

    // ---------- settings ----------

    fun updateSettings(transform: (Settings) -> Settings) {
        settings = transform(settings).let { it.copy(fontSize = it.fontSize.coerceIn(10, 32)) }
        prefs.saveSettings(settings)
    }

    // ---------- open / new / exit (guarded by unsaved-changes check) ----------

    fun request(action: PendingAction) {
        if (isDirty) dialog = EditorDialog.Unsaved(action) else perform(action)
    }

    private fun perform(action: PendingAction) {
        when (action) {
            PendingAction.New -> {
                setDocument("", DocInfo.untitled())
                prefs.lastUri = null
            }
            PendingAction.OpenPicker -> send(UiEvent.LaunchOpenPicker)
            is PendingAction.OpenUri -> load(action.uri)
            PendingAction.Reload -> doc.uri?.let { load(it) }
            PendingAction.Exit -> {
                savedText = value.text // changes were saved or deliberately discarded
                drafts.clear()
                send(UiEvent.Finish)
            }
        }
    }

    fun onUnsavedChoice(save: Boolean?) {
        val d = dialog as? EditorDialog.Unsaved ?: return
        dialog = null
        when (save) {
            null -> Unit // cancel
            false -> perform(d.then) // discard: perform() doesn't re-check dirtiness
            true -> {
                if (doc.uri == null) {
                    afterSaveAs = d.then
                    saveAs()
                } else {
                    save(then = d.then)
                }
            }
        }
    }

    fun onOpenPicked(uri: Uri) {
        repo.takePermission(uri)
        load(uri)
    }

    /** "Open with" from another app. */
    fun openExternal(uri: Uri) {
        repo.takePermission(uri)
        request(PendingAction.OpenUri(uri))
    }

    fun openRecent(r: RecentFile) = request(PendingAction.OpenUri(Uri.parse(r.uri)))

    fun removeRecent(r: RecentFile) {
        repo.releasePermission(Uri.parse(r.uri))
        recent = prefs.removeRecent(r.uri)
    }

    private fun load(uri: Uri, quiet: Boolean = false) {
        viewModelScope.launch {
            busy = true
            try {
                val (text, info) = withContext(Dispatchers.IO) { repo.read(uri) }
                setDocument(text, info)
                rememberRecent(info)
                preview = false
            } catch (e: Exception) {
                if (!quiet) message(errorText(e, "open"))
                if (e is SecurityException || e is java.io.FileNotFoundException) {
                    recent = prefs.removeRecent(uri.toString())
                }
            } finally {
                busy = false
            }
        }
    }

    private fun setDocument(text: String, info: DocInfo) {
        doc = info
        savedText = text
        value = TextFieldValue(text, TextRange(0))
        resetHistory()
    }

    private fun rememberRecent(info: DocInfo) {
        val uri = info.uri ?: return
        val (kept, dropped) = prefs.addRecent(uri, info.name)
        dropped.forEach { repo.releasePermission(Uri.parse(it)) }
        recent = kept
        prefs.lastUri = uri.toString()
    }

    // ---------- save ----------

    fun save(then: PendingAction? = null, force: Boolean = false) {
        if (doc.uri == null) { afterSaveAs = then; saveAs(); return }
        if (busy) return
        val textToSave = value.text
        val info = doc
        viewModelScope.launch {
            busy = true
            try {
                val conflict = !force && withContext(Dispatchers.IO) { changedElsewhere(info) }
                if (conflict) {
                    afterSaveAs = then
                    dialog = EditorDialog.Conflict
                    return@launch
                }
                val newInfo = withContext(Dispatchers.IO) { repo.write(textToSave, info) }
                doc = newInfo
                savedText = textToSave
                if (newInfo.charset != info.charset) message("Saved as UTF-8 (new characters didn't fit ${info.charset})")
                else message("Saved to ${FileRepository.locationLabel(newInfo.uri)}")
                then?.let(::perform)
            } catch (e: Exception) {
                if (e is SecurityException) {
                    message("This file is read-only for PocketEdit.", "Save as") { saveAs() }
                } else {
                    message(errorText(e, "save"), "Save as") { saveAs() }
                }
            } finally {
                busy = false
            }
        }
    }

    /** True if the file on disk/Drive no longer matches what we loaded or last saved. */
    private fun changedElsewhere(info: DocInfo): Boolean {
        val uri = info.uri ?: return false
        val now = repo.queryMeta(uri).lastModified
        if (now == null || info.lastModified == null || now == info.lastModified) return false
        // Timestamps differ — Drive sometimes bumps them on sync, so compare the actual content.
        return try {
            repo.readTextOnly(uri) != savedText
        } catch (_: Exception) {
            false
        }
    }

    fun onConflictChoice(choice: Int) { // 0 cancel, 1 overwrite, 2 reload
        dialog = null
        val then = afterSaveAs
        afterSaveAs = null
        when (choice) {
            1 -> save(then = then, force = true)
            2 -> doc.uri?.let { load(it) }
        }
    }

    fun saveAs() {
        send(UiEvent.LaunchSaveAs(doc.name, FileRepository.mimeFor(doc.name)))
    }

    fun onSaveAsPicked(uri: Uri?) {
        if (uri == null) { afterSaveAs = null; return }
        repo.takePermission(uri)
        val textToSave = value.text
        val base = doc
        viewModelScope.launch {
            busy = true
            try {
                val newInfo = withContext(Dispatchers.IO) {
                    val name = repo.displayName(uri)
                    repo.write(textToSave, base.copy(uri = uri, name = name, lastModified = null))
                }
                doc = newInfo
                savedText = textToSave
                rememberRecent(newInfo)
                message("Saved to ${FileRepository.locationLabel(uri)}")
                afterSaveAs?.let(::perform)
            } catch (e: Exception) {
                message(errorText(e, "save"))
            } finally {
                afterSaveAs = null
                busy = false
            }
        }
    }

    // ---------- session persistence ----------

    fun persistSession() {
        val text = value.text
        val dirty = isDirty
        val draft = Draft(doc, text, savedText)
        viewModelScope.launch(Dispatchers.IO) {
            if (dirty) drafts.save(draft) else drafts.clear()
        }
    }

    fun restoreSession() {
        viewModelScope.launch {
            val draft = withContext(Dispatchers.IO) { drafts.load() }
            if (draft != null && draft.text != draft.savedText) {
                doc = draft.info
                savedText = draft.savedText
                value = TextFieldValue(draft.text, TextRange(0))
                resetHistory()
                message("Restored unsaved changes")
                return@launch
            }
            prefs.lastUri?.let { load(Uri.parse(it), quiet = true) }
        }
    }

    // ---------- helpers ----------

    private fun send(e: UiEvent) {
        _events.trySend(e)
    }

    private fun message(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) =
        send(UiEvent.Message(text, actionLabel, action))

    private fun errorText(e: Exception, verb: String) = when (e) {
        is FileTooLargeException, is BinaryFileException -> e.message ?: "Can't $verb this file"
        is SecurityException -> "Permission denied — pick the file again from Open"
        is java.io.FileNotFoundException -> "File not found (moved or deleted?)"
        else -> "Couldn't $verb: ${e.message ?: e.javaClass.simpleName}"
    }

    companion object {
        private const val MAX_UNDO = 300
        private const val MAX_MATCHES = 10_000
        // 1 indent, 2 bullet, 3 number, 4 delimiter, 5 checkbox, 6 rest
        private val LIST_ITEM = Regex("""^(\s*)(?:([-*+])|(\d+)([.)]))\s+(\[[ xX]\]\s+)?(.*)$""")
        val PAIRS = mapOf(
            "( )" to ("(" to ")"), "[ ]" to ("[" to "]"), "{ }" to ("{" to "}"),
            "< >" to ("<" to ">"), "\" \"" to ("\"" to "\""), "' '" to ("'" to "'"), "` `" to ("`" to "`"),
        )
    }
}
