package com.citigroup.copilotchat.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import kotlinx.serialization.json.JsonObject
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

/**
 * Tool call panel that groups consecutive calls to the same tool under one header.
 *
 * Layout:
 *   **toolName**
 *     └ action=find_class, pattern=Foo
 *     └ action=find_symbol, pattern=Foo
 *     └ action=search_text, query=bar
 *
 * Duplicate consecutive lines (same brief text) are skipped.
 */
class ToolCallPanel(
    val toolName: String,
    input: JsonObject,
) : JPanel(GridBagLayout()) {

    private var nextRow = 1
    private var lastBrief: String? = null

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 0)

        // Tool name header (row 0)
        val nameColor = JBColor(0x0366D6, 0x58A6FF)
        val nameLabel = JLabel(
            "<html><b style='color: ${colorToHex(nameColor)}'>$toolName</b></html>"
        )
        nameLabel.border = JBUI.Borders.empty(4, 0)
        add(nameLabel, GridBagConstraints().apply {
            gridx = 0; gridy = 0
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            anchor = GridBagConstraints.NORTHWEST
        })

        // First action line
        addActionLine(input)
    }

    /**
     * Append another action line to this panel.
     * Returns true if the line was added, false if it was a duplicate of the previous line.
     */
    fun addAction(input: JsonObject): Boolean {
        return addActionLine(input)
    }

    private fun addActionLine(input: JsonObject): Boolean {
        val brief = formatBriefInput(input)
        if (brief.isEmpty()) return false
        if (brief == lastBrief) return false // skip duplicate

        lastBrief = brief
        val argColor = colorToHex(JBColor(0x666666, 0x999999))
        val argLabel = JLabel(
            "<html><span style='color: $argColor'>  \u2514 ${escapeHtml(brief)}</span></html>"
        )
        argLabel.border = JBUI.Borders.empty(0, 8, 2, 0)
        add(argLabel, GridBagConstraints().apply {
            gridx = 0; gridy = nextRow++
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            anchor = GridBagConstraints.NORTHWEST
        })
        revalidate()
        repaint()
        return true
    }

    private fun formatBriefInput(input: JsonObject): String {
        val parts = input.entries.take(2).map { (k, v) ->
            val valStr = v.toString().removeSurrounding("\"").take(50)
            "$k=$valStr"
        }
        val brief = parts.joinToString(", ")
        return if (brief.length > 80) brief.take(80) + "..." else brief
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun colorToHex(color: java.awt.Color): String =
        String.format("#%02x%02x%02x", color.red, color.green, color.blue)
}
