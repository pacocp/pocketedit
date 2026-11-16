# PocketEdit

A small, fast text editor for Android for notes, Markdown and code. It opens files from your phone **or Google Drive** and saves them back to the same place.

## How it reaches Google Drive (no API keys needed)

PocketEdit uses Android's Storage Access Framework. The system file picker already lists Google Drive (through the Drive app), so:

- **Open…** → in the picker, tap ☰ → **Drive** → pick a file.
- **Save** writes back to that exact file, wherever it lives.
- In the Drive app you can also use **⋮ → Open with → PocketEdit**.

You need the Google Drive app installed and signed in. Only real text files (`.txt`, `.md`, `.py`, `.json`…) can be edited. Google Docs files are not text files.

## Features

- Open / New / Save / Save as, with a recent-files list (persistent permissions, so recents keep working after a restart)
- Writes back safely: tries `"wt"` (truncate) → `"rw"` + truncate → `"w"` with a size check
- Keeps each file's **line endings** (LF/CRLF), **UTF-8 BOM**, and encoding (Latin-1 files round-trip unchanged)
- **Conflict check**: if the file changed elsewhere (e.g. edited on your laptop via Drive), it asks whether to overwrite or reload
- **Never loses work**: unsaved changes are stashed when the app goes to the background and restored if Android kills it
- Undo / redo (grouped by typing pauses), find & replace with match count and case toggle
- Syntax colours for Markdown and common languages (Kotlin/Java/JS/TS/C/Go/Rust/Swift, Python, shell, YAML/TOML, SQL, XML/HTML, JSON)
- Markdown **preview** (headings, lists, checkboxes, quotes, code blocks, tables, links)
- Smart Enter: keeps indentation, indents after `{ [ (` (and `:` in Python), continues Markdown lists and ends them on an empty bullet
- A symbol row above the keyboard: ⇥ ( ) [ ] { } < > " ' ` # * - = : ; / \ | & ! ? $ _ ~ % (pairs wrap the selection)
- Word wrap toggle, font size, monospace option, light/dark with Material You colours
- Status line: line/column, character count, language, encoding, line endings
- Hardware keyboard: Ctrl+S, Ctrl+Shift+S, Ctrl+Z, Ctrl+Shift+Z / Ctrl+Y, Ctrl+F, Ctrl+O, Ctrl+N

Limits: files up to 2 MB. Syntax colouring turns off above 150k characters to keep typing smooth.

## Build & install

### Option A: Android Studio (easiest)
1. Install Android Studio and open this folder.
2. Let it sync (it downloads the Android SDK and Gradle the first time).
3. Plug in your phone with USB debugging on, then press ▶ Run.

### Option B: no computer setup, using GitHub Actions
1. Push this folder to a GitHub repo (private is fine).
2. The **Build APK** workflow runs automatically. Open the run and download the `PocketEdit-apk` artifact.
3. Copy the APK to your phone, open it, and allow "install unknown apps" when asked.

The release build is signed with the debug key, which is fine for personal use. Each CI run uses a fresh debug key, though, so to update an install from a *different* CI run you may need to uninstall first. Android Studio builds on one machine keep the same key.

## Project layout

```
app/src/main/java/dev/pocketedit/
  MainActivity.kt      entry point, "Open with" intents, theme
  EditorScreen.kt      Compose UI: top bar, drawer, editor, find bar, symbol row, dialogs
  EditorViewModel.kt   state: editing, undo/redo, find/replace, open/save/conflicts, drafts
  FileRepository.kt    SAF read/write, encoding & line-ending handling
  Highlighter.kt       regex-based syntax colouring as a VisualTransformation
  MarkdownPreview.kt   tiny Markdown parser + renderer
  Lang.kt              file-extension → language family
  Prefs.kt             settings, recent files, draft store
```

Stack: Kotlin 2.1, Jetpack Compose (Material 3), AGP 8.7, minSdk 26 (Android 8), targetSdk 35. No third-party libraries.
