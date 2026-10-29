// Copyright 2026 Ernesto Gonzalez. Licensed under the Apache License, Version 2.0.
// See the LICENSE and NOTICE files in the project root.

package dev.umikami.coloredbrackets

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.ColorChooserService
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JSlider
import javax.swing.ListSelectionModel

class ColoredBracketsConfigurable : Configurable {
    private val enabled = JBCheckBox("Colorize matching bracket pairs")
    private val round = JBCheckBox("( )")
    private val square = JBCheckBox("[ ]")
    private val curly = JBCheckBox("{ }")
    private val angle = JBCheckBox("< > (template brackets, best guess)")
    private val cycle = JBCheckBox("Repeat the colors for levels deeper than the list")
    private val unmatched = JBCheckBox("Show unmatched brackets in red")
    private val bracketColors = ColorListEditor()

    private val indents = JBCheckBox("Colorize indents")
    private val indentsUseBracketColors = JBCheckBox("Use the bracket color order")
    private val indentColors = ColorListEditor()
    private val opacity = JSlider(1, 60, 15).apply {
        majorTickSpacing = 10
        paintTicks = true
    }
    private val indentWidth = ComboBox(arrayOf("Detect from file", "2", "3", "4", "8"))

    override fun getDisplayName(): String = "C/C++ Colored Brackets"

    override fun createComponent(): JComponent {
        val p = panel {
            group("Brackets") {
                row { cell(enabled) }
                row("Bracket types:") {
                    cell(round)
                    cell(square)
                    cell(curly)
                    cell(angle)
                }
                row {
                    label("Color order. Level 1 is the outermost pair, one color per level:")
                }
                row {
                    cell(bracketColors.component).align(Align.FILL)
                }
                row { cell(cycle) }
                row { cell(unmatched) }
                row {
                    button("Reset Colors to Defaults") { bracketColors.colors = ColoredBracketsSettings.defaultColors() }
                }
            }
            group("Indents") {
                row { cell(indents) }
                row { cell(indentsUseBracketColors) }
                row {
                    cell(indentColors.component).align(Align.FILL)
                }
                row("Tint strength:") { cell(opacity) }
                row("Indent width:") { cell(indentWidth) }
            }
        }
        enabled.addItemListener { updateEnabledState() }
        indents.addItemListener { updateEnabledState() }
        indentsUseBracketColors.addItemListener { updateEnabledState() }
        reset()
        return p
    }

    private fun updateEnabledState() {
        val on = enabled.isSelected
        for (c in listOf(round, square, curly, angle, cycle, unmatched)) c.isEnabled = on
        bracketColors.setEnabled(on)
        val ind = indents.isSelected
        indentsUseBracketColors.isEnabled = ind
        indentColors.setEnabled(ind && !indentsUseBracketColors.isSelected)
        opacity.isEnabled = ind
        indentWidth.isEnabled = ind
    }

    private fun indentWidthValue(): Int = when (indentWidth.selectedIndex) {
        1 -> 2
        2 -> 3
        3 -> 4
        4 -> 8
        else -> 0
    }

    override fun isModified(): Boolean {
        val s = ColoredBracketsSettings.getInstance()
        val d = s.state
        return enabled.isSelected != d.enabled ||
            round.isSelected != d.roundBrackets ||
            square.isSelected != d.squareBrackets ||
            curly.isSelected != d.curlyBrackets ||
            angle.isSelected != d.angleBrackets ||
            cycle.isSelected != d.cycleColors ||
            unmatched.isSelected != d.highlightUnmatched ||
            bracketColors.colors != s.bracketColors() ||
            indents.isSelected != d.indentsEnabled ||
            indentsUseBracketColors.isSelected != d.indentsUseBracketColors ||
            indentColors.colors != (ColoredBracketsSettings.parseColors(d.indentColors) ?: ColoredBracketsSettings.defaultColors()) ||
            opacity.value != d.indentOpacity ||
            indentWidthValue() != d.indentWidth
    }

    override fun apply() {
        val s = ColoredBracketsSettings.getInstance()
        val d = s.state
        d.enabled = enabled.isSelected
        d.roundBrackets = round.isSelected
        d.squareBrackets = square.isSelected
        d.curlyBrackets = curly.isSelected
        d.angleBrackets = angle.isSelected
        d.cycleColors = cycle.isSelected
        d.highlightUnmatched = unmatched.isSelected
        d.bracketColors = ColoredBracketsSettings.formatColors(bracketColors.colors)
        d.indentsEnabled = indents.isSelected
        d.indentsUseBracketColors = indentsUseBracketColors.isSelected
        d.indentColors = ColoredBracketsSettings.formatColors(indentColors.colors)
        d.indentOpacity = opacity.value
        d.indentWidth = indentWidthValue()
        s.changed()
    }

    override fun reset() {
        val s = ColoredBracketsSettings.getInstance()
        val d = s.state
        enabled.isSelected = d.enabled
        round.isSelected = d.roundBrackets
        square.isSelected = d.squareBrackets
        curly.isSelected = d.curlyBrackets
        angle.isSelected = d.angleBrackets
        cycle.isSelected = d.cycleColors
        unmatched.isSelected = d.highlightUnmatched
        bracketColors.colors = s.bracketColors()
        indents.isSelected = d.indentsEnabled
        indentsUseBracketColors.isSelected = d.indentsUseBracketColors
        indentColors.colors = ColoredBracketsSettings.parseColors(d.indentColors) ?: ColoredBracketsSettings.defaultColors()
        opacity.value = d.indentOpacity
        indentWidth.selectedIndex = when (d.indentWidth) {
            2 -> 1
            3 -> 2
            4 -> 3
            8 -> 4
            else -> 0
        }
        updateEnabledState()
    }
}

/** An ordered list of colors with add / remove / edit / move up / move down. */
private class ColorListEditor {
    private val model = CollectionListModel<Color>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 6
        cellRenderer = object : ColoredListCellRenderer<Color>() {
            override fun customizeCellRenderer(
                list: JList<out Color>, value: Color, index: Int, selected: Boolean, hasFocus: Boolean,
            ) {
                icon = ColorIcon(JBUI.scale(14), value)
                append("Level ${index + 1}")
                append("   #%06X".format(value.rgb and 0xFFFFFF), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    val component: JComponent = ToolbarDecorator.createDecorator(list)
        .setAddAction { pick(model.items.lastOrNull() ?: Color.WHITE) { model.add(it); list.selectedIndex = model.size - 1 } }
        .setEditAction { editSelected() }
        .setRemoveAction {
            val i = list.selectedIndex
            if (i >= 0) {
                model.remove(i)
                list.selectedIndex = minOf(i, model.size - 1)
            }
        }
        .setRemoveActionUpdater { model.size > 1 && list.selectedIndex >= 0 }
        .setMoveUpAction { move(-1) }
        .setMoveDownAction { move(1) }
        .createPanel()

    init {
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                editSelected()
                return true
            }
        }.installOn(list)
    }

    var colors: List<Color>
        get() = model.items.toList()
        set(value) = model.replaceAll(value)

    fun setEnabled(on: Boolean) {
        list.isEnabled = on
        component.isEnabled = on
    }

    private fun editSelected() {
        val i = list.selectedIndex
        if (i < 0) return
        pick(model.getElementAt(i)) { model.setElementAt(it, i) }
    }

    private fun move(delta: Int) {
        val i = list.selectedIndex
        val j = i + delta
        if (i < 0 || j < 0 || j >= model.size) return
        model.exchangeRows(i, j)
        list.selectedIndex = j
    }

    private fun pick(initial: Color, onPicked: (Color) -> Unit) {
        val picked = ColorChooserService.getInstance().showDialog(list, "Choose Color", initial) ?: return
        onPicked(Color(picked.rgb and 0xFFFFFF))
    }
}
