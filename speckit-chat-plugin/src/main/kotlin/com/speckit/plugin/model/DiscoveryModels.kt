package com.speckit.plugin.model

import com.intellij.ui.table.JBTable
import javax.swing.table.DefaultTableModel

data class TableRow(val category: String, val attribute: String, val answer: String)

class CategoryTable(
    val category: String,
    val tableModel: DefaultTableModel,
    val table: JBTable
)
