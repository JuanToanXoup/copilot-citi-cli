package com.citigroup.copilotchat.ui

import com.citigroup.copilotchat.agent.AgentDefinition
import com.citigroup.copilotchat.agent.AgentRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Simplified dialog for creating new agents.
 * Collects only Name and Description — all further config happens
 * inline in the AgentConfigPanel right panel.
 */
class AgentDefinitionDialog(
    private val project: Project,
    private val defaults: AgentDefinition,
    private val isSupervisor: Boolean,
) : DialogWrapper(project) {

    private val nameField = JBTextField("", 30)
    private val descField = JBTextField("", 30)

    init {
        title = if (isSupervisor) "New Lead Agent" else "New Subagent"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4)
            gridx = 0; gridy = 0; weightx = 0.0
        }

        // Name
        gbc.gridx = 0; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
        panel.add(JLabel("Name:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(nameField, gbc)
        gbc.gridy++

        // Description
        gbc.gridx = 0; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
        panel.add(JLabel("Description:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(descField, gbc)

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(panel, BorderLayout.NORTH)
        }
    }

    override fun doValidate(): ValidationInfo? {
        val name = nameField.text.trim()
        if (name.isBlank()) return ValidationInfo("Name is required", nameField)
        if (!AgentRegistry.isValidAgentName(name)) {
            return ValidationInfo("Name must only contain [a-zA-Z0-9._-]", nameField)
        }
        val desc = descField.text.trim()
        if (desc.isBlank()) return ValidationInfo("Description is required", descField)
        if (desc.length > 500) return ValidationInfo("Description must be 500 chars or less", descField)
        return null
    }

    fun getAgentName(): String = nameField.text.trim()
    fun getAgentDescription(): String = descField.text.trim()
}
