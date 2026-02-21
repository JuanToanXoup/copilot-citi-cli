package com.citigroup.copilotchat.ui

import com.citigroup.copilotchat.config.CopilotChatSettings
import com.citigroup.copilotchat.rag.RagIndexer
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*

/**
 * "Memory" tab — RAG settings and project indexing controls.
 *
 * Sections:
 *  1. RAG toggle + settings (enabled, topK)
 *  2. Index controls (index project button, progress, status)
 */
class MemoryPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val enabledCheckbox = JCheckBox("Enable RAG context injection").apply {
        toolTipText = "When enabled, relevant code snippets are automatically retrieved and prepended to each message"
    }
    private val autoIndexCheckbox = JCheckBox("Auto-index on project open").apply {
        toolTipText = "Automatically index the project when it is opened"
    }
    private val topKSpinner = JSpinner(SpinnerNumberModel(5, 1, 20, 1)).apply {
        toolTipText = "Number of code chunks to retrieve per query"
    }

    private val indexButton = JButton("Index Project").apply {
        icon = AllIcons.Actions.Refresh
    }
    private val cancelButton = JButton("Cancel").apply {
        icon = AllIcons.Actions.Cancel
        isVisible = false
    }

    private val statusLabel = JLabel(" ")
    private val progressBar = JProgressBar().apply {
        isVisible = false
        isStringPainted = true
    }

    private var progressTimer: Timer? = null

    init {
        border = JBUI.Borders.empty(8)

        val content = Box.createVerticalBox()

        // ── Section 1: RAG Settings ──
        content.add(createSectionHeader("RAG Settings"))
        content.add(Box.createVerticalStrut(6))

        val settingsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.emptyLeft(8)

            add(enabledCheckbox.apply { alignmentX = LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(4))
            add(autoIndexCheckbox.apply { alignmentX = LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(6))

            val topKRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                alignmentX = LEFT_ALIGNMENT
                add(JLabel("Context chunks per query: "))
                add(topKSpinner)
            }
            add(topKRow)
        }
        content.add(settingsPanel)
        content.add(Box.createVerticalStrut(16))

        // ── Section 2: Indexing ──
        content.add(createSectionHeader("Project Index"))
        content.add(Box.createVerticalStrut(6))

        val indexPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.emptyLeft(8)

            val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                alignmentX = LEFT_ALIGNMENT
                add(indexButton)
                add(Box.createHorizontalStrut(8))
                add(cancelButton)
            }
            add(buttonRow)
            add(Box.createVerticalStrut(6))
            add(progressBar.apply { alignmentX = LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(4))
            add(statusLabel.apply { alignmentX = LEFT_ALIGNMENT })
        }
        content.add(indexPanel)
        content.add(Box.createVerticalStrut(16))

        // ── Section 3: Info ──
        content.add(createSectionHeader("How It Works"))
        content.add(Box.createVerticalStrut(6))

        val infoText = JLabel(
            "<html><div style='width: 320px; color: gray;'>" +
            "Memory indexes your project's code into a local vector store. " +
            "When RAG is enabled, each chat message is augmented with the most relevant " +
            "code snippets from your project — giving the model deeper context without manual file references." +
            "<br/><br/>" +
            "Indexing uses PSI-aware chunking (functions, classes) and a local ONNX embedding model " +
            "(bge-small-en-v1.5) that runs entirely on-device — no network calls needed. " +
            "Unchanged files are skipped on re-index." +
            "</div></html>"
        ).apply {
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.emptyLeft(8)
        }
        content.add(infoText)

        // Glue to push everything to the top
        content.add(Box.createVerticalGlue())

        val scrollPane = JBScrollPane(content).apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(scrollPane, BorderLayout.CENTER)

        // ── Load settings ──
        loadFromSettings()

        // ── Listeners ──
        enabledCheckbox.addActionListener { saveToSettings() }
        autoIndexCheckbox.addActionListener { saveToSettings() }
        topKSpinner.addChangeListener { saveToSettings() }

        indexButton.addActionListener { startIndexing() }
        cancelButton.addActionListener { cancelIndexing() }
    }

    private fun createSectionHeader(title: String): JComponent {
        val label = JLabel(title).apply {
            font = font.deriveFont(java.awt.Font.BOLD, font.size + 1f)
            alignmentX = LEFT_ALIGNMENT
        }
        return JPanel(BorderLayout()).apply {
            alignmentX = LEFT_ALIGNMENT
            add(label, BorderLayout.WEST)
            add(JSeparator(), BorderLayout.CENTER)
            border = JBUI.Borders.emptyBottom(2)
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun loadFromSettings() {
        val settings = CopilotChatSettings.getInstance()
        enabledCheckbox.isSelected = settings.ragEnabled
        autoIndexCheckbox.isSelected = settings.ragAutoIndex
        topKSpinner.value = settings.ragTopK
    }

    private fun saveToSettings() {
        val settings = CopilotChatSettings.getInstance()
        settings.ragEnabled = enabledCheckbox.isSelected
        settings.ragAutoIndex = autoIndexCheckbox.isSelected
        settings.ragTopK = topKSpinner.value as Int
    }

    private fun startIndexing() {
        val indexer = RagIndexer.getInstance(project)
        if (indexer.isIndexing) return

        indexButton.isEnabled = false
        cancelButton.isVisible = true
        progressBar.isVisible = true
        progressBar.value = 0
        statusLabel.text = "Starting..."
        statusLabel.foreground = JBColor.foreground()

        indexer.indexProject()

        // Poll indexer progress
        progressTimer?.stop()
        progressTimer = Timer(500) {
            SwingUtilities.invokeLater { updateProgress() }
        }
        progressTimer?.start()
    }

    private fun cancelIndexing() {
        RagIndexer.getInstance(project).cancelIndexing()
        onIndexingDone("Indexing cancelled.")
    }

    private fun updateProgress() {
        val indexer = RagIndexer.getInstance(project)

        if (!indexer.isIndexing) {
            val error = indexer.lastError
            if (error != null) {
                onIndexingDone(error)
                statusLabel.foreground = JBColor.RED
            } else {
                val actuallyIndexed = indexer.indexedFiles - indexer.skippedFiles - indexer.failedFiles
                val parts = mutableListOf<String>()
                if (actuallyIndexed > 0) parts.add("$actuallyIndexed indexed")
                if (indexer.skippedFiles > 0) parts.add("${indexer.skippedFiles} unchanged")
                if (indexer.failedFiles > 0) parts.add("${indexer.failedFiles} failed")
                val msg = "Done. ${parts.joinToString(", ")}."
                onIndexingDone(msg)
                statusLabel.foreground = if (indexer.failedFiles > 0) JBColor.RED else JBColor.foreground()
            }
            return
        }

        val total = indexer.totalFiles
        val done = indexer.indexedFiles

        if (total > 0) {
            progressBar.maximum = total
            progressBar.value = done
            progressBar.string = "$done / $total"
            statusLabel.text = "Indexing... ($done / $total files)"
        } else {
            statusLabel.text = "Scanning project files..."
        }
    }

    private fun onIndexingDone(message: String) {
        progressTimer?.stop()
        progressTimer = null
        indexButton.isEnabled = true
        cancelButton.isVisible = false
        progressBar.isVisible = false
        statusLabel.text = message
    }

    override fun dispose() {
        progressTimer?.stop()
        scope.cancel()
    }
}
