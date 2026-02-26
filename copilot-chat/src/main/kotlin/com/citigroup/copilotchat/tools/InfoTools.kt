package com.citigroup.copilotchat.tools

import com.citigroup.copilotchat.tools.BuiltInToolUtils.OUTPUT_LIMIT
import com.citigroup.copilotchat.tools.BuiltInToolUtils.runCommand
import com.citigroup.copilotchat.tools.BuiltInToolUtils.str
import com.citigroup.copilotchat.tools.BuiltInToolUtils.strArray
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import kotlinx.serialization.json.JsonObject
import java.io.File

object InfoTools : ToolGroup {

    private val log = Logger.getInstance(InfoTools::class.java)

    override val schemas: List<String> = listOf(
        """{"name":"get_errors","description":"Check files for syntax/compile errors.","inputSchema":{"type":"object","properties":{"filePaths":{"type":"array","items":{"type":"string"},"description":"File paths to check. Omit for all files."}},"required":[]}}""",
        """{"name":"get_doc_info","description":"Extract documentation — docstrings, module-level comments, and function/class signatures — from source files.","inputSchema":{"type":"object","properties":{"filePaths":{"type":"array","items":{"type":"string"},"description":"File paths to extract documentation from."}},"required":["filePaths"]}}""",
        """{"name":"get_project_setup_info","description":"Return project setup information — detected frameworks, config files, entry points, and common commands.","inputSchema":{"type":"object","properties":{"projectType":{"type":"string","description":"Hint for what kind of project: 'auto', 'python', 'node', 'java', 'go', 'rust', etc."}},"required":["projectType"]}}""",
        """{"name":"get_library_docs","description":"Fetches up-to-date documentation and code examples for a library. You MUST call resolve_library_id first to get the correct library ID. Bundled docs are available for: playwright, selenium, cucumber, gherkin, java, mermaid. For other libraries, falls back to Context7 API.","inputSchema":{"type":"object","properties":{"libraryId":{"type":"string","description":"Library ID obtained from resolve_library_id. Use the exact ID returned."},"query":{"type":"string","description":"Specific topic, API, or task to search for (e.g. 'how to click a button', 'locator strategies', 'wait for element')."}},"required":["libraryId","query"]}}""",
        """{"name":"resolve_library_id","description":"Resolves a library/package name to a library ID for use with get_library_docs. You MUST call this BEFORE get_library_docs to get the correct ID. Bundled: playwright, selenium, cucumber, gherkin, java, mermaid. Other libraries are resolved via Context7 API.","inputSchema":{"type":"object","properties":{"libraryName":{"type":"string","description":"Library or package name to resolve (e.g. 'playwright', 'selenium-java', 'cucumber', 'mermaid')."},"query":{"type":"string","description":"Optional: the user's question to help disambiguate results."}},"required":["libraryName"]}}""",
    )

    override val executors: Map<String, (ToolInvocationRequest) -> String> = mapOf(
        "get_errors" to ::executeGetErrors,
        "get_doc_info" to ::executeGetDocInfo,
        "get_project_setup_info" to ::executeGetProjectSetupInfo,
        "get_library_docs" to ::executeGetLibraryDocs,
        "resolve_library_id" to ::executeResolveLibraryId,
    )

    private fun executeGetErrors(request: ToolInvocationRequest): String {
        val input = request.input
        val filePaths = input.strArray("filePaths")
        return if (filePaths != null && filePaths.isNotEmpty()) {
            filePaths.joinToString("\n") { path ->
                val ext = File(path).extension
                when (ext) {
                    "py" -> runCommand(listOf("python3", "-m", "py_compile", path), timeout = 10).ifBlank { "$path: OK" }
                    "kt", "java" -> "$path: (use IDE diagnostics for compile errors)"
                    else -> "$path: (no checker available)"
                }
            }
        } else {
            "Specify filePaths to check for errors."
        }
    }

    private fun executeGetDocInfo(request: ToolInvocationRequest): String {
        val input = request.input
        val filePaths = input.strArray("filePaths") ?: return "Error: filePaths is required"
        return filePaths.joinToString("\n\n") { path ->
            val file = File(path)
            if (!file.exists()) return@joinToString "$path: not found"
            val content = file.readText().take(OUTPUT_LIMIT)
            val lines = content.lines()
            val docs = lines.filter { line ->
                val trimmed = line.trim()
                trimmed.startsWith("\"\"\"") || trimmed.startsWith("///") || trimmed.startsWith("/**") ||
                        trimmed.startsWith("def ") || trimmed.startsWith("class ") || trimmed.startsWith("fun ") ||
                        trimmed.startsWith("function ") || trimmed.startsWith("public ") || trimmed.startsWith("private ")
            }
            "=== $path ===\n${docs.joinToString("\n")}"
        }
    }

    private fun executeGetProjectSetupInfo(request: ToolInvocationRequest): String {
        val input = request.input
        val ws = request.workspaceRoot
        val configFiles = listOf(
            "pyproject.toml", "setup.py", "requirements.txt",
            "package.json", "tsconfig.json",
            "build.gradle.kts", "build.gradle", "pom.xml",
            "go.mod", "Cargo.toml", "Makefile", "Dockerfile",
        )
        val found = configFiles.filter { File(ws, it).exists() }
        val tree = runCommand(listOf("ls", "-la", ws), timeout = 5)
        return "Project root: $ws\nConfig files found: ${found.joinToString(", ")}\n\nDirectory listing:\n$tree"
    }

    private fun executeGetLibraryDocs(request: ToolInvocationRequest): String {
        val input = request.input
        val rawId = input.str("libraryId") ?: return "Error: libraryId is required"
        val query = input.str("query") ?: ""

        val libraryId = rawId.trim().removePrefix("/").split("/").last().lowercase()

        val localResult = LibraryDocs.searchDocs(libraryId, query, OUTPUT_LIMIT)
        if (localResult.isNotEmpty()) return localResult

        if (rawId.isNotBlank() && query.isNotBlank()) {
            try {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val encodedId = java.net.URLEncoder.encode(rawId, "UTF-8")
                val url = "https://context7.com/api/v2/context?query=$encodedQuery&libraryId=$encodedId&tokens=2000"
                val response = HttpRequests.request(url)
                    .accept("application/json")
                    .connectTimeout(15_000)
                    .readTimeout(15_000)
                    .readString()
                if (response.isNotBlank()) return response.take(OUTPUT_LIMIT)
            } catch (e: Exception) {
                log.info("Context7 API fallback failed for '$rawId': ${e.message}")
            }
        }

        return "No documentation found for library '$rawId'. Bundled docs available for: ${LibraryDocs.bundledIds.joinToString(", ")}."
    }

    private fun executeResolveLibraryId(request: ToolInvocationRequest): String {
        val input = request.input
        val libraryName = input.str("libraryName") ?: return "Error: libraryName is required"

        val localMatches = LibraryDocs.resolve(libraryName)
        if (localMatches.isNotEmpty()) {
            return buildString {
                for (lib in localMatches) {
                    appendLine("- ID: ${lib.id}")
                    appendLine("  Title: ${lib.title}")
                    appendLine("  Description: ${lib.description}")
                    appendLine("  Source: bundled")
                }
                append("Use the ID with get_library_docs to fetch documentation.")
            }
        }

        try {
            val encodedName = java.net.URLEncoder.encode(libraryName, "UTF-8")
            val url = "https://context7.com/api/v2/libs/search?query=$encodedName&libraryName=$encodedName"
            val response = HttpRequests.request(url)
                .accept("application/json")
                .connectTimeout(10_000)
                .readTimeout(10_000)
                .readString()
            if (response.isNotBlank()) {
                return "Context7 results for '$libraryName':\n${response.take(OUTPUT_LIMIT)}\n\nUse the returned ID with get_library_docs."
            }
        } catch (e: Exception) {
            log.info("Context7 API search failed for '$libraryName': ${e.message}")
        }

        return "No bundled docs for '$libraryName' and Context7 is unreachable. Bundled libraries: ${LibraryDocs.bundledIds.joinToString(", ")}."
    }
}
