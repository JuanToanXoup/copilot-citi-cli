package com.citigroup.copilotchat.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import kotlinx.serialization.json.JsonObject
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

/**
 * Tool call panel matching the official GitHub Copilot plugin's AgentToolCallPanel.
 *
 * Official structure (from decompiled bytecode):
 *   AgentToolCallPanel (JPanel, GridBagLayout, opaque=false)
 *   └── contentPanel (BorderLayout(6, 0), opaque=false, border=empty(4,6,4,6))
 *       ├── CENTER: ProgressMessageComponent (tool name / progress)
 *       └── EAST: icon (running spinner JLabel)
 *
 * Border: Style.Borders.AgentToolCallPanelBorder
 */
class ToolCallPanel(
    private val toolName: String,
    private val input: JsonObject,
) : JPanel(GridBagLayout()) {

    init {
        isOpaque = false
        // Official uses Style.Borders.AgentToolCallPanelBorder
        border = JBUI.Borders.empty(2, 0)

        val contentPanel = JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0)
        }

        // Tool name header
        val nameColor = JBColor(0x0366D6, 0x58A6FF)
        val nameLabel = JLabel(
            "<html><b style='color: ${colorToHex(nameColor)}'>$toolName</b></html>"
        )
        contentPanel.add(nameLabel, BorderLayout.CENTER)



        val headerGbc = GridBagConstraints().apply {
            gridx = 0; gridy = 0
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            anchor = GridBagConstraints.NORTHWEST
        }
        add(contentPanel, headerGbc)

        // Action and arguments on └ line below
        val briefInput = formatBriefInput(input)
        if (briefInput.isNotEmpty()) {
            val argColor = colorToHex(JBColor(0x666666, 0x999999))
            val argLabel = JLabel(
                "<html><span style='color: $argColor'>  \u2514 ${escapeHtml(briefInput)}</span></html>"
            )
            argLabel.border = JBUI.Borders.empty(0, 8, 2, 0)
            val argGbc = GridBagConstraints().apply {
                gridx = 0; gridy = 1
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                anchor = GridBagConstraints.NORTHWEST
            }
            add(argLabel, argGbc)
        }
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
