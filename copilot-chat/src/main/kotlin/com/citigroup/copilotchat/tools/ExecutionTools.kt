package com.citigroup.copilotchat.tools

import com.citigroup.copilotchat.tools.BuiltInToolUtils.runCommand
import com.citigroup.copilotchat.tools.BuiltInToolUtils.str
import kotlinx.serialization.json.JsonObject

object ExecutionTools : ToolGroup {

    override val schemas: List<String> = listOf(
        """{"name":"run_in_terminal","description":"Run a shell command in the terminal.","inputSchema":{"type":"object","properties":{"command":{"type":"string","description":"The command to run."},"explanation":{"type":"string","description":"What this command does."}},"required":["command","explanation"]}}""",
        """{"name":"run_tests","description":"Run tests using the project's test framework.","inputSchema":{"type":"object","properties":{"command":{"type":"string","description":"The test command to run."},"explanation":{"type":"string","description":"What tests are being run."}},"required":["command"]}}""",
    )

    override val executors: Map<String, (ToolInvocationRequest) -> String> = mapOf(
        "run_in_terminal" to ::executeRunInTerminal,
        "run_tests" to ::executeRunTests,
    )

    private fun executeRunInTerminal(request: ToolInvocationRequest): String {
        val input = request.input
        val ws = request.workspaceRoot
        val command = input.str("command") ?: return "Error: command is required"
        return runCommand(listOf("sh", "-c", command), workingDir = ws, timeout = 60)
    }

    private fun executeRunTests(request: ToolInvocationRequest): String {
        val input = request.input
        val ws = request.workspaceRoot
        val command = input.str("command") ?: return "Error: command is required"
        return runCommand(listOf("sh", "-c", command), workingDir = ws, timeout = 120)
    }
}
