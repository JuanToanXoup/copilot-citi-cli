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
import com.speckit.plugin.ui.component.PipelineUiHelpers
import com.speckit.plugin.ui.pipeline.FeatureNodeData
import com.speckit.plugin.ui.pipeline.PipelineTreeDataProvider
import com.speckit.plugin.ui.pipeline.PipelineTreeRenderer
import com.speckit.plugin.ui.pipeline.StepDetailPanel
import com.speckit.plugin.ui.pipeline.StepNodeData
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
) : JPanel(BorderLayout()), Disposable, PipelineTreeDataProvider {

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

    private val stepDetailPanel = StepDetailPanel(project, chatPanel, persistenceManager, launcher)

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
        pipelineTree.cellRenderer = PipelineTreeRenderer(this)
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

        // Wire StepDetailPanel callbacks
        stepDetailPanel.onRunStep = { step, args -> runStep(step, args) }
        stepDetailPanel.onReplyToSession = { sessionId, msg -> replyToSession(sessionId, msg) }
        stepDetailPanel.displayNumber = { step -> displayNumber(step) }
        stepDetailPanel.loadArgs = { stepId -> loadArgs(stepId) }

        pipelineTree.addTreeSelectionListener {
            val node = pipelineTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            when (val data = node.userObject) {
                is StepNodeData -> {
                    currentPaths = resolvePathsForFeature(data.featureEntry)
                    val state = featureStepStates[data.featureEntry.dirName]?.get(data.step)
                    stepDetailPanel.showStep(data.step, state, currentPaths, lastImplementRun)
                }
                is FeatureNodeData -> {
                    currentPaths = resolvePathsForFeature(data.entry)
                    stepDetailPanel.showFeature(data.entry, featureStepStates[data.entry.dirName], pipelineSteps)
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
            secondComponent = JBScrollPane(stepDetailPanel).apply {
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
            val branch = GitHelper.currentBranch(project.basePath ?: "main")
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

    // ── Run step ─────────────────────────────────────────────────────────────

    private fun runStep(step: PipelineStepDef, arguments: String) {
        saveArgs(step.id, arguments)
        val basePath = project.basePath ?: return
        val agentContent = ResourceLoader.readAgent(basePath, step.agentFileName) ?: return
        val prompt = agentContent.replace("\$ARGUMENTS", arguments)

        val refreshDetail = {
            val selected = getSelectedStep()
            val featureEntry = getSelectedFeatureEntry()
            if (selected != null && featureEntry != null) {
                val state = featureStepStates[featureEntry.dirName]?.get(selected)
                stepDetailPanel.showStep(selected, state, currentPaths, lastImplementRun)
            }
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

    // ── PipelineTreeDataProvider implementation ─────────────────────────────

    override fun getStepStates(featureDirName: String) = featureStepStates[featureDirName]
    override fun getCurrentBranch(): String = currentBranch
    override fun getPipelineSteps(): List<PipelineStepDef> = pipelineSteps

    // ── Display/status helpers (used by detail panels, delegated) ─────────

    private fun displayNumber(step: PipelineStepDef): String {
        if (step.parentId == null) return step.number.toString()
        val parent = pipelineSteps.firstOrNull { it.id == step.parentId }
            ?: return step.number.toString()
        val siblingIndex = pipelineSteps.filter { it.parentId == step.parentId }
            .indexOf(step) + 1
        return "${parent.number}.$siblingIndex"
    }

    private fun statusIcon(status: StepStatus) = PipelineUiHelpers.statusIcon(status)
    private fun statusText(status: StepStatus) = PipelineUiHelpers.statusText(status)
    private fun statusColor(status: StepStatus) = PipelineUiHelpers.statusColor(status)

    override fun dispose() {}
}
