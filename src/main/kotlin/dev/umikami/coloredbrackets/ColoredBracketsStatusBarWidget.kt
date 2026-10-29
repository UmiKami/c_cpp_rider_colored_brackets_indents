// Copyright 2026 Ernesto Gonzalez. Licensed under the Apache License, Version 2.0.
// See the LICENSE and NOTICE files in the project root.

package dev.umikami.coloredbrackets

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Consumer
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import java.awt.geom.Arc2D
import javax.swing.Icon

class ColoredBracketsStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ColoredBracketsStatusBarWidget.ID
    override fun getDisplayName(): String = "C/C++ Colored Brackets"
    override fun createWidget(project: Project): StatusBarWidget = ColoredBracketsStatusBarWidget(project)
}

/** Status bar icon showing the current color order; clicking it opens quick toggles and the settings. */
class ColoredBracketsStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.IconPresentation {
    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        ColoredBracketsService.getInstance() // make sure editors that are already open get colored
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getIcon(): Icon {
        val settings = ColoredBracketsSettings.getInstance()
        return BracketsIcon(settings.bracketColors(), settings.state.enabled)
    }

    override fun getTooltipText(): String {
        val s = ColoredBracketsSettings.getInstance().state
        val brackets = if (s.enabled) "on" else "off"
        val indents = if (s.indentsEnabled) "on" else "off"
        return "C/C++ Colored Brackets: brackets $brackets, indents $indents. Click to customize."
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { e -> showPopup(e.component) }

    private fun showPopup(component: Component) {
        val group = DefaultActionGroup().apply {
            add(SettingToggle("Colored Brackets") { it::enabled })
            add(SettingToggle("Colorize Indents") { it::indentsEnabled })
            addSeparator()
            add(SettingToggle("Round ( )") { it::roundBrackets })
            add(SettingToggle("Square [ ]") { it::squareBrackets })
            add(SettingToggle("Curly { }") { it::curlyBrackets })
            add(SettingToggle("Angle < > (templates)") { it::angleBrackets })
            addSeparator()
            add(SettingToggle("Repeat Colors Past Last Level") { it::cycleColors })
            add(SettingToggle("Mark Unmatched Brackets") { it::highlightUnmatched })
            addSeparator()
            add(object : DumbAwareAction("Customize Colors and Levels…") {
                override fun actionPerformed(e: AnActionEvent) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, ColoredBracketsConfigurable::class.java)
                }
            })
        }
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            "C/C++ Colored Brackets",
            group,
            DataManager.getInstance().getDataContext(component),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            false,
        )
        val size = popup.content.preferredSize
        popup.show(RelativePoint(component, Point(0, -size.height)))
    }

    override fun dispose() {}

    companion object {
        const val ID = "CppColoredBrackets"
    }
}

private class SettingToggle(
    text: String,
    private val property: (ColoredBracketsSettings.Data) -> kotlin.reflect.KMutableProperty0<Boolean>,
) : ToggleAction(text) {
    override fun isSelected(e: AnActionEvent): Boolean = property(ColoredBracketsSettings.getInstance().state).get()

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val settings = ColoredBracketsSettings.getInstance()
        property(settings.state).set(state)
        settings.changed()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun isDumbAware(): Boolean = true
}

/** Nested arcs in the first few bracket colors, like `((( )))`. Grayed out when brackets are off. */
private class BracketsIcon(colors: List<Color>, private val enabled: Boolean) : Icon {
    private val colors = colors.take(4)

    override fun getIconWidth(): Int = JBUI.scale(16)
    override fun getIconHeight(): Int = JBUI.scale(16)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val size = iconWidth.toDouble()
            val step = size / 8.0
            g2.stroke = BasicStroke((size / 11.0).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            colors.forEachIndexed { i, color ->
                g2.color = if (enabled) color else Color(128, 128, 128, 140)
                val inset = step * i + size * 0.06
                val w = size - 2 * inset
                val h = size * 0.86
                val top = y + (size - h) / 2
                g2.draw(Arc2D.Double(x + inset, top, w, h, 120.0, 120.0, Arc2D.OPEN))
                g2.draw(Arc2D.Double(x + inset, top, w, h, -60.0, 120.0, Arc2D.OPEN))
            }
        } finally {
            g2.dispose()
        }
    }
}
