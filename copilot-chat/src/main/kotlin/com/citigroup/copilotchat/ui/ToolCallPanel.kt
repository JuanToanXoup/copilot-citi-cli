package com.citigroup.copilotchat.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.serialization.json.JsonObject
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*

/**
 * Collapsible panel showing a tool call — built on CollapsiblePanel,
 * matching the official Copilot plugin's AgentToolCallPanel pattern.
 *
 * Shows tool name + brief input in the header. Expanding reveals full JSON input.
 */
class ToolCallPanel(
    private val toolName: String,
    private val input: JsonObject,
) : JPanel(BorderLayout()) {

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 8)

        val toolColor = colorToHex(JBColor(0x6A737D, 0x8B949E))
        val nameColor = colorToHex(JBColor(0x0366D6, 0x58A6FF))

        // Trigger: function icon + tool name + brief input
        val triggerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(JLabel(AllIcons.Nodes.Function))

            val briefInput = formatBriefInput(input)
            add(JLabel("<html><span style='color: $nameColor'><b>$toolName</b></span> " +
                "<span style='color: $toolColor'>${escapeHtml(briefInput)}</span></html>"))
        }

        // Detail content: formatted JSON
        val detailContent = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 24, 4, 8)

            val detailText = JTextArea(formatJson(input)).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 11)
                foreground = JBColor(0x586069, 0x8B949E)
                background = JBColor(0xF6F8FA, 0x161B22)
                border = JBUI.Borders.empty(4)
            }
            add(JBScrollPane(detailText).apply {
                preferredSize = Dimension(0, 80)
                maximumSize = Dimension(Int.MAX_VALUE, 120)
            }, BorderLayout.CENTER)
        }

        val collapsible = CollapsiblePanel(
            trigger = triggerPanel,
            contentPanel = detailContent,
            initiallyExpanded = false,
        )

        add(collapsible, BorderLayout.CENTER)
    }

    private fun formatBriefInput(input: JsonObject): String {
        val parts = input.entries.take(2).map { (k, v) ->
            val valStr = v.toString().removeSurrounding("\"").take(50)
            "$k=$valStr"
        }
        val brief = parts.joinToString(", ")
        return if (brief.length > 80) brief.take(80) + "..." else brief
    }

    private fun formatJson(input: JsonObject): String {
        return try {
            val sb = StringBuilder()
            input.entries.forEach { (k, v) ->
                sb.appendLine("$k: ${v.toString().removeSurrounding("\"")}")
            }
            sb.toString().trimEnd()
        } catch (_: Exception) {
            input.toString()
        }
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun colorToHex(color: java.awt.Color): String =
        String.format("#%02x%02x%02x", color.red, color.green, color.blue)
}
