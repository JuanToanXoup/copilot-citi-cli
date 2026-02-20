package com.citigroup.copilotchat.actions

import com.citigroup.copilotchat.conversation.ConversationManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class NewConversationAction : AnAction("New Conversation", "Start a new chat conversation", null) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ConversationManager.getInstance(project).newConversation()
    }
}
