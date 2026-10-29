// Copyright 2026 Ernesto Gonzalez. Licensed under the Apache License, Version 2.0.
// See the LICENSE and NOTICE files in the project root.

package dev.umikami.coloredbrackets

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer

/** Tracks one [EditorBracketPainter] per open C/C++ editor. */
@Service(Service.Level.APP)
class ColoredBracketsService : Disposable {
    private val painters = HashMap<Editor, EditorBracketPainter>()

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(EditorColorsManager.TOPIC, EditorColorsListener { refreshAll() })
        // Pick up editors that were already open when the plugin was loaded.
        ApplicationManager.getApplication().invokeLater {
            for (editor in EditorFactory.getInstance().allEditors) attach(editor)
        }
    }

    fun attach(editor: Editor) {
        if (editor.isDisposed || editor in painters) return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: editor.virtualFile ?: return
        if (!CppFiles.isCpp(file.name)) return
        val painter = EditorBracketPainter(editor)
        painters[editor] = painter
        Disposer.register(this, painter)
    }

    fun detach(editor: Editor) {
        painters.remove(editor)?.let(Disposer::dispose)
    }

    fun refreshAll() {
        for (painter in painters.values) painter.schedule(0)
    }

    override fun dispose() {
        painters.clear()
    }

    companion object {
        fun getInstance(): ColoredBracketsService = service()
    }
}

class ColoredBracketsEditorFactoryListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        ColoredBracketsService.getInstance().attach(event.editor)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        ColoredBracketsService.getInstance().detach(event.editor)
    }
}

object CppFiles {
    private val EXTENSIONS = setOf(
        "c", "cc", "cpp", "cxx", "c++", "cp", "cppm", "ixx", "ino",
        "h", "hh", "hpp", "hxx", "h++", "inl", "ipp", "tpp", "tcc", "inc",
    )

    fun isCpp(fileName: String): Boolean {
        val dot = fileName.lastIndexOf('.')
        return dot >= 0 && fileName.substring(dot + 1).lowercase() in EXTENSIONS
    }
}
