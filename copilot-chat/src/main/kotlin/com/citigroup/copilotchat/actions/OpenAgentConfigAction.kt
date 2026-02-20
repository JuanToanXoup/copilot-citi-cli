package com.citigroup.copilotchat.actions

import com.citigroup.copilotchat.config.CopilotChatSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

class OpenAgentConfigAction : AnAction("Open Agent Config", "Open the agent configuration file", null) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val configPath = CopilotChatSettings.getInstance().agentConfigPath
        if (configPath.isBlank()) return

        val file = File(configPath)
        if (!file.exists()) return

        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: return
        FileEditorManager.getInstance(project).openFile(vf, true)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = CopilotChatSettings.getInstance().agentConfigPath.isNotBlank()
    }
}
