package dev.pocketedit

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(vm: EditorViewModel, onFinish: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val colors = if (isSystemInDarkTheme()) SyntaxColors.Dark else SyntaxColors.Light
    val doc = vm.doc
    val isMarkdown = doc.lang == Lang.MARKDOWN
    val showPreview = vm.preview && isMarkdown

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::onOpenPicked)
    }
    val saveAsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        vm.onSaveAsPicked(if (r.resultCode == Activity.RESULT_OK) r.data?.data else null)
    }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                UiEvent.LaunchOpenPicker -> try {
                    openLauncher.launch(arrayOf("*/*"))
                } catch (_: ActivityNotFoundException) {
                    snackbar.showSnackbar("No file picker available")
                }
                is UiEvent.LaunchSaveAs -> {
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(e.mime)
                        .putExtra(Intent.EXTRA_TITLE, e.suggestedName)
                    try { saveAsLauncher.launch(intent) } catch (_: ActivityNotFoundException) { }
                }
                is UiEvent.Message -> scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    val result = snackbar.showSnackbar(
                        e.text, actionLabel = e.actionLabel,
                        duration = if (e.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) e.action?.invoke()
                }
                UiEvent.Finish -> onFinish()
            }
        }
    }

    // Back: later handlers win, so the most specific ones come last.
    BackHandler(enabled = vm.isDirty) { vm.request(PendingAction.Exit) }
    BackHandler(enabled = showPreview) { vm.preview = false }
    BackHandler(enabled = vm.findVisible) { vm.findVisible = false }
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            Drawer(vm) { action -> scope.launch { drawerState.close() }; action() }
        },
    ) {
        Scaffold(
            modifier = Modifier.onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.S -> { if (e.isShiftPressed) vm.saveAs() else vm.save(); true }
                    Key.Z -> { if (e.isShiftPressed) vm.redo() else vm.undo(); true }
                    Key.Y -> { vm.redo(); true }
                    Key.F -> { vm.toggleFind(); true }
                    Key.O -> { vm.request(PendingAction.OpenPicker); true }
                    Key.N -> { vm.request(PendingAction.New); true }
                    else -> false
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, "Files")
                        }
                    },
                    title = {
                        Column {
                            Text(
                                (if (vm.isDirty) "• " else "") + doc.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                FileRepository.locationLabel(doc.uri),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    },
                    actions = { TopActions(vm, isMarkdown, showPreview) },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
            ) {
                if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (vm.findVisible && !showPreview) FindBar(vm)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (showPreview) MarkdownPreview(vm.value.text, vm.settings.fontSize, colors, Modifier.fillMaxSize())
                    else EditorField(vm, colors)
                }
                if (WindowInsets.isImeVisible && !showPreview) SymbolBar(vm)
                StatusLine(vm)
            }
        }
    }

    when (vm.dialog) {
        is EditorDialog.Unsaved -> AlertDialog(
            onDismissRequest = { vm.onUnsavedChoice(null) },
            title = { Text("Save changes?") },
            text = { Text("“${doc.name}” has unsaved changes.") },
            confirmButton = { TextButton(onClick = { vm.onUnsavedChoice(true) }) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.onUnsavedChoice(null) }) { Text("Cancel") }
                    TextButton(onClick = { vm.onUnsavedChoice(false) }) { Text("Discard") }
                }
            },
        )
        EditorDialog.Conflict -> AlertDialog(
            onDismissRequest = { vm.onConflictChoice(0) },
            title = { Text("File changed elsewhere") },
            text = {
                Text("“${doc.name}” was modified since you opened it (maybe on another device). " +
                    "Overwrite it with your version, or reload and lose your edits?")
            },
            confirmButton = { TextButton(onClick = { vm.onConflictChoice(1) }) { Text("Overwrite") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.onConflictChoice(0) }) { Text("Cancel") }
                    TextButton(onClick = { vm.onConflictChoice(2) }) { Text("Reload") }
                }
            },
        )
        null -> Unit
    }
}

@Composable
private fun TopActions(vm: EditorViewModel, isMarkdown: Boolean, showPreview: Boolean) {
    var menu by remember { mutableStateOf(false) }

    if (!showPreview) {
        IconButton(onClick = vm::undo, enabled = vm.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
        IconButton(onClick = vm::redo, enabled = vm.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
    }
    if (isMarkdown) {
        IconButton(onClick = { vm.preview = !vm.preview }) {
            Icon(if (showPreview) Icons.Default.Edit else Icons.Default.Visibility, if (showPreview) "Edit" else "Preview")
        }
    }
    IconButton(onClick = { vm.save() }) {
        Icon(
            Icons.Default.Save, "Save",
            tint = if (vm.isDirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            val s = vm.settings
            DropdownMenuItem(
                text = { Text("Find & replace") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                onClick = { menu = false; vm.preview = false; vm.toggleFind() },
            )
            DropdownMenuItem(
                text = { Text("Save as…") },
                leadingIcon = { Icon(Icons.Default.SaveAs, null) },
                onClick = { menu = false; vm.saveAs() },
            )
            if (vm.doc.uri != null) DropdownMenuItem(
                text = { Text("Reload from file") },
                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                onClick = { menu = false; vm.request(PendingAction.Reload) },
            )
            HorizontalDivider()
            CheckItem("Word wrap", Icons.AutoMirrored.Filled.WrapText, s.wordWrap) { vm.updateSettings { it.copy(wordWrap = !it.wordWrap) } }
            CheckItem("Monospace for notes", Icons.Default.TextFields, s.monospace) { vm.updateSettings { it.copy(monospace = !it.monospace) } }
            CheckItem("Syntax colours", Icons.Default.Palette, s.highlight) { vm.updateSettings { it.copy(highlight = !it.highlight) } }
            DropdownMenuItem(
                text = { Text("Text size ${s.fontSize}") },
                onClick = {},
                trailingIcon = {
                    Row {
                        IconButton(onClick = { vm.updateSettings { it.copy(fontSize = it.fontSize - 1) } }) {
                            Icon(Icons.Default.TextDecrease, "Smaller")
                        }
                        IconButton(onClick = { vm.updateSettings { it.copy(fontSize = it.fontSize + 1) } }) {
                            Icon(Icons.Default.TextIncrease, "Bigger")
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun CheckItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, null) },
        trailingIcon = { if (checked) Icon(Icons.Default.Check, "On") },
        onClick = onClick,
    )
}

@Composable
private fun Drawer(vm: EditorViewModel, onItem: (() -> Unit) -> Unit) {
    ModalDrawerSheet {
        Text("PocketEdit", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 28.dp, top = 24.dp, bottom = 12.dp))
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Add, null) },
            label = { Text("New file") },
            selected = false,
            onClick = { onItem { vm.request(PendingAction.New) } },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.FolderOpen, null) },
            label = { Text("Open… (phone or Drive)") },
            selected = false,
            onClick = { onItem { vm.request(PendingAction.OpenPicker) } },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            "Recent",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 28.dp, bottom = 4.dp),
        )
        if (vm.recent.isEmpty()) {
            Text(
                "Files you open show up here.\n\nTip: in the file picker, open the ☰ menu and pick Drive to edit files in Google Drive.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
        }
        LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(vm.recent, key = { it.uri }) { r ->
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Description, null) },
                    label = {
                        Column {
                            Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(r.location, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    selected = r.uri == vm.doc.uri?.toString(),
                    onClick = { onItem { vm.openRecent(r) } },
                    badge = {
                        IconButton(onClick = { vm.removeRecent(r) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, "Remove from recent", Modifier.size(18.dp))
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun EditorField(vm: EditorViewModel, colors: SyntaxColors) {
    val s = vm.settings
    val lang = vm.doc.lang
    val mono = lang.isCode || s.monospace
    val textColor = MaterialTheme.colorScheme.onSurface
    val textStyle = remember(mono, s.fontSize, textColor) {
        TextStyle(
            color = textColor,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            fontSize = s.fontSize.sp,
            lineHeight = (s.fontSize * 1.45f).sp,
        )
    }
    val matches = vm.matches
    val current = vm.currentMatch
    val transformation = remember(lang, colors, s.highlight, matches, current) {
        EditorTransformation(lang, colors, s.highlight, matches, current)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val outer = if (s.wordWrap) Modifier.fillMaxSize() else Modifier.fillMaxHeight().horizontalScroll(rememberScrollState())
        val field = if (s.wordWrap) Modifier.fillMaxSize() else Modifier.fillMaxHeight().widthIn(min = maxWidth)
        Box(outer) {
            BasicTextField(
                value = vm.value,
                onValueChange = vm::onValueChange,
                modifier = field,
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = transformation,
                keyboardOptions = KeyboardOptions(
                    capitalization = if (lang.isCode) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
                    autoCorrectEnabled = !lang.isCode,
                ),
                decorationBox = { inner ->
                    Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        if (vm.value.text.isEmpty()) {
                            Text("Start typing…", style = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)))
                        }
                        inner()
                    }
                },
            )
        }
    }
}

@Composable
private fun FindBar(vm: EditorViewModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallField(
                    vm.query, { vm.query = it }, "Find",
                    Modifier.weight(1f).focusRequester(focus),
                    imeAction = ImeAction.Search, onIme = vm::findNext,
                )
                val count = vm.matches.size
                Text(
                    when {
                        vm.query.isEmpty() -> ""
                        count == 0 -> "0"
                        vm.currentMatch < 0 -> "–/$count"
                        else -> "${vm.currentMatch + 1}/$count"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                IconButton(onClick = vm::findPrev, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.KeyboardArrowUp, "Previous") }
                IconButton(onClick = vm::findNext, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.KeyboardArrowDown, "Next") }
                TextButton(onClick = { vm.matchCase = !vm.matchCase }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("Aa", color = if (vm.matchCase) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { vm.findVisible = false }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Close, "Close find") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallField(vm.replacement, { vm.replacement = it }, "Replace with", Modifier.weight(1f))
                TextButton(onClick = vm::replaceCurrent) { Text("Replace") }
                TextButton(onClick = vm::replaceAll) { Text("All") }
            }
        }
    }
}

@Composable
private fun SmallField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
    onIme: () -> Unit = {},
) {
    val style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onSearch = { onIme() }, onDone = { onIme() }),
        modifier = modifier.height(40.dp),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                inner()
            }
        },
    )
}

private val SYMBOLS = listOf("TAB", "( )", "[ ]", "{ }", "< >", "\" \"", "' '", "` `", "#", "*", "-", "=", ":", ";", "/", "\\", "|", "&", "!", "?", "$", "_", "~", "%")

@Composable
private fun SymbolBar(vm: EditorViewModel) {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
        ) {
            SYMBOLS.forEach { sym ->
                TextButton(
                    onClick = { vm.insertSymbol(sym) },
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.height(40.dp),
                ) {
                    Text(
                        when (sym) { "TAB" -> "⇥"; else -> sym.replace(" ", "") },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLine(vm: EditorViewModel) {
    val text = vm.value.text
    val cursor = vm.value.selection.start.coerceIn(0, text.length)
    val (line, col) = remember(text, cursor) {
        var l = 1
        for (i in 0 until cursor) if (text[i] == '\n') l++
        l to (cursor - (text.lastIndexOf('\n', cursor - 1) + 1) + 1)
    }
    val doc = vm.doc
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = MaterialTheme.typography.labelSmall
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        Text("Ln $line, Col $col", style = style, color = color)
        Spacer(Modifier.width(12.dp))
        Text("${text.length} chars", style = style, color = color)
        Spacer(Modifier.weight(1f))
        Text(
            listOf(
                doc.lang.name.lowercase().replace('_', '-').replaceFirstChar { it.uppercase() },
                doc.charset.replace("ISO-8859-1", "Latin-1") + if (doc.bom) " BOM" else "",
                doc.lineEnding.label,
            ).joinToString(" · "),
            style = style, color = color,
        )
    }
}
