// Copyright 2026 Ernesto Gonzalez. Licensed under the Apache License, Version 2.0.
// See the LICENSE and NOTICE files in the project root.

package dev.umikami.coloredbrackets

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.ColorUtil
import com.intellij.util.xmlb.XmlSerializerUtil
import java.awt.Color

@Service(Service.Level.APP)
@State(name = "CppColoredBracketsSettings", storages = [Storage("cppColoredBrackets.xml")])
class ColoredBracketsSettings : PersistentStateComponent<ColoredBracketsSettings.Data> {
    class Data {
        var enabled: Boolean = true
        var roundBrackets: Boolean = true
        var squareBrackets: Boolean = true
        var curlyBrackets: Boolean = true
        var angleBrackets: Boolean = false

        /** Reuse the color list for levels deeper than the list is long. Off: deeper levels stay uncolored. */
        var cycleColors: Boolean = false
        var highlightUnmatched: Boolean = true

        /** Comma-separated #RRGGBB, outermost level first. Empty means the theme's defaults. */
        var bracketColors: String = ""

        var indentsEnabled: Boolean = false
        var indentsUseBracketColors: Boolean = true
        var indentColors: String = ""

        /** Indent tint strength, 1..100 percent. */
        var indentOpacity: Int = 15

        /** Columns per indent level. 0 means detect it from the file. */
        var indentWidth: Int = 0
    }

    private val data = Data()

    override fun getState(): Data = data

    override fun loadState(state: Data) {
        XmlSerializerUtil.copyBean(state, data)
    }

    fun bracketColors(): List<Color> = parseColors(data.bracketColors) ?: defaultColors()

    fun indentColors(): List<Color> =
        if (data.indentsUseBracketColors) bracketColors() else parseColors(data.indentColors) ?: defaultColors()

    fun enabledTypes(): Set<BracketType> = buildSet {
        if (data.roundBrackets) add(BracketType.ROUND)
        if (data.squareBrackets) add(BracketType.SQUARE)
        if (data.curlyBrackets) add(BracketType.CURLY)
        if (data.angleBrackets) add(BracketType.ANGLE)
    }

    /** Call after changing [state]: repaints open editors and the status bar widget. */
    fun changed() {
        ColoredBracketsService.getInstance().refreshAll()
        for (project in ProjectManager.getInstance().openProjects) {
            WindowManager.getInstance().getStatusBar(project)?.updateWidget(ColoredBracketsStatusBarWidget.ID)
        }
    }

    companion object {
        fun getInstance(): ColoredBracketsSettings = service()

        // Gold, orchid and blue come from VS Code's bracket pair colorization; the fourth is added to fit.
        private val DARK_DEFAULTS = listOf(Color(0xFFD700), Color(0xDA70D6), Color(0x179FFF), Color(0x3DD68C))
        private val LIGHT_DEFAULTS = listOf(Color(0x0431FA), Color(0x319331), Color(0x7B3814), Color(0xC2185B))

        fun defaultColors(): List<Color> {
            val bg = EditorColorsManager.getInstance().globalScheme.defaultBackground
            return if (ColorUtil.isDark(bg)) DARK_DEFAULTS else LIGHT_DEFAULTS
        }

        fun formatColors(colors: List<Color>): String =
            if (colors == defaultColors()) "" else colors.joinToString(",") { "#%06X".format(it.rgb and 0xFFFFFF) }

        fun parseColors(s: String): List<Color>? {
            if (s.isBlank()) return null
            val colors = s.split(',').mapNotNull { part ->
                part.trim().removePrefix("#").toIntOrNull(16)?.let { Color(it) }
            }
            return colors.ifEmpty { null }
        }
    }
}
