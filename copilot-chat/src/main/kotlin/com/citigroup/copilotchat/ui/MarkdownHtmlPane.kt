package com.citigroup.copilotchat.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import java.awt.BorderLayout
import java.awt.Rectangle
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.UIManager
import javax.swing.text.DefaultCaret

/**
 * Renders markdown as HTML in a bordered panel.
 * Uses flexmark-java for markdown-to-HTML conversion.
 */
class MarkdownHtmlPane : JPanel(BorderLayout()) {

    private val content = StringBuilder()

    private val editorPane = JEditorPane().apply {
        contentType = "text/html"
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty(2, 4)
        // Prevent auto-scrolling on content update
        (caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
        caret = object : DefaultCaret() {
            override fun adjustVisibility(nloc: Rectangle?) {}
        }
        // Enable link clicks
        addHyperlinkListener(com.intellij.ui.BrowserHyperlinkListener.INSTANCE)
    }

    init {
        isOpaque = true
        background = JBColor(0xF6F8FA, 0x1E1E20)
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor(0xD0D7DE, 0x3D3D3F), 1),
            JBUI.Borders.empty(0),
        )
        add(editorPane, BorderLayout.CENTER)
    }

    fun appendText(text: String) {
        content.append(text)
        render()
    }

    fun setText(text: String) {
        content.clear()
        content.append(text)
        render()
    }

    fun getText(): String = content.toString()

    fun hasContent(): Boolean = content.isNotEmpty()

    private fun render() {
        val html = markdownToHtml(content.toString())
        editorPane.text = wrapHtml(html)
        revalidate()
        repaint()
    }

    companion object {
        private val options = MutableDataSet()
        private val parser = Parser.builder(options).build()
        private val renderer = HtmlRenderer.builder(options).build()

        fun markdownToHtml(markdown: String): String {
            val document = parser.parse(markdown)
            return renderer.render(document)
        }

        private fun wrapHtml(bodyHtml: String): String {
            val fg = colorToHex(JBColor(0x1F2328, 0xE6EDF3))
            val codeBg = colorToHex(JBColor(0xEFF1F3, 0x2D2D2F))
            val codeBorder = colorToHex(JBColor(0xD0D7DE, 0x3D3D3F))
            val fontFamily = UIManager.getFont("Label.font")?.family ?: "sans-serif"
            val fontSize = (UIManager.getFont("Label.font")?.size ?: 13) - 3

            return """
            <html><head><style>
            body {
                font-family: '$fontFamily', sans-serif;
                font-size: ${fontSize}px;
                color: $fg;
                margin: 0; padding: 0;
            }
            p { margin: 2px 0; }
            pre {
                background: $codeBg;
                border: 1px solid $codeBorder;
                padding: 4px 6px;
                font-family: monospace;
                font-size: ${fontSize - 1}px;
            }
            code {
                background: $codeBg;
                padding: 0px 3px;
                font-family: monospace;
                font-size: ${fontSize - 1}px;
            }
            pre code { background: none; padding: 0; }
            ul, ol { padding-left: 16px; margin: 2px 0; }
            li { margin: 1px 0; }
            h1, h2, h3, h4 { margin: 4px 0 2px 0; }
            h1 { font-size: ${fontSize + 2}px; }
            h2 { font-size: ${fontSize + 1}px; }
            h3 { font-size: ${fontSize}px; }
            blockquote {
                border-left: 3px solid $codeBorder;
                margin: 2px 0;
                padding: 1px 6px;
                color: $fg;
            }
            a { color: #58A6FF; }
            </style></head><body>
            $bodyHtml
            </body></html>
            """.trimIndent()
        }

        private fun colorToHex(color: JBColor): String {
            val c = color // resolves to light or dark based on current theme
            return String.format("#%02x%02x%02x", c.red, c.green, c.blue)
        }
    }
}
