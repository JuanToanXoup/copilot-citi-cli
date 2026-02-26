package com.speckit.plugin.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class AgentRunPanel(
    private val project: Project,
    parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    private val tracker = project.service<AgentRunTracker>()
    private val tableModel = AgentRunTableModel(tracker)
    private val table = JBTable(tableModel)
    private val detailConsole: ConsoleView

    private var selectedRun: AgentRun? = null
    private var detailShownChunkCount: Int = 0

    init {
        detailConsole = TextConsoleBuilderFactory.getInstance()
            .createBuilder(project)
            .console
        Disposer.register(parentDisposable, detailConsole)
        Disposer.register(parentDisposable, this)

        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.setShowGrid(false)
        table.autoResizeMode = JBTable.AUTO_RESIZE_ALL_COLUMNS
        table.setAutoCreateColumnsFromModel(true)

        val leftAligned = DefaultTableCellRenderer().apply {
            horizontalAlignment = javax.swing.SwingConstants.LEFT
        }
        for (i in 0 until table.columnCount) {
            if (i == 2) {
                table.columnModel.getColumn(i).cellRenderer = StatusCellRenderer()
            } else {
                table.columnModel.getColumn(i).cellRenderer = leftAligned
            }
        }
        table.tableHeader.defaultRenderer = DefaultTableCellRenderer().apply {
            horizontalAlignment = javax.swing.SwingConstants.LEFT
        }

        table.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                showSelectedRunDetail()
            }
        }

        val splitter = JBSplitter(true, 0.35f)
        splitter.firstComponent = JScrollPane(table)
        splitter.secondComponent = detailConsole.component

        add(splitter, BorderLayout.CENTER)
    }

    fun selectRun(index: Int) {
        if (index in 0 until tableModel.rowCount) {
            table.selectionModel.setSelectionInterval(index, index)
        }
    }

    fun refreshDetailIfSelected(run: AgentRun) {
        if (run !== selectedRun) return
        invokeLater {
            if (project.isDisposed) return@invokeLater
            val chunks = synchronized(run.output) { run.output.toList() }
            for (i in detailShownChunkCount until chunks.size) {
                val (text, type) = chunks[i]
                detailConsole.print(text, type)
            }
            detailShownChunkCount = chunks.size
        }
    }

    private fun showSelectedRunDetail() {
        val row = table.selectedRow
        if (row < 0) {
            selectedRun = null
            return
        }
        val run = tracker.runs.getOrNull(row) ?: return
        selectedRun = run
        detailShownChunkCount = 0
        detailConsole.clear()
        val chunks = synchronized(run.output) { run.output.toList() }
        for ((text, type) in chunks) {
            detailConsole.print(text, type)
        }
        detailShownChunkCount = chunks.size
    }

    override fun dispose() {}
}

// ── Table model ─────────────────────────────────────────────────────────────

class AgentRunTableModel(private val tracker: AgentRunTracker) : AbstractTableModel() {

    private val columns = arrayOf("Time", "Agent", "Status", "Duration", "Tools")

    init {
        tracker.addChangeListener {
            SwingUtilities.invokeLater { fireTableDataChanged() }
        }
    }

    override fun getRowCount(): Int = tracker.runs.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val runs = tracker.runs
        if (rowIndex >= runs.size) return ""
        val run = runs[rowIndex]
        return when (columnIndex) {
            0 -> java.text.SimpleDateFormat("MM/dd hh:mm:ss a").format(java.util.Date(run.startTimeMillis))
            1 -> run.agentName
            2 -> run.status
            3 -> if (run.status == AgentRunStatus.RUNNING) "..."
                 else String.format("%.1fs", run.durationMs / 1000.0)
            4 -> synchronized(run.toolCalls) { run.toolCalls.size }
            else -> ""
        }
    }
}

// ── Status cell renderer ────────────────────────────────────────────────────

private class StatusCellRenderer : DefaultTableCellRenderer() {
    init { horizontalAlignment = javax.swing.SwingConstants.LEFT }
    override fun getTableCellRendererComponent(
        table: javax.swing.JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        val status = value as? AgentRunStatus ?: return component
        text = when (status) {
            AgentRunStatus.RUNNING -> "Running..."
            AgentRunStatus.COMPLETED -> "Completed"
            AgentRunStatus.FAILED -> "Failed"
        }
        if (!isSelected) {
            foreground = when (status) {
                AgentRunStatus.RUNNING -> JBColor.BLUE
                AgentRunStatus.COMPLETED -> JBColor(Color(0, 128, 0), Color(80, 200, 80))
                AgentRunStatus.FAILED -> JBColor.RED
            }
        }
        return component
    }
}
