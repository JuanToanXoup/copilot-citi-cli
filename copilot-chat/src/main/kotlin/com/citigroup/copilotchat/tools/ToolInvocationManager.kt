package com.citigroup.copilotchat.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * Application-level service that resolves [Project] for a tool invocation.
 *
 * Mirrors the reference Copilot plugin's ToolInvocationManager:
 *   val mgr = ApplicationManager.getApplication().getService(ToolInvocationManager::class.java)
 *   val project = mgr.findProjectForInvocation(request.identifier)
 *
 * The dispatch layer (ToolRouter / ConversationManager) registers the
 * conversationId → Project mapping before dispatching tool calls.
 * Tools call [findProjectForInvocation] to resolve Project.
 */
@Service(Service.Level.APP)
class ToolInvocationManager {

    private val projects = ConcurrentHashMap<String, Project>()

    /**
     * Register a conversationId → Project mapping.
     * Called by the dispatch layer before tool execution.
     */
    fun registerInvocation(conversationId: String, project: Project) {
        projects[conversationId] = project
    }

    /**
     * Resolve the [Project] for a tool invocation.
     * Mirrors: ToolInvocationManager.findProjectForInvocation(identifier)
     */
    fun findProjectForInvocation(identifier: ToolInvocationIdentifier): Project? {
        val key = identifier.conversationId ?: return null
        return projects[key]
    }

    /**
     * Remove a conversationId mapping when a conversation ends.
     */
    fun clearInvocation(conversationId: String) {
        projects.remove(conversationId)
    }

    companion object {
        fun getInstance(): ToolInvocationManager =
            ApplicationManager.getApplication().getService(ToolInvocationManager::class.java)
    }
}
