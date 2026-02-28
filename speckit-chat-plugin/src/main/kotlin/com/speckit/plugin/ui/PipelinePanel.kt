package com.speckit.plugin.ui

import com.github.copilot.api.CopilotChatService
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.JBColor
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.speckit.plugin.model.CheckResult
import com.speckit.plugin.model.ChatRun
import com.speckit.plugin.model.ChatRunStatus
import com.speckit.plugin.model.FeatureEntry
import com.speckit.plugin.model.FeaturePaths
import com.speckit.plugin.model.PipelineStepDef
import com.speckit.plugin.model.PipelineStepState
import com.speckit.plugin.model.StepStatus
import com.speckit.plugin.service.ChatRunLauncher
import com.speckit.plugin.service.GitHelper
import com.speckit.plugin.service.PipelineService
import com.speckit.plugin.service.PipelineStepRegistry
import com.speckit.plugin.ui.component.ConnectorPanel
import com.speckit.plugin.ui.component.PipelineUiHelpers
import com.speckit.plugin.persistence.SessionPersistenceManager
import com.speckit.plugin.tools.ResourceLoader
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class PipelinePanel(
    private val project: Project,
    parentDisposable: Disposable,
    private val chatPanel: SessionPanel,
    private val persistenceManager: SessionPersistenceManager? = null,
    private val launcher: ChatRunLauncher? = null
) : JPanel(BorderLayout()), Disposable {

    // ── Data model (see model/PipelineModels.kt) ────────────────────────────

    // ── Tree node data ────────────────────────────────────────────────────────

    private sealed interface PipelineNodeData
    private data class FeatureNodeData(val entry: FeatureEntry) : PipelineNodeData {
        override fun toString() = entry.dirName
    }
    private data class StepNodeData(val step: PipelineStepDef, val featureEntry: FeatureEntry) : PipelineNodeData {
        override fun toString() = step.name
    }

    // ── Pipeline definitions (see service/PipelineService.kt) ──────────────

    private val pipelineSteps = PipelineStepRegistry.steps

    private val featureStepStates = mutableMapOf<String, Map<PipelineStepDef, PipelineStepState>>()

    // ── UI fields ────────────────────────────────────────────────────────────

    private val branchLabel = JLabel("Branch: \u2014")

    private val rootNode = DefaultMutableTreeNode("Pipeline")
    private val treeModel = DefaultTreeModel(rootNode)
    private val pipelineTree = com.intellij.ui.treeStructure.Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        putClientProperty("JTree.lineStyle", "None")
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
    }

    private val detailPanel = JPanel(BorderLayout())

    @Volatile private var currentPaths: FeaturePaths? = null
    @Volatile private var currentBranch: String = ""
    @Volatile private var lastImplementRun: ChatRun? = null
    @Volatile private var pendingSpecifyArgs: String? = null

    // ── Init ─────────────────────────────────────────────────────────────────

    init {
        Disposer.register(parentDisposable, this)

        // Top bar
        val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = "Refresh validation"
            addActionListener { refreshAll() }
        }
        val topBar = JPanel(BorderLayout(8, 0)).apply {
            border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            add(branchLabel, BorderLayout.WEST)
            add(refreshButton, BorderLayout.EAST)
        }

        // Tree configuration
        pipelineTree.cellRenderer = PipelineTreeCellRenderer()
        pipelineTree.rowHeight = 28
        pipelineTree.border = BorderFactory.createEmptyBorder(4, 0, 4, 0)
        // Minimize the built-in tree indent for child nodes
        javax.swing.UIManager.put("Tree.leftChildIndent", 4)
        javax.swing.UIManager.put("Tree.rightChildIndent", 0)
        pipelineTree.updateUI()
        // Re-apply our settings after updateUI
        pipelineTree.isRootVisible = false
        pipelineTree.showsRootHandles = true
        pipelineTree.putClientProperty("JTree.lineStyle", "None")
        pipelineTree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION

        pipelineTree.addTreeSelectionListener {
            val node = pipelineTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            when (val data = node.userObject) {
                is StepNodeData -> {
                    currentPaths = resolvePathsForFeature(data.featureEntry)
                    updateDetailPanel(data.step)
                }
                is FeatureNodeData -> {
                    currentPaths = resolvePathsForFeature(data.entry)
                    updateFeatureDetailPanel(data.entry)
                }
                else -> {}
            }
        }

        TreeSpeedSearch.installOn(pipelineTree)

        // "Add New Specification" button above the tree
        val newFeatureButton = JButton("Add New Specification", AllIcons.General.Add).apply {
            toolTipText = "Create new feature specification"
            addActionListener { showNewFeatureDialog() }
        }
        val newFeatureButtonPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
            add(newFeatureButton, BorderLayout.CENTER)
        }
        val treePane = JPanel(BorderLayout()).apply {
            add(newFeatureButtonPanel, BorderLayout.NORTH)
            add(JBScrollPane(pipelineTree), BorderLayout.CENTER)
        }

        // Two-pane splitter: tree | detail
        val splitter = OnePixelSplitter(false, 0.35f).apply {
            firstComponent = treePane
            secondComponent = JBScrollPane(detailPanel).apply {
                border = BorderFactory.createEmptyBorder()
            }
        }

        val headerPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(8, 12, 4, 12)
            add(JLabel("Pipeline").apply {
                font = font.deriveFont(Font.BOLD, font.size + 2f)
            }, BorderLayout.NORTH)
            add(JLabel("Validate artifacts and run each step of the spec-driven pipeline.").apply {
                foreground = JBColor.GRAY
            }, BorderLayout.CENTER)
        }
        val northPanel = JPanel(BorderLayout()).apply {
            add(headerPanel, BorderLayout.NORTH)
            add(topBar, BorderLayout.CENTER)
        }
        add(northPanel, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        // VFS listener — auto-refresh on file changes under specs/ or .specify/
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val basePath = project.basePath ?: return
                val relevant = events.any { e ->
                    val path = e.path ?: return@any false
                    path.startsWith("$basePath/specs/") || path.startsWith("$basePath/.specify/")
                }
                if (relevant) refreshAll()
            }
        })

        // App focus listener — auto-refresh on IDE regain focus (catches branch switches)
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus
            .connect(this)
            .subscribe(com.intellij.openapi.application.ApplicationActivationListener.TOPIC,
                object : com.intellij.openapi.application.ApplicationActivationListener {
                    override fun applicationActivated(ideFrame: com.intellij.openapi.wm.IdeFrame) {
                        refreshAll()
                    }
                })

        // Initial load
        refreshAll()
    }

    // ── Feature scanning ──────────────────────────────────────────────────────

    private fun getCurrentBranch(): String {
        val basePath = project.basePath ?: return "main"
        return GitHelper.currentBranch(basePath)
    }

    private fun scanFeatures(): List<FeatureEntry> {
        val basePath = project.basePath ?: return emptyList()
        return PipelineService.scanFeatures(basePath, pipelineSteps)
    }

    private fun resolvePathsForFeature(entry: FeatureEntry): FeaturePaths {
        val basePath = project.basePath ?: return FeaturePaths("", currentBranch, false, null)
        return FeaturePaths(basePath, currentBranch, true, entry.path)
    }

    // ── Refresh ──────────────────────────────────────────────────────────────

    private fun refreshAll() {
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val branch = getCurrentBranch()
            currentBranch = branch
            val features = scanFeatures()
            val basePath = project.basePath ?: ""

            // Compute step states for all features upfront
            val allStates = mutableMapOf<String, Map<PipelineStepDef, PipelineStepState>>()
            for (entry in features) {
                val stateMap = pipelineSteps.associateWith { step ->
                    PipelineStepState().also { state ->
                        state.status = PipelineService.deriveStatus(step, basePath, entry.path, state)
                    }
                }
                allStates[entry.dirName] = stateMap
            }

            invokeLater {
                branchLabel.text = "Branch: $branch"
                featureStepStates.clear()
                featureStepStates.putAll(allStates)
                rebuildTree(features)

                // Re-save pending specify args under the now-selected feature key
                val pending = pendingSpecifyArgs
                if (pending != null) {
                    saveArgs("specify", pending)
                    pendingSpecifyArgs = null
                }
            }
        }
    }

    private fun rebuildTree(features: List<FeatureEntry>) {
        // Save expansion and selection state
        val expandedDirs = mutableSetOf<String>()
        for (i in 0 until rootNode.childCount) {
            val featureNode = rootNode.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            val data = featureNode.userObject as? FeatureNodeData ?: continue
            if (pipelineTree.isExpanded(TreePath(featureNode.path))) {
                expandedDirs.add(data.entry.dirName)
            }
        }
        val previousFeature = getSelectedFeatureEntry()?.dirName
        val previousStep = getSelectedStep()?.id

        rootNode.removeAllChildren()

        for (entry in features) {
            val featureNode = DefaultMutableTreeNode(FeatureNodeData(entry))
            for (step in pipelineSteps) {
                featureNode.add(DefaultMutableTreeNode(StepNodeData(step, entry)))
            }
            rootNode.add(featureNode)
        }

        treeModel.reload()

        // Expand: branch-match feature auto-expands, plus any previously expanded
        val branchPrefix = Regex("^(\\d{3})-").find(currentBranch)?.groupValues?.get(1)
        var branchMatchNode: DefaultMutableTreeNode? = null
        for (i in 0 until rootNode.childCount) {
            val featureNode = rootNode.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            val data = featureNode.userObject as? FeatureNodeData ?: continue
            val isBranchMatch = branchPrefix != null && data.entry.dirName.startsWith("$branchPrefix-")
            if (isBranchMatch) branchMatchNode = featureNode
            if (isBranchMatch || data.entry.dirName in expandedDirs) {
                pipelineTree.expandPath(TreePath(featureNode.path))
            }
        }

        // Restore selection: try previous step > previous feature > branch match first step > first feature
        val restored = restoreSelection(previousFeature, previousStep)
        if (!restored) {
            val defaultNode = branchMatchNode ?: (if (rootNode.childCount > 0) rootNode.getChildAt(0) as? DefaultMutableTreeNode else null)
            if (defaultNode != null) {
                pipelineTree.expandPath(TreePath(defaultNode.path))
                // Select first step child
                if (defaultNode.childCount > 0) {
                    val firstStep = defaultNode.getChildAt(0) as DefaultMutableTreeNode
                    pipelineTree.selectionPath = TreePath(firstStep.path)
                } else {
                    pipelineTree.selectionPath = TreePath(defaultNode.path)
                }
            }
        }
    }

    private fun restoreSelection(featureDirName: String?, stepId: String?): Boolean {
        if (featureDirName == null) return false
        for (i in 0 until rootNode.childCount) {
            val featureNode = rootNode.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            val data = featureNode.userObject as? FeatureNodeData ?: continue
            if (data.entry.dirName != featureDirName) continue

            if (stepId != null) {
                for (j in 0 until featureNode.childCount) {
                    val stepNode = featureNode.getChildAt(j) as? DefaultMutableTreeNode ?: continue
                    val stepData = stepNode.userObject as? StepNodeData ?: continue
                    if (stepData.step.id == stepId) {
                        pipelineTree.selectionPath = TreePath(stepNode.path)
                        return true
                    }
                }
            }
            pipelineTree.selectionPath = TreePath(featureNode.path)
            return true
        }
        return false
    }

    // ── Tree selection helpers ───────────────────────────────────────────

    private fun getSelectedFeatureEntry(): FeatureEntry? {
        val node = pipelineTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return when (val data = node.userObject) {
            is FeatureNodeData -> data.entry
            is StepNodeData -> data.featureEntry
            else -> null
        }
    }

    private fun getSelectedStep(): PipelineStepDef? {
        val node = pipelineTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return (node.userObject as? StepNodeData)?.step
    }

    // ── Argument persistence & autofill ──────────────────────────────────

    private fun argsStorageKey(stepId: String): String {
        val featureName = getSelectedFeatureEntry()?.dirName ?: "_global_"
        return "speckit.args.$featureName.$stepId"
    }

    private fun saveArgs(stepId: String, value: String) {
        PropertiesComponent.getInstance(project).setValue(argsStorageKey(stepId), value)
    }

    private fun loadArgs(stepId: String): String? {
        return PropertiesComponent.getInstance(project).getValue(argsStorageKey(stepId))
    }

    // ── Detail panel ─────────────────────────────────────────────────────────

    private fun updateDetailPanel(step: PipelineStepDef) {
        detailPanel.removeAll()
        val featureEntry = getSelectedFeatureEntry()
        val state = if (featureEntry != null) featureStepStates[featureEntry.dirName]?.get(step) else null
        if (state == null) { detailPanel.revalidate(); detailPanel.repaint(); return }

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(12, 16, 12, 16)
        }

        // Header
        content.add(JLabel("Step ${displayNumber(step)}: ${step.name}").apply {
            font = font.deriveFont(Font.BOLD, font.size + 4f)
            alignmentX = Component.LEFT_ALIGNMENT
        })
        if (step.isOptional) {
            content.add(JLabel("(optional)").apply {
                foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }
        content.add(JLabel(step.description).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(4, 0, 12, 0)
        })

        // Status
        val statusLabel = JLabel("Status: ${statusText(state.status)}").apply {
            foreground = statusColor(state.status)
            font = font.deriveFont(Font.BOLD)
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(0, 0, 12, 0)
        }
        content.add(statusLabel)

        // Clarification markers (clarify step only)
        if (step.id == "clarify") {
            val paths = currentPaths
            if (paths != null) {
                val specFile = PipelineService.resolveArtifactFile("spec.md", false, paths.basePath, paths.featureDir)
                if (specFile != null && specFile.isFile) {
                    val markerCount = Regex("\\[NEEDS CLARIFICATION").findAll(specFile.readText()).count()
                    val greenColor = JBColor(Color(0, 128, 0), Color(80, 200, 80))
                    val orangeColor = JBColor(Color(200, 100, 0), Color(255, 160, 60))
                    val markerLabel = if (markerCount == 0) {
                        JLabel("No clarification markers in spec.md").apply {
                            foreground = greenColor
                        }
                    } else {
                        JLabel("$markerCount [NEEDS CLARIFICATION] marker(s) in spec.md").apply {
                            foreground = orangeColor
                            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                            addMouseListener(object : java.awt.event.MouseAdapter() {
                                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                                    openFileInEditor(specFile)
                                }
                            })
                            addHoverEffect(this)
                        }
                    }
                    markerLabel.alignmentX = Component.LEFT_ALIGNMENT
                    markerLabel.border = BorderFactory.createEmptyBorder(0, 0, 12, 0)
                    content.add(markerLabel)
                }
            }
        }

        // Prerequisites
        if (step.prerequisites.isNotEmpty()) {
            content.add(sectionHeader("Prerequisites:"))
            for (result in state.prerequisiteResults) {
                content.add(checkResultLabel(result))
            }
            content.add(verticalSpacer(8))
        }

        // Outputs
        if (step.outputs.isNotEmpty()) {
            content.add(sectionHeader("Outputs:"))
            for (result in state.outputResults) {
                content.add(checkResultLabel(result))

                // For the checklist step, list individual files under the directory output
                if (step.id == "checklist" && result.artifact.isDirectory && result.exists && result.resolvedFile != null) {
                    val files = result.resolvedFile.listFiles()
                        ?.filter { it.isFile && it.extension == "md" }
                        ?.sortedBy { it.name }
                        ?: emptyList()
                    for (file in files) {
                        val fileLabel = JLabel("      ${file.name}").apply {
                            alignmentX = Component.LEFT_ALIGNMENT
                            foreground = JBColor(Color(0, 128, 0), Color(80, 200, 80))
                            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                            addMouseListener(object : java.awt.event.MouseAdapter() {
                                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                                    openFileInEditor(file)
                                }
                            })
                            addHoverEffect(this)
                        }
                        content.add(fileLabel)
                    }
                }
            }
            content.add(verticalSpacer(8))
        }

        // Checklists (implement step only)
        if (step.id == "implement") {
            val paths = currentPaths
            if (paths != null) {
                val checklists = PipelineService.parseChecklists(paths.featureDir ?: "")
                if (checklists.isNotEmpty()) {
                    val totalItems = checklists.sumOf { it.total }
                    val completedItems = checklists.sumOf { it.completed }
                    content.add(sectionHeader("Checklists:  $completedItems/$totalItems complete"))

                    val greenColor = JBColor(Color(0, 128, 0), Color(80, 200, 80))
                    val orangeColor = JBColor(Color(200, 100, 0), Color(255, 160, 60))

                    for (cl in checklists) {
                        val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                            isOpaque = false
                            alignmentX = Component.LEFT_ALIGNMENT
                        }
                        val cb = javax.swing.JCheckBox().apply {
                            isSelected = cl.isComplete
                            isEnabled = false
                        }
                        row.add(cb)
                        val label = JLabel("${cl.fileName}  (${cl.completed}/${cl.total})").apply {
                            foreground = if (cl.isComplete) greenColor else orangeColor
                            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                            addMouseListener(object : java.awt.event.MouseAdapter() {
                                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                                    openFileInEditor(cl.file)
                                }
                            })
                            addHoverEffect(this)
                        }
                        row.add(label)
                        content.add(row)
                    }
                    content.add(verticalSpacer(8))
                }
            }
        }

        // Task list (tasks, implement, taskstoissues steps)
        if (step.id in setOf("tasks", "implement", "taskstoissues")) {
            val paths = currentPaths
            if (paths != null) {
                val taskListPanel = TaskListPanel(
                    project, chatPanel, persistenceManager,
                    enableActions = step.id == "implement",
                    launcher = launcher
                )
                taskListPanel.update(paths.featureDir)
                taskListPanel.alignmentX = Component.LEFT_ALIGNMENT
                content.add(taskListPanel)
                content.add(verticalSpacer(8))
            }
        }

        // "Yes, proceed" reply button (implement step, running with session)
        val implRun = lastImplementRun
        if (step.id == "implement" && implRun != null
            && implRun.status == ChatRunStatus.RUNNING && implRun.sessionId != null) {

            val replyButton = JButton("Yes, proceed with implementation").apply {
                alignmentX = Component.LEFT_ALIGNMENT
                addActionListener { replyToSession(implRun.sessionId!!, "yes, proceed with implementation") }
            }
            content.add(replyButton)
            content.add(verticalSpacer(8))
        }

        // Hands off to
        if (step.handsOffTo.isNotEmpty()) {
            content.add(JLabel("Hands off to \u2192 ${step.handsOffTo.joinToString(", ")}").apply {
                foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
                border = BorderFactory.createEmptyBorder(0, 0, 12, 0)
            })
        }

        // Arguments (persisted per feature+step, with autofill defaults)
        val isReadOnly = step.id == "specify"
        val argsField = JBTextArea(3, 0).apply {
            lineWrap = true
            wrapStyleWord = true
            margin = java.awt.Insets(6, 8, 6, 8)
            text = loadArgs(step.id) ?: step.defaultArgs
            isEditable = !isReadOnly
            if (isReadOnly) background = JBColor(Color(245, 245, 245), Color(60, 63, 65))
        }
        val argsHeader = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 24)
            add(JLabel("Arguments:").apply {
                font = font.deriveFont(Font.BOLD)
            }, BorderLayout.WEST)
            if (!isReadOnly) {
                add(JButton("Default").apply {
                    isBorderPainted = false
                    isContentAreaFilled = false
                    foreground = JBColor.BLUE
                    cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                    font = font.deriveFont(font.size - 1f)
                    toolTipText = "Reset to default"
                    addActionListener { argsField.text = step.defaultArgs }
                }, BorderLayout.EAST)
            }
            border = BorderFactory.createEmptyBorder(0, 0, 4, 0)
        }
        content.add(argsHeader)
        val argsScroll = JBScrollPane(argsField).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 80)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0, 0, 8, 0),
                RoundedLineBorder(JBColor.GRAY, 6)
            )
        }
        content.add(argsScroll)

        // Run button (hidden for Specify — use "Add New Specification" instead)
        if (!isReadOnly) {
            val runButton = JButton("Run ${step.name} \u25B7").apply {
                alignmentX = Component.LEFT_ALIGNMENT
                addActionListener { runStep(step, argsField.text.trim()) }
            }
            content.add(runButton)
        }

        content.add(javax.swing.Box.createVerticalGlue())

        detailPanel.add(content, BorderLayout.CENTER)
        detailPanel.revalidate()
        detailPanel.repaint()
    }

    private fun sectionHeader(text: String) = PipelineUiHelpers.sectionHeader(text)
    private fun checkResultLabel(result: CheckResult) = PipelineUiHelpers.checkResultLabel(result, project)
    private fun openFileInEditor(file: File) = PipelineUiHelpers.openFileInEditor(project, file)
    private fun addHoverEffect(label: JLabel) = PipelineUiHelpers.addHoverEffect(label)
    private fun verticalSpacer(height: Int) = PipelineUiHelpers.verticalSpacer(height)

    // ── Run step ─────────────────────────────────────────────────────────────

    private fun runStep(step: PipelineStepDef, arguments: String) {
        saveArgs(step.id, arguments)
        val basePath = project.basePath ?: return
        val agentContent = ResourceLoader.readAgent(basePath, step.agentFileName) ?: return
        val prompt = agentContent.replace("\$ARGUMENTS", arguments)

        val refreshDetail = {
            val selected = getSelectedStep()
            if (selected != null) updateDetailPanel(selected)
        }

        val run = launcher?.launch(
            prompt = prompt,
            agent = step.id,
            promptSummary = arguments.ifEmpty { "(no arguments)" },
            onSessionReceived = { if (step.id == "implement") refreshDetail() },
            onDone = { refreshAll(); refreshDetail() },
            onFail = { refreshDetail() }
        ) ?: return

        if (step.id == "implement") {
            lastImplementRun = run
        }
    }

    private fun showNewFeatureDialog() {
        val textArea = JBTextArea(6, 40).apply {
            lineWrap = true
            wrapStyleWord = true
        }

        val dialog = object : DialogWrapper(project, false) {
            init {
                title = "New Feature Specification"
                init()
            }

            override fun createCenterPanel(): JComponent {
                return JPanel(BorderLayout(0, 8)).apply {
                    add(JLabel("Describe the feature you want to build:"), BorderLayout.NORTH)
                    add(JBScrollPane(textArea), BorderLayout.CENTER)
                    preferredSize = Dimension(460, 220)
                }
            }

            override fun getPreferredFocusedComponent() = textArea
        }

        if (!dialog.showAndGet()) return  // user cancelled

        val description = textArea.text.trim()
        if (description.isBlank()) return

        val specifyStep = pipelineSteps.firstOrNull { it.id == "specify" } ?: return

        // Stash the description so onComplete can re-save under the correct feature key
        pendingSpecifyArgs = description

        // Run the Specify agent with the description
        runStep(specifyStep, description)
    }

    private fun replyToSession(sessionId: String, message: String) {
        val chatService = project.getService(CopilotChatService::class.java) ?: return
        val dataContext = SimpleDataContext.getProjectContext(project)
        chatService.query(dataContext) {
            withInput(message)
            withExistingSession(sessionId)
            withAgentMode()
        }
    }

    // ── Feature detail panel ────────────────────────────────────────────────

    private fun updateFeatureDetailPanel(entry: FeatureEntry) {
        detailPanel.removeAll()
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(12, 16, 12, 16)
        }

        content.add(JLabel(entry.dirName).apply {
            font = font.deriveFont(Font.BOLD, font.size + 4f)
            alignmentX = Component.LEFT_ALIGNMENT
        })
        content.add(JLabel("${entry.completedSteps}/${entry.totalOutputSteps} output steps completed").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(4, 0, 12, 0)
        })

        val states = featureStepStates[entry.dirName]
        if (states != null) {
            content.add(sectionHeader("Steps:"))
            for (step in pipelineSteps) {
                val status = states[step]?.status ?: StepStatus.NOT_STARTED
                content.add(JLabel("  ${statusIcon(status)}  ${displayNumber(step)}. ${step.name} \u2014 ${statusText(status)}").apply {
                    foreground = statusColor(status)
                    alignmentX = Component.LEFT_ALIGNMENT
                })
            }
        }

        content.add(javax.swing.Box.createVerticalGlue())
        detailPanel.add(content, BorderLayout.CENTER)
        detailPanel.revalidate()
        detailPanel.repaint()
    }

    // ── Tree cell renderer ───────────────────────────────────────────────────

    private inner class PipelineTreeCellRenderer : JPanel(BorderLayout()), javax.swing.tree.TreeCellRenderer {
        private val connectorPanel = ConnectorPanel()
        private val connectorWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            preferredSize = Dimension(14, 0)
            add(connectorPanel, BorderLayout.CENTER)
        }
        private val iconLabel = JLabel().apply { horizontalAlignment = SwingConstants.CENTER }
        private val nameLabel = JLabel()

        init {
            isOpaque = true
            val textPanel = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(iconLabel)
                add(javax.swing.Box.createHorizontalStrut(4))
                add(nameLabel)
            }
            add(connectorWrapper, BorderLayout.WEST)
            add(textPanel, BorderLayout.CENTER)
        }

        override fun getTreeCellRendererComponent(
            tree: JTree, value: Any?, selected: Boolean,
            expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
        ): Component {
            val node = value as? DefaultMutableTreeNode
            when (val data = node?.userObject) {
                is FeatureNodeData -> renderFeature(data, tree, selected)
                is StepNodeData -> renderStep(data, node, tree, selected)
                else -> { nameLabel.text = value?.toString() ?: "" }
            }

            background = if (selected) {
                UIManager.getColor("Tree.selectionBackground") ?: tree.background
            } else {
                tree.background
            }
            return this
        }

        private fun renderFeature(data: FeatureNodeData, tree: JTree, selected: Boolean) {
            connectorWrapper.isVisible = false
            border = BorderFactory.createEmptyBorder(2, 0, 2, 4)

            val entry = data.entry
            val icon: String
            val iconColor: Color
            when {
                entry.totalOutputSteps > 0 && entry.completedSteps >= entry.totalOutputSteps -> {
                    icon = "\u2713"; iconColor = JBColor(Color(0, 128, 0), Color(80, 200, 80))
                }
                entry.completedSteps > 0 -> {
                    icon = "\u25D0"; iconColor = JBColor.BLUE
                }
                else -> {
                    icon = "\u25CB"; iconColor = JBColor.GRAY
                }
            }
            iconLabel.text = icon
            iconLabel.foreground = iconColor

            nameLabel.text = entry.dirName
            nameLabel.foreground = if (selected) {
                UIManager.getColor("Tree.selectionForeground") ?: tree.foreground
            } else tree.foreground

            val branchPrefix = Regex("^(\\d{3})-").find(currentBranch)?.groupValues?.get(1)
            val isBranchMatch = branchPrefix != null && entry.dirName.startsWith("$branchPrefix-")
            nameLabel.font = nameLabel.font.deriveFont(if (isBranchMatch) Font.BOLD else Font.PLAIN)
        }

        private fun renderStep(data: StepNodeData, node: DefaultMutableTreeNode, tree: JTree, selected: Boolean) {
            connectorWrapper.isVisible = true
            val isSubStep = data.step.parentId != null
            border = BorderFactory.createEmptyBorder(2, if (isSubStep) 12 else 0, 2, 4)

            val states = featureStepStates[data.featureEntry.dirName]
            val status = states?.get(data.step)?.status ?: StepStatus.NOT_STARTED

            iconLabel.text = statusIcon(status)
            iconLabel.foreground = statusColor(status)
            nameLabel.text = "${displayNumber(data.step)}. ${data.step.name}"
            nameLabel.foreground = if (selected) {
                UIManager.getColor("Tree.selectionForeground") ?: tree.foreground
            } else tree.foreground
            nameLabel.font = nameLabel.font.deriveFont(Font.PLAIN)

            val parent = node.parent as? DefaultMutableTreeNode
            val siblingIndex = parent?.getIndex(node) ?: 0
            val siblingCount = parent?.childCount ?: 0
            connectorPanel.isFirst = siblingIndex == 0
            connectorPanel.isLast = siblingIndex == siblingCount - 1
            connectorPanel.isSubStep = isSubStep
            connectorPanel.color = JBColor.border()
        }
    }

    // ── Display helpers ────────────────────────────────────────────────────

    private fun displayNumber(step: PipelineStepDef): String {
        if (step.parentId == null) return step.number.toString()
        val parent = pipelineSteps.firstOrNull { it.id == step.parentId }
            ?: return step.number.toString()
        val siblingIndex = pipelineSteps.filter { it.parentId == step.parentId }
            .indexOf(step) + 1
        return "${parent.number}.$siblingIndex"
    }

    // ── Status helpers (delegated to PipelineUiHelpers) ─────────────────────

    private fun statusIcon(status: StepStatus) = PipelineUiHelpers.statusIcon(status)
    private fun statusText(status: StepStatus) = PipelineUiHelpers.statusText(status)
    private fun statusColor(status: StepStatus) = PipelineUiHelpers.statusColor(status)

    override fun dispose() {}
}
