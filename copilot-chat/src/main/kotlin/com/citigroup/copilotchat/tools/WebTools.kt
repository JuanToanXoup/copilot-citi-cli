package com.citigroup.copilotchat.tools

import com.citigroup.copilotchat.tools.BuiltInToolUtils.OUTPUT_LIMIT
import com.citigroup.copilotchat.tools.BuiltInToolUtils.runCommand
import com.citigroup.copilotchat.tools.BuiltInToolUtils.str
import com.citigroup.copilotchat.tools.BuiltInToolUtils.strArray
import kotlinx.serialization.json.JsonObject

object WebTools : ToolGroup {

    override val schemas: List<String> = listOf(
        """{"name":"fetch_web_page","description":"Fetch content from one or more URLs.","inputSchema":{"type":"object","properties":{"urls":{"type":"array","items":{"type":"string"},"description":"URLs to fetch content from."},"query":{"type":"string","description":"What to look for in the page content."}},"required":["urls","query"]}}""",
        """{"name":"github_repo","description":"Search code in a GitHub repository using the GitHub CLI (gh).","inputSchema":{"type":"object","properties":{"repo":{"type":"string","description":"The GitHub repository in 'owner/repo' format."},"query":{"type":"string","description":"The search query for code search."}},"required":["repo","query"]}}""",
    )

    override val executors: Map<String, (ToolInvocationRequest) -> String> = mapOf(
        "fetch_web_page" to ::executeFetchWebPage,
        "github_repo" to ::executeGithubRepo,
    )

    private fun executeFetchWebPage(request: ToolInvocationRequest): String {
        val input = request.input
        val urls = input.strArray("urls") ?: return "Error: urls is required"
        return urls.take(5).joinToString("\n\n") { url ->
            try {
                val content = java.net.URI(url).toURL().readText().take(8000)
                "=== $url ===\n$content"
            } catch (e: Exception) {
                "=== $url ===\nError: ${e.message}"
            }
        }
    }

    private fun executeGithubRepo(request: ToolInvocationRequest): String {
        val input = request.input
        val repo = input.str("repo") ?: return "Error: repo is required"
        val query = input.str("query") ?: return "Error: query is required"
        return runCommand(listOf("gh", "search", "code", "--repo", repo, query), timeout = 30)
            .take(OUTPUT_LIMIT).ifBlank { "No results found." }
    }
}
