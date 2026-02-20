package com.citigroup.copilotchat.ui

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet

/**
 * Renders markdown content to HTML and displays in a JEditorPane.
 * Uses flexmark for markdown→HTML conversion with IDE-themed styling.
 *
 * Follows the official Copilot plugin pattern (MarkdownPane):
 *  - JEditorPane with HTMLEditorKit + custom StyleSheet
 *  - Theme change listener to update styles on LAF switch
 *  - Hyperlink handler for opening links in browser
 */
class MessageRenderer {

    private val options = MutableDataSet()
    private val parser: Parser = Parser.builder(options).build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder(options).build()

    // Track created panes so we can update stylesheets on theme change
    private val activePanes = mutableListOf<JEditorPane>()

    init {
        // Listen for theme/LAF changes like the official Copilot plugin does
        ApplicationManager.getApplication().messageBus.connect()
            .subscribe(LafManagerListener.TOPIC, LafManagerListener {
                updateAllPaneStyles()
            })
    }

    fun createMessagePane(): JEditorPane {
        val pane = JEditorPane()
        pane.isEditable = false
        pane.contentType = "text/html"
        pane.isOpaque = false

        val kit = HTMLEditorKit()
        kit.styleSheet = createStyleSheet()
        pane.editorKit = kit
        pane.border = JBUI.Borders.empty(0)
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)

        // Handle hyperlinks
        pane.addHyperlinkListener { event ->
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED && event.url != null) {
                try {
                    java.awt.Desktop.getDesktop().browse(event.url.toURI())
                } catch (_: Exception) {}
            }
        }

        activePanes.add(pane)
        return pane
    }

    fun renderMarkdown(markdown: String): String {
        // Collapse 3+ consecutive blank lines to 2 (one paragraph break)
        val cleaned = markdown.replace(Regex("\n{3,}"), "\n\n")
        val document = parser.parse(cleaned)
        var html = renderer.render(document)

        // Swing's HTMLEditorKit has hardcoded block spacing on <p> elements
        // that CSS cannot override. Strip <p> tags and use <br> for spacing.
        html = html.replace(Regex("<p>"), "")
        html = html.replace(Regex("</p>"), "<br>")
        // Clean up double <br> from consecutive empty paragraphs
        html = html.replace(Regex("(<br>\\s*){3,}"), "<br><br>")

        return "<html><body>$html</body></html>"
    }

    private fun updateAllPaneStyles() {
        val ss = createStyleSheet()
        val iterator = activePanes.iterator()
        while (iterator.hasNext()) {
            val pane = iterator.next()
            if (!pane.isDisplayable) {
                iterator.remove()
                continue
            }
            val kit = pane.editorKit as? HTMLEditorKit ?: continue
            kit.styleSheet = ss
            // Re-render current content to apply new styles
            val text = pane.text
            if (text.isNotEmpty()) {
                pane.text = text
            }
        }
    }

    private fun createStyleSheet(): StyleSheet {
        val ss = StyleSheet()
        val fg = colorToHex(JBColor.foreground())
        val bg = colorToHex(JBColor.background())
        val link = colorToHex(JBColor.BLUE)
        val codeBg = colorToHex(JBColor(0xF5F5F5, 0x2B2B2B))
        val borderColor = colorToHex(JBColor(0xDDDDDD, 0x444444))

        // Note: Swing's CSS parser only supports CSS1 — no border-radius, overflow-x, etc.
        ss.addRule("body { font-family: sans-serif; font-size: 13pt; color: $fg; background-color: $bg; margin: 0; padding: 0; }")
        ss.addRule("a { color: $link; text-decoration: underline; }")
        ss.addRule("code { font-family: monospace; font-size: 12pt; background-color: $codeBg; padding: 1px; }")
        ss.addRule("pre { font-family: monospace; font-size: 12pt; background-color: $codeBg; padding: 8px; border-width: 1px; border-style: solid; border-color: $borderColor; margin: 0; }")
        ss.addRule("pre code { background-color: transparent; padding: 0; }")
        ss.addRule("h1 { font-size: 18pt; margin: 0; padding: 0; }")
        ss.addRule("h2 { font-size: 16pt; margin: 0; padding: 0; }")
        ss.addRule("h3 { font-size: 14pt; margin: 0; padding: 0; }")
        ss.addRule("p { margin: 0; padding: 0; }")
        ss.addRule("ul { margin: 0; padding-left: 20px; }")
        ss.addRule("ol { margin: 0; padding-left: 20px; }")
        ss.addRule("li { margin: 0; padding: 0; }")
        ss.addRule("li p { margin: 0; padding: 0; }")
        ss.addRule("blockquote { border-left-width: 3px; border-left-style: solid; border-left-color: $borderColor; margin: 0; padding-left: 8px; color: $fg; }")
        return ss
    }

    private fun colorToHex(color: java.awt.Color): String {
        return String.format("#%02x%02x%02x", color.red, color.green, color.blue)
    }
}
