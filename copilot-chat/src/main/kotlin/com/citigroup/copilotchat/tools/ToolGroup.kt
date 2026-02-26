package com.citigroup.copilotchat.tools

/**
 * A cohesive group of built-in tools that share a common domain.
 * Each group provides its own schemas and executor functions.
 *
 * Executor signature mirrors the reference Copilot plugin's
 * LanguageModelToolRegistration.handleInvocation(request):
 * tools receive [ToolInvocationRequest] and resolve Project via
 * [ToolInvocationManager.findProjectForInvocation] when needed.
 */
interface ToolGroup {
    val schemas: List<String>
    val executors: Map<String, (ToolInvocationRequest) -> String>
}
