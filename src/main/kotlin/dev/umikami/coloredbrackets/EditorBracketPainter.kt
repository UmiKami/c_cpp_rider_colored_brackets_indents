// Copyright 2026 Ernesto Gonzalez. Licensed under the Apache License, Version 2.0.
// See the LICENSE and NOTICE files in the project root.

package dev.umikami.coloredbrackets

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.util.Alarm
import java.awt.Color

/**
 * Colors brackets and indents in one editor. The text is scanned on a background thread a moment after
 * each change, then the editor's highlighters are updated, reusing the ones that didn't change.
 */
class EditorBracketPainter(private val editor: Editor) : Disposable {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var current = HashMap<Key, RangeHighlighter>()
    private var generation = 0
    @Volatile private var disposed = false

    private data class Key(val start: Int, val end: Int, val rgb: Int, val indent: Boolean)

    private class Snapshot(
        val text: CharSequence,
        val stamp: Long,
        val types: Set<BracketType>,
        val indents: Boolean,
        val tabSize: Int,
        val indentWidth: Int,
        val fallbackIndent: Int,
    )

    private class Result(val brackets: List<BracketHit>, val indents: List<IndentHit>)

    init {
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = schedule(DELAY_MS)
        }, this)
        schedule(0)
    }

    fun schedule(delayMs: Int) {
        if (disposed) return
        alarm.cancelAllRequests()
        alarm.addRequest(::recompute, delayMs)
    }

    private fun recompute() {
        if (disposed || editor.isDisposed) return
        val settings = ColoredBracketsSettings.getInstance().state
        val document = editor.document
        if (document.textLength > MAX_FILE_CHARS) { apply(Result(emptyList(), emptyList())); return }
        val project = editor.project
        val tabSize = editor.settings.getTabSize(project)
        val snapshot = Snapshot(
            text = document.immutableCharSequence,
            stamp = document.modificationStamp,
            types = if (settings.enabled) ColoredBracketsSettings.getInstance().enabledTypes() else emptySet(),
            indents = settings.indentsEnabled,
            tabSize = tabSize,
            indentWidth = settings.indentWidth,
            fallbackIndent = project?.let { CodeStyle.getIndentOptions(it, document).INDENT_SIZE } ?: tabSize,
        )
        val gen = ++generation
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = compute(snapshot)
            ApplicationManager.getApplication().invokeLater({
                if (!disposed && !editor.isDisposed && gen == generation &&
                    editor.document.modificationStamp == snapshot.stamp
                ) apply(result)
            }, ModalityState.any())
        }
    }

    private fun compute(s: Snapshot): Result {
        val brackets = CppBracketScanner.scan(s.text, s.types)
        val indents = if (s.indents) {
            val width = s.indentWidth.takeIf { it > 0 }
                ?: CppBracketScanner.detectIndentSize(s.text, s.tabSize).takeIf { it > 0 }
                ?: s.fallbackIndent
            CppBracketScanner.indents(s.text, s.tabSize, width)
        } else emptyList()
        return Result(brackets, indents)
    }

    private fun apply(result: Result) {
        val settings = ColoredBracketsSettings.getInstance()
        val state = settings.state
        val bracketColors = settings.bracketColors()
        val indentColors = settings.indentColors()
        val background = editor.colorsScheme.defaultBackground
        val alpha = state.indentOpacity.coerceIn(1, 100) / 100f
        val markup = editor.markupModel

        val old = HashMap<Key, RangeHighlighter>(current.size)
        for ((key, h) in current) {
            if (!h.isValid) { markup.removeHighlighter(h); continue }
            // Edits can collapse two highlighters onto the same range; keep one.
            old.put(Key(h.startOffset, h.endOffset, key.rgb, key.indent), h)?.let(markup::removeHighlighter)
        }
        val next = HashMap<Key, RangeHighlighter>()
        val attributes = HashMap<Key, TextAttributes>()
        val length = editor.document.textLength

        fun put(start: Int, end: Int, color: Color, indent: Boolean) {
            if (end > length) return
            val key = Key(start, end, color.rgb, indent)
            if (key in next) return
            val reused = old.remove(key)
            if (reused != null) { next[key] = reused; return }
            val attrs = attributes.getOrPut(Key(0, 0, color.rgb, indent)) {
                if (indent) TextAttributes(null, color, null, null, 0) else TextAttributes(color, null, null, null, 0)
            }
            val layer = if (indent) INDENT_LAYER else BRACKET_LAYER
            next[key] = markup.addRangeHighlighter(start, end, layer, attrs, HighlighterTargetArea.EXACT_RANGE)
        }

        for (hit in result.brackets) {
            val color = when {
                hit.unmatched -> if (state.highlightUnmatched) UNMATCHED else null
                hit.level < bracketColors.size -> bracketColors[hit.level]
                state.cycleColors -> bracketColors[hit.level % bracketColors.size]
                else -> null
            } ?: continue
            put(hit.offset, hit.offset + 1, color, indent = false)
        }
        if (result.indents.isNotEmpty()) {
            val tints = indentColors.map { blend(it, background, alpha) }
            for (hit in result.indents) put(hit.start, hit.end, tints[hit.level % tints.size], indent = true)
        }

        for (h in old.values) markup.removeHighlighter(h)
        current = next
    }

    override fun dispose() {
        disposed = true
        if (!editor.isDisposed) for (h in current.values) editor.markupModel.removeHighlighter(h)
        current.clear()
    }

    private companion object {
        const val DELAY_MS = 120
        const val MAX_FILE_CHARS = 8_000_000

        // Above the backend's syntax highlighting so bracket colors win, below selection so selected text still reads.
        const val BRACKET_LAYER = HighlighterLayer.SELECTION - 1
        const val INDENT_LAYER = HighlighterLayer.ADDITIONAL_SYNTAX
        val UNMATCHED = Color(0xFF4040)

        fun blend(c: Color, bg: Color, a: Float): Color = Color(
            (c.red * a + bg.red * (1 - a)).toInt(),
            (c.green * a + bg.green * (1 - a)).toInt(),
            (c.blue * a + bg.blue * (1 - a)).toInt(),
        )
    }
}
