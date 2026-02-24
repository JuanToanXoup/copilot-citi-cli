package com.citigroup.copilotchat.tools

import com.citigroup.copilotchat.tools.BuiltInToolUtils.OUTPUT_LIMIT
import com.citigroup.copilotchat.tools.BuiltInToolUtils.runCommand
import com.citigroup.copilotchat.tools.BuiltInToolUtils.str
import com.citigroup.copilotchat.tools.BuiltInToolUtils.int
import com.citigroup.copilotchat.tools.BuiltInToolUtils.bool
import com.citigroup.copilotchat.tools.BuiltInToolUtils.strArray
import kotlinx.serialization.json.JsonObject
import java.io.File

object SearchTools : ToolGroup {

    override val schemas: List<String> = listOf(
        """{"name":"grep_search","description":"Search for a text pattern or regex in files within the workspace.","inputSchema":{"type":"object","properties":{"query":{"type":"string","description":"The pattern to search for."},"isRegexp":{"type":"boolean","description":"Whether the pattern is a regex. Default: false."},"includePattern":{"type":"string","description":"Glob pattern to filter which files to search."}},"required":["query"]}}""",
        """{"name":"file_search","description":"Search for files by name or glob pattern in the workspace.","inputSchema":{"type":"object","properties":{"query":{"type":"string","description":"Glob pattern or substring to match file names/paths."},"maxResults":{"type":"number","description":"Maximum number of results to return."}},"required":["query"]}}""",
        """{"name":"list_code_usages","description":"Find all usages/references of a symbol name in the workspace or specific files.","inputSchema":{"type":"object","properties":{"symbolName":{"type":"string","description":"The symbol name to search for."},"filePaths":{"type":"array","items":{"type":"string"},"description":"Optional list of file paths to restrict the search to."}},"required":["symbolName"]}}""",
        """{"name":"search_workspace_symbols","description":"Search for symbol definitions (functions, classes, methods, variables) in the workspace by name.","inputSchema":{"type":"object","properties":{"symbolName":{"type":"string","description":"The symbol name to search for."}},"required":["symbolName"]}}""",
        """{"name":"find_test_files","description":"Find test files associated with the given source files.","inputSchema":{"type":"object","properties":{"filePaths":{"type":"array","items":{"type":"string"},"description":"Source file paths to find tests for."}},"required":["filePaths"]}}""",
        """{"name":"get_changed_files","description":"Get the list of changed, staged, or untracked files from git.","inputSchema":{"type":"object","properties":{"repositoryPath":{"type":"string","description":"Path to the git repository. Defaults to workspace root."},"sourceControlState":{"type":"string","description":"Filter by state: 'all' (default), 'staged', 'unstaged', 'untracked'."}},"required":[]}}""",
    )

    override val executors: Map<String, (JsonObject, String) -> String> = mapOf(
        "grep_search" to ::executeGrepSearch,
        "file_search" to ::executeFileSearch,
        "list_code_usages" to ::executeListCodeUsages,
        "search_workspace_symbols" to ::executeSearchWorkspaceSymbols,
        "find_test_files" to ::executeFindTestFiles,
        "get_changed_files" to ::executeGetChangedFiles,
    )

    private fun executeGrepSearch(input: JsonObject, ws: String): String {
        val query = input.str("query") ?: return "Error: query is required"
        val isRegexp = input.bool("isRegexp") ?: false
        val include = input.str("includePattern")
        val cmd = mutableListOf("grep", "-rn")
        if (!isRegexp) cmd.add("-F")
        if (include != null) { cmd.add("--include"); cmd.add(include) }
        cmd.add(query); cmd.add(ws)
        val result = runCommand(cmd, timeout = 30)
        return if (result.isBlank() || result == "Exit code: 1") "No matches found." else result
    }

    private fun executeFileSearch(input: JsonObject, ws: String): String {
        val query = input.str("query") ?: return "Error: query is required"
        val maxResults = input.int("maxResults") ?: 50
        val root = File(ws)
        val matches = mutableListOf<String>()
        root.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isFile }.forEach { file ->
            if (matches.size >= maxResults) return@forEach
            val rel = file.relativeTo(root).path
            if (query.lowercase() in rel.lowercase()) matches.add(rel)
        }
        return matches.joinToString("\n").ifBlank { "No files found." }
    }

    private fun executeListCodeUsages(input: JsonObject, ws: String): String {
        val symbol = input.str("symbolName") ?: return "Error: symbolName is required"
        return runCommand(listOf("grep", "-rn", "-F", symbol, ws), timeout = 30).take(OUTPUT_LIMIT).ifBlank { "No usages found." }
    }

    private fun executeSearchWorkspaceSymbols(input: JsonObject, ws: String): String {
        val symbol = input.str("symbolName") ?: return "Error: symbolName is required"
        val patterns = listOf("def $symbol", "class $symbol", "fun $symbol", "function $symbol", "val $symbol", "var $symbol", "const $symbol")
        val results = mutableListOf<String>()
        for (pat in patterns) {
            val out = runCommand(listOf("grep", "-rn", "-F", pat, ws), timeout = 10)
            if (out.isNotBlank()) results.add(out)
        }
        return results.joinToString("\n").take(OUTPUT_LIMIT).ifBlank { "No definitions found for: $symbol" }
    }

    private fun executeFindTestFiles(input: JsonObject, ws: String): String {
        val filePaths = input.strArray("filePaths") ?: return "Error: filePaths is required"
        val results = mutableListOf<String>()
        for (path in filePaths) {
            val file = File(path)
            val name = file.nameWithoutExtension
            val ext = file.extension
            val candidates = listOf("test_$name.$ext", "${name}_test.$ext", "${name}Test.$ext", "tests/$name.$ext")
            val found = candidates.mapNotNull { c ->
                val f = File(file.parentFile, c)
                if (f.exists()) f.path else null
            }
            results.add("$path -> ${found.ifEmpty { listOf("no test file found") }}")
        }
        return results.joinToString("\n")
    }

    private fun executeGetChangedFiles(input: JsonObject, ws: String): String {
        val repoPath = input.str("repositoryPath") ?: ws
        val state = input.str("sourceControlState") ?: "all"
        val parts = mutableListOf<String>()
        if (state == "all" || state == "staged") {
            parts.add("=== Staged ===\n" + runCommand(listOf("git", "-C", repoPath, "diff", "--cached", "--name-status"), timeout = 10))
        }
        if (state == "all" || state == "unstaged") {
            parts.add("=== Unstaged ===\n" + runCommand(listOf("git", "-C", repoPath, "diff", "--name-status"), timeout = 10))
        }
        if (state == "all" || state == "untracked") {
            parts.add("=== Untracked ===\n" + runCommand(listOf("git", "-C", repoPath, "ls-files", "--others", "--exclude-standard"), timeout = 10))
        }
        return parts.joinToString("\n").ifBlank { "No changes." }
    }
}
