package com.speckit.plugin.ui.component

import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JPanel

/**
 * Vertical timeline connector drawn between pipeline step rows.
 *
 * Renders a vertical line with a dot at the vertical centre. Supports
 * an [isSubStep] flag that shrinks the dot for nested steps.
 *
 * Shared between [com.speckit.plugin.ui.PipelinePanel] (tree renderer)
 * and [com.speckit.plugin.ui.onboarding.PipelineDemoPanel] (list renderer).
 */
class ConnectorPanel : JPanel() {
    var isFirst = false
    var isLast = false
    var isSubStep = false
    var color: Color = JBColor.border()

    init {
        isOpaque = false
        preferredSize = Dimension(14, 0)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color

        val cx = width / 2
        val top = if (isFirst) height / 2 else 0
        val bottom = if (isLast) height / 2 else height

        g2.drawLine(cx, top, cx, bottom)
        val dotSize = if (isSubStep) 4 else 6
        g2.fillOval(cx - dotSize / 2, height / 2 - dotSize / 2, dotSize, dotSize)
    }
}
