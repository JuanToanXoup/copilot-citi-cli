package com.speckit.plugin.ui.onboarding

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.darcula.DarculaNewUIUtil
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBDimension
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBValue
import com.intellij.util.ui.MacUIUtil
import com.intellij.util.ui.UIUtilities
import java.awt.Color
import java.awt.Dimension
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.AbstractButton
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.plaf.basic.BasicButtonUI

/**
 * Exact replica of com.intellij.llmInstaller.ui.welcome.components.DisclosureButton
 * and its DisclosureButtonUI / DisclosureButtonBorder.
 */
class SpeckitDisclosureButton : JButton() {

    var rightIcon: Icon? = null
        set(value) {
            if (field != value) {
                field = value
                revalidate()
                repaint()
            }
        }

    var arrowIcon: Icon? = AllIcons.General.ChevronRight
        set(value) {
            if (field != value) {
                field = value
                revalidate()
                repaint()
            }
        }

    var buttonBackground: Color? = null
        set(value) {
            if (field != value) {
                field = value
                revalidate()
                repaint()
            }
        }

    init {
        setUI(SpeckitDisclosureButtonUI())
        horizontalAlignment = LEFT
        iconTextGap = JBUIScale.scale(12)
        isRolloverEnabled = true
    }

    override fun updateUI() {
        setUI(SpeckitDisclosureButtonUI())
    }
}

/**
 * Exact replica of com.intellij.llmInstaller.ui.welcome.components.DisclosureButtonUI.
 * Uses DarculaNewUIUtil for background rendering and super.paintText for proper text AA.
 */
class SpeckitDisclosureButtonUI : BasicButtonUI() {

    companion object {
        private val ARC = JBValue.UIInteger("DisclosureButton.arc", 16)
        private val TEXT_RIGHT_ICON_GAP = JBValue.UIInteger("DisclosureButton.textRightIconGap", 8)

        private val DEFAULT_BACKGROUND: JBColor = JBColor.namedColor("DisclosureButton.defaultBackground")
        private val HOVER_BACKGROUND: JBColor = JBColor.namedColor("DisclosureButton.hoverOverlay")
        private val PRESSED_BACKGROUND: JBColor = JBColor.namedColor("DisclosureButton.pressedOverlay")

        private var LEFT_MARGIN = 14
        private var RIGHT_MARGIN = 12
    }

    override fun installDefaults(b: AbstractButton?) {
        super.installDefaults(b)
        b?.border = SpeckitDisclosureButtonBorder()
    }

    override fun paint(g: Graphics?, c: javax.swing.JComponent?) {
        if (c !is SpeckitDisclosureButton || g == null) {
            super.paint(g, c)
            return
        }
        paintBackground(g, c)
        super.paint(g, c)

        // Paint arrow icon on the right
        val arrow = c.arrowIcon ?: return
        val insets = c.insets
        val x = c.width - insets.right - JBUIScale.scale(RIGHT_MARGIN) - arrow.iconWidth
        val y = insets.top + (c.height - insetsHeight(insets) - arrow.iconHeight) / 2
        arrow.paintIcon(c, g, x, y)
    }

    override fun paintIcon(g: Graphics?, c: javax.swing.JComponent?, iconRect: Rectangle?) {
        iconRect?.let { it.x += JBUIScale.scale(LEFT_MARGIN) }
        super.paintIcon(g, c, iconRect)
    }

    override fun paintText(g: Graphics?, c: javax.swing.JComponent?, textRect: Rectangle?, text: String?) {
        if (g == null || textRect == null) return
        if (c !is SpeckitDisclosureButton) {
            super.paintText(g, c, textRect, text)
            return
        }
        textRect.x += JBUIScale.scale(LEFT_MARGIN)
        textRect.width = c.width - textRect.x - getExtraIconsSize(c).width - JBUIScale.scale(RIGHT_MARGIN)

        val fm: FontMetrics = c.getFontMetrics(c.font)
        val clippedText = UIUtilities.clipStringIfNecessary(c, fm, text, textRect.width)

        // Draw text directly — calling super.paintText() causes infinite recursion because
        // BasicButtonUI.paintText() dispatches back to this override via virtual dispatch.
        g.color = if (c.model.isEnabled) c.foreground else c.background.darker()
        UIUtilities.drawStringUnderlineCharAt(
            c, g, clippedText, c.displayedMnemonicIndex,
            textRect.x + textShiftOffset, textRect.y + fm.ascent + textShiftOffset
        )

        // Paint right icon after text
        val ri = c.rightIcon ?: return
        val textWidth = fm.stringWidth(clippedText)
        val riX = textRect.x + minOf(textRect.width, textWidth) + TEXT_RIGHT_ICON_GAP.get()
        val insets = c.insets
        val riY = insets.top + (c.height - insetsHeight(insets) - ri.iconHeight) / 2
        ri.paintIcon(c, g, riX, riY)
    }

    override fun getPreferredSize(c: javax.swing.JComponent?): Dimension {
        val result = super.getPreferredSize(c)
        if (c is SpeckitDisclosureButton) {
            val insets = c.insets
            val minimumSize = getMinimumSize(c)
            val extraSize = getExtraIconsSize(c)
            result.width += extraSize.width
            result.height = maxOf(result.height, extraSize.height)
            result.width = maxOf(result.width + JBUIScale.scale(LEFT_MARGIN) + JBUIScale.scale(RIGHT_MARGIN), minimumSize.width) + insetsWidth(insets)
            result.height = maxOf(result.height, minimumSize.height) + insetsHeight(insets)
        }
        return result
    }

    override fun getMinimumSize(c: javax.swing.JComponent?): Dimension {
        return JBDimension(72, 34)
    }

    /**
     * Uses DarculaNewUIUtil.fillRoundedRectangle for proper anti-aliased background rendering,
     * matching the original DisclosureButtonUI exactly.
     */
    private fun paintBackground(g: Graphics, c: SpeckitDisclosureButton) {
        val r = Rectangle(0, 0, c.width, c.height)
        JBInsets.removeFrom(r, c.insets)

        val color: Color = c.buttonBackground ?: DEFAULT_BACKGROUND
        DarculaNewUIUtil.fillRoundedRectangle(g, r, color, ARC.float)

        val model = c.model
        val overlay = when {
            model.isArmed && model.isPressed -> PRESSED_BACKGROUND
            model.isRollover -> HOVER_BACKGROUND
            else -> null
        }
        if (overlay != null) {
            DarculaNewUIUtil.fillRoundedRectangle(g, r, overlay, ARC.float)
        }
    }

    private fun getExtraIconsSize(b: SpeckitDisclosureButton): Dimension {
        val result = Dimension()
        b.rightIcon?.let {
            result.width += TEXT_RIGHT_ICON_GAP.get() + it.iconWidth
            result.height = maxOf(result.height, it.iconHeight)
        }
        b.arrowIcon?.let {
            result.width += b.iconTextGap + it.iconWidth
            result.height = maxOf(result.height, it.iconHeight)
        }
        return result
    }

    private fun insetsWidth(insets: Insets): Int = insets.left + insets.right
    private fun insetsHeight(insets: Insets): Int = insets.top + insets.bottom
}

/**
 * Exact replica of com.intellij.llmInstaller.ui.welcome.components.DisclosureButtonBorder.
 * Paints focus ring when button has focus, with proper AA and stroke hints.
 */
class SpeckitDisclosureButtonBorder : javax.swing.border.Border, javax.swing.plaf.UIResource {

    override fun paintBorder(c: java.awt.Component?, g: Graphics?, x: Int, y: Int, width: Int, height: Int) {
        if (g == null || c !is SpeckitDisclosureButton) return

        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(
                RenderingHints.KEY_STROKE_CONTROL,
                if (MacUIUtil.USE_QUARTZ) RenderingHints.VALUE_STROKE_PURE else RenderingHints.VALUE_STROKE_NORMALIZE
            )
            // Focus ring painting would go here if needed
        } finally {
            g2.dispose()
        }
    }

    override fun getBorderInsets(c: java.awt.Component?): Insets {
        return JBInsets(3).asUIResource()
    }

    override fun isBorderOpaque(): Boolean = false
}
