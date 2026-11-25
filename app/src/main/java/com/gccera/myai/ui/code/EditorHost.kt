package com.gccera.myai.ui.code

import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.gccera.myai.data.code.CodeLanguages
import com.gccera.myai.data.settings.ThemeMode
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.SymbolInputView
import org.eclipse.tm4e.core.registry.IThemeSource

/** One-time TextMate registry setup: grammars + GitHub light/dark themes from assets/textmate. */
object TextMateSetup {
    @Volatile private var done = false

    @Synchronized
    fun ensure(context: Context) {
        if (done) return
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(context.applicationContext.assets))
        val themes = ThemeRegistry.getInstance()
        for ((file, dark) in listOf("github-light" to false, "github-dark" to true)) {
            val path = "textmate/themes/$file.json"
            val src = IThemeSource.fromInputStream(FileProviderRegistry.getInstance().tryGetInputStream(path), path, null)
            themes.loadTheme(ThemeModel(src, file).apply { isDark = dark })
        }
        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
        done = true
    }
}

/** Lets Compose buttons (undo, find, AI…) talk to the editor View. */
class EditorRef {
    var editor: CodeEditor? = null

    /** Selected text with its range [startLine, startCol, endLine, endCol], or null when nothing is selected. */
    fun selection(): Pair<String, IntArray>? {
        val e = editor ?: return null
        val c = e.cursor
        if (!c.isSelected) return null
        val r = intArrayOf(c.leftLine, c.leftColumn, c.rightLine, c.rightColumn)
        return e.text.subContent(r[0], r[1], r[2], r[3]).toString() to r
    }

    fun cursorIndex(): Int = editor?.cursor?.left ?: 0
}

@Composable
fun EditorHost(
    tab: OpenTab?,
    themeMode: ThemeMode,
    ref: EditorRef,
    onEdited: (cursorIndex: Int) -> Unit,
    onCursorMoved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = when (themeMode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val state = remember { object { var path: String? = null; var dark: Boolean? = null } }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextMateSetup.ensure(ctx)
            CodeEditor(ctx).apply {
                setTypefaceText(Typeface.MONOSPACE)
                setTextSize(14f)
                setLineNumberEnabled(true)
                setTabWidth(4)
                setWordwrap(false)
                colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                subscribeAlways(ContentChangeEvent::class.java) { onEdited(cursor.left) }
                subscribeAlways(SelectionChangeEvent::class.java) { onCursorMoved() }
                ref.editor = this
            }
        },
        update = { editor ->
            if (state.dark != dark) {
                ThemeRegistry.getInstance().setTheme(if (dark) "github-dark" else "github-light")
                state.dark = dark
            }
            if (tab == null) {
                if (state.path != null) { editor.setText(""); state.path = null }
                editor.isEditable = false
            } else if (state.path != tab.path) {
                editor.isEditable = true
                // Passing the tab's Content keeps its own undo/redo history.
                editor.setText(tab.content)
                val scope = CodeLanguages.of(tab.path)?.second
                editor.setEditorLanguage(
                    if (scope != null) runCatching { TextMateLanguage.create(scope, true) }.getOrElse { EmptyLanguage() } else EmptyLanguage()
                )
                state.path = tab.path
            }
        },
    )
    DisposableEffect(Unit) { onDispose { ref.editor?.release(); ref.editor = null } }
}

/** Extra-keys row (Sora's SymbolInputView): symbols that are awkward on phone keyboards. */
@Composable
fun SymbolRow(ref: EditorRef, textColor: Int, modifier: Modifier = Modifier) {
    AndroidView(modifier = modifier, factory = { ctx ->
        SymbolInputView(ctx).apply {
            val shown = arrayOf("⇥", "{", "}", "(", ")", "[", "]", ";", ":", "=", "\"", "'", "<", ">", "/", "\\", "|", "&", "!", "?", "+", "-", "*", "_", "#", "$", "%", "`", ".", ",")
            val inserted = shown.map { if (it == "⇥") "\t" else it }.toTypedArray()
            addSymbols(shown, inserted)
            setTextColor(textColor)
            ref.editor?.let { bindEditor(it) }
        }
    }, update = { v -> ref.editor?.let { v.bindEditor(it) } })
}
