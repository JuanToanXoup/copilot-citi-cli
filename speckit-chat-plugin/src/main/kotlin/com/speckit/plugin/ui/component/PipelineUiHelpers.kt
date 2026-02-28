package com.speckit.plugin.ui.component

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.speckit.plugin.model.CheckResult
import com.speckit.plugin.model.StepStatus
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Shared UI helper functions for pipeline step rendering.
 *
 * Used by both [com.speckit.plugin.ui.PipelinePanel] and
 * [com.speckit.plugin.ui.onboarding.PipelineDemoPanel] to avoid
 * duplicated status icon/text/color mappings and widget factories.
 */
object PipelineUiHelpers {

    // ── Reusable colours ──────────────────────────────────────────────────

    val greenColor: Color = JBColor(Color(0, 128, 0), Color(80, 200, 80))
    val orangeColor: Color = JBColor(Color(200, 100, 0), Color(255, 160, 60))
    private val hoverColor: Color = JBColor(Color(0, 100, 200), Color(100, 180, 255))

    // ── Status helpers ────────────────────────────────────────────────────

    fun statusIcon(status: StepStatus): String = when (status) {
        StepStatus.COMPLETED -> "\u2713"     // checkmark
        StepStatus.READY -> "\u25CB"         // circle
        StepStatus.IN_PROGRESS -> "\u25D0"   // half circle
        StepStatus.BLOCKED -> "\u2717"       // X
        StepStatus.NOT_STARTED -> "\u25CB"   // circle
    }

    fun statusText(status: StepStatus): String = when (status) {
        StepStatus.COMPLETED -> "Completed"
        StepStatus.READY -> "Ready"
        StepStatus.IN_PROGRESS -> "In Progress"
        StepStatus.BLOCKED -> "Blocked"
        StepStatus.NOT_STARTED -> "Not Started"
    }

    fun statusColor(status: StepStatus): Color = when (status) {
        StepStatus.COMPLETED -> greenColor
        StepStatus.READY -> JBColor.BLUE
        StepStatus.IN_PROGRESS -> JBColor.BLUE
        StepStatus.BLOCKED -> orangeColor
        StepStatus.NOT_STARTED -> JBColor.GRAY
    }

    // ── Widget factories ──────────────────────────────────────────────────

    fun sectionHeader(text: String) = JLabel(text).apply {
        font = font.deriveFont(Font.BOLD)
        alignmentX = Component.LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(0, 0, 4, 0)
    }

    fun checkResultLabel(result: CheckResult, project: Project? = null): JComponent {
        val icon = if (result.exists) "\u2713" else "\u2717"
        val detail = if (result.detail.isNotEmpty()) "  (${result.detail})" else ""
        val text = "  $icon  ${result.artifact.label}$detail"

        val canOpen = result.exists && result.resolvedFile != null && !result.artifact.isDirectory
        return JLabel(text).apply {
            foreground = if (result.exists) greenColor else orangeColor
            alignmentX = Component.LEFT_ALIGNMENT
            if (canOpen && project != null) {
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) {
                        openFileInEditor(project, result.resolvedFile!!)
                    }
                })
                addHoverEffect(this)
            }
        }
    }

    fun verticalSpacer(height: Int) = JPanel().apply {
        maximumSize = Dimension(Int.MAX_VALUE, height)
        preferredSize = Dimension(0, height)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
    }

    // ── File actions ──────────────────────────────────────────────────────

    fun openFileInEditor(project: Project, file: File) {
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: return
        FileEditorManager.getInstance(project).openFile(vFile, true)
    }

    fun addHoverEffect(label: JLabel) {
        val normalColor = label.foreground
        label.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent) { label.foreground = hoverColor }
            override fun mouseExited(e: java.awt.event.MouseEvent) { label.foreground = normalColor }
        })
    }
}
