package com.citigroup.copilotchat.tools

import com.citigroup.copilotchat.rag.LocalEmbeddings
import com.citigroup.copilotchat.rag.VectorPoint
import com.citigroup.copilotchat.rag.VectorStore
import com.citigroup.copilotchat.ui.PlaywrightManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * All client-side tools ported from cli/src/copilot_cli/tools/.
 * The server only makes tools available to the model once the client registers them.
 */
object BuiltInTools {

    private val log = Logger.getInstance(BuiltInTools::class.java)
    private const val OUTPUT_LIMIT = 4000

    val toolNames: Set<String> get() = executors.keys

    private val executors: Map<String, (JsonObject, String) -> String> by lazy { mapOf(
        "read_file" to ::executeReadFile,
        "list_dir" to ::executeListDir,
        "grep_search" to ::executeGrepSearch,
        "file_search" to ::executeFileSearch,
        "create_file" to ::executeCreateFile,
        "create_directory" to ::executeCreateDirectory,
        "insert_edit_into_file" to ::executeInsertEdit,
        "replace_string_in_file" to ::executeReplaceString,
        "multi_replace_string" to ::executeMultiReplace,
        "apply_patch" to ::executeApplyPatch,
        "run_in_terminal" to ::executeRunInTerminal,
        "run_tests" to ::executeRunTests,
        "find_test_files" to ::executeFindTestFiles,
        "get_errors" to ::executeGetErrors,
        "get_changed_files" to ::executeGetChangedFiles,
        "fetch_web_page" to ::executeFetchWebPage,
        "list_code_usages" to ::executeListCodeUsages,
        "search_workspace_symbols" to ::executeSearchWorkspaceSymbols,
        "get_doc_info" to ::executeGetDocInfo,
        "get_project_setup_info" to ::executeGetProjectSetupInfo,
        "semantic_search" to ::executeSemanticSearch,
        "remember" to ::executeRemember,
        "recall" to ::executeRecall,
        "github_repo" to ::executeGithubRepo,
        "get_library_docs" to ::executeGetLibraryDocs,
        "resolve_library_id" to ::executeResolveLibraryId,
        "browser_record" to ::executeBrowserRecord,
        // Agent tools — fallback executors so ToolRouter routes them properly.
        // Actual execution is handled by AgentService for the lead conversation.
        "delegate_task" to { _, _ -> "Error: delegate_task is only available in the Agent tab" },
        "create_team" to { _, _ -> "Error: create_team is only available in the Agent tab" },
        "send_message" to { _, _ -> "Error: send_message is only available in the Agent tab" },
        "delete_team" to { _, _ -> "Error: delete_team is only available in the Agent tab" },
    ) }

    /** Tool schemas in the format expected by conversation/registerTools. */
    val schemas: List<String> = listOf(
        // ── File Operations ──
        """{"name":"read_file","description":"Read the contents of a file, optionally specifying a line range.","inputSchema":{"type":"object","properties":{"filePath":{"type":"string","description":"The absolute path of the file to read."},"startLineNumberBaseOne":{"type":"number","description":"Start line (1-based). Default: 1."},"endLineNumberBaseOne":{"type":"number","description":"End line inclusive (1-based). Default: end of file."}},"required":["filePath"]}}""",
        """{"name":"list_dir","description":"List the contents of a directory.","inputSchema":{"type":"object","properties":{"path":{"type":"string","description":"The absolute path to the directory to list."}},"required":["path"]}}""",
        """{"name":"create_file","description":"Create a new file with the given content.","inputSchema":{"type":"object","properties":{"filePath":{"type":"string","description":"The absolute path for the new file."},"content":{"type":"string","description":"The content of the new file."}},"required":["filePath","content"]}}""",
        """{"name":"create_directory","description":"Create a new directory (and parent directories as needed).","inputSchema":{"type":"object","properties":{"dirPath":{"type":"string","description":"The absolute path of the directory to create."}},"required":["dirPath"]}}""",
        """{"name":"insert_edit_into_file","description":"Insert or replace text in a file. Creates the file if it doesn't exist.","inputSchema":{"type":"object","properties":{"filePath":{"type":"string","description":"The absolute path of the file to edit."},"code":{"type":"string","description":"The new code to insert."},"explanation":{"type":"string","description":"A short explanation of what this edit does."}},"required":["filePath","code"]}}""",
        """{"name":"replace_string_in_file","description":"Replace an exact string match in a file with new content.","inputSchema":{"type":"object","properties":{"filePath":{"type":"string","description":"The absolute path of the file to edit."},"oldString":{"type":"string","description":"The exact literal text to replace."},"newString":{"type":"string","description":"The replacement text."},"explanation":{"type":"string","description":"A short explanation."}},"required":["filePath","oldString","newString","explanation"]}}""",
        """{"name":"multi_replace_string","description":"Apply multiple string replacements across one or more files in a single operation.","inputSchema":{"type":"object","properties":{"explanation":{"type":"string","description":"A brief explanation of the multi-replace operation."},"replacements":{"type":"array","description":"Array of replacement operations.","items":{"type":"object","properties":{"explanation":{"type":"string"},"filePath":{"type":"string"},"oldString":{"type":"string"},"newString":{"type":"string"}},"required":["explanation","filePath","oldString","newString"]},"minItems":1}},"required":["explanation","replacements"]}}""",
        """{"name":"apply_patch","description":"Apply a unified diff patch to files.","inputSchema":{"type":"object","properties":{"input":{"type":"string","description":"The patch content to apply."},"explanation":{"type":"string","description":"A short description of what the patch does."}},"required":["input","explanation"]}}""",
        // ── Search & Navigation ──
        """{"name":"grep_search","description":"Search for a text pattern or regex in files within the workspace.","inputSchema":{"type":"object","properties":{"query":{"type":"string","description":"The pattern to search for."},"isRegexp":{"type":"boolean","description":"Whether the pattern is a regex. Default: false."},"includePattern":{"type":"string","description":"Glob pattern to filter which files to search."}},"required":["query"]}}""",
        """{"name":"file_search","description":"Search for files by name or glob pattern in the workspace.","inputSchema":{"type":"object","properties":{"query":{"type":"string","description":"Glob pattern or substring to match file names/paths."},"maxResults":{"type":"number","description":"Maximum number of results to return."}},"required":["query"]}}""",
        """{"name":"list_code_usages","description":"Find all usages/references of a symbol name in the workspace or specific files.","inputSchema":{"type":"object","properties":{"symbolName":{"type":"string","description":"The symbol name to search for."},"filePaths":{"type":"array","items":{"type":"string"},"description":"Optional list of file paths to restrict the search to."}},"required":["symbolName"]}}""",
        """{"name":"search_workspace_symbols","description":"Search for symbol definitions (functions, classes, methods, variables) in the workspace by name.","inputSchema":{"type":"object","properties":{"symbolName":{"type":"string","description":"The symbol name to search for."}},"required":["symbolName"]}}""",
        """{"name":"find_test_files","description":"Find test files associated with the given source files.","inputSchema":{"type":"object","properties":{"filePaths":{"type":"array","items":{"type":"string"},"description":"Source file paths to find tests for."}},"required":["filePaths"]}}""",
        """{"name":"get_changed_files","description":"Get the list of changed, staged, or untracked files from git.","inputSchema":{"type":"object","properties":{"repositoryPath":{"type":"string","description":"Path to the git repository. Defaults to workspace root."},"sourceControlState":{"type":"string","description":"Filter by state: 'all' (default), 'staged', 'unstaged', 'untracked'."}},"required":[]}}""",
        // ── Execution ──
        """{"name":"run_in_terminal","description":"Run a shell command in the terminal.","inputSchema":{"type":"object","properties":{"command":{"type":"string","description":"The command to run."},"explanation":{"type":"string","description":"What this command does."}},"required":["command","explanation"]}}""",
        """{"name":"run_tests","description":"Run tests using the project's test framework.","inputSchema":{"type":"object","properties":{"command":{"type":"string","description":"The test command to run."},"explanation":{"type":"string","description":"What tests are being run."}},"required":["command"]}}""",
        // ── Documentation & Info ──
        """{"name":"get_errors","description":"Check files for syntax/compile errors.","inputSchema":{"type":"object","properties":{"filePaths":{"type":"array","items":{"type":"string"},"description":"File paths to check. Omit for all files."}},"required":[]}}""",
        """{"name":"get_doc_info","description":"Extract documentation — docstrings, module-level comments, and function/class signatures — from source files.","inputSchema":{"type":"object","properties":{"filePaths":{"type":"array","items":{"type":"string"},"description":"File paths to extract documentation from."}},"required":["filePaths"]}}""",
        """{"name":"get_project_setup_info","description":"Return project setup information — detected frameworks, config files, entry points, and common commands.","inputSchema":{"type":"object","properties":{"projectType":{"type":"string","description":"Hint for what kind of project: 'auto', 'python', 'node', 'java', 'go', 'rust', etc."}},"required":["projectType"]}}""",
        """{"name":"get_library_docs","description":"Fetches up-to-date documentation and code examples for a library. You MUST call resolve_library_id first to get the correct library ID. Bundled docs are available for: playwright, selenium, cucumber, gherkin, java, mermaid. For other libraries, falls back to Context7 API.","inputSchema":{"type":"object","properties":{"libraryId":{"type":"string","description":"Library ID obtained from resolve_library_id. Use the exact ID returned."},"query":{"type":"string","description":"Specific topic, API, or task to search for (e.g. 'how to click a button', 'locator strategies', 'wait for element')."}},"required":["libraryId","query"]}}""",
        """{"name":"resolve_library_id","description":"Resolves a library/package name to a library ID for use with get_library_docs. You MUST call this BEFORE get_library_docs to get the correct ID. Bundled: playwright, selenium, cucumber, gherkin, java, mermaid. Other libraries are resolved via Context7 API.","inputSchema":{"type":"object","properties":{"libraryName":{"type":"string","description":"Library or package name to resolve (e.g. 'playwright', 'selenium-java', 'cucumber', 'mermaid')."},"query":{"type":"string","description":"Optional: the user's question to help disambiguate results."}},"required":["libraryName"]}}""",
        // ── Web & External ──
        """{"name":"fetch_web_page","description":"Fetch content from one or more URLs.","inputSchema":{"type":"object","properties":{"urls":{"type":"array","items":{"type":"string"},"description":"URLs to fetch content from."},"query":{"type":"string","description":"What to look for in the page content."}},"required":["urls","query"]}}""",
        """{"name":"github_repo","description":"Search code in a GitHub repository using the GitHub CLI (gh).","inputSchema":{"type":"object","properties":{"repo":{"type":"string","description":"The GitHub repository in 'owner/repo' format."},"query":{"type":"string","description":"The search query for code search."}},"required":["repo","query"]}}""",
        // ── Memory & Knowledge ──
        """{"name":"semantic_search","description":"Search the project's code index for relevant code snippets using semantic similarity. Returns matching code chunks ranked by relevance. The project must be indexed first (via Memory tab).","inputSchema":{"type":"object","properties":{"query":{"type":"string","description":"Natural language query describing what code you're looking for."},"topK":{"type":"number","description":"Maximum number of results to return. Default: 5."}},"required":["query"]}}""",
        """{"name":"remember","description":"Store a fact or piece of knowledge for persistent cross-session recall. Facts are embedded into the vector store and can be retrieved later with the recall tool.","inputSchema":{"type":"object","properties":{"fact":{"type":"string","description":"The fact, procedure, or insight to remember."},"category":{"type":"string","description":"Category: 'semantic' (facts about code/architecture), 'procedural' (how-to steps), 'failure' (mistakes/anti-patterns), 'general' (default).","enum":["semantic","procedural","failure","general"]}},"required":["fact"]}}""",
        """{"name":"recall","description":"Retrieve stored knowledge by topic. Uses both semantic similarity and keyword matching to find relevant memories saved with the remember tool.","inputSchema":{"type":"object","properties":{"topic":{"type":"string","description":"The topic or question to search memories for."},"category":{"type":"string","description":"Optional: filter results to a specific category.","enum":["semantic","procedural","failure","general"]}},"required":["topic"]}}""",
        // ── Browser Recording ──
        """{"name":"browser_record","description":"Record user interactions in a browser and generate Playwright test code. ONLY use when the user explicitly asks to record a test, generate test code, or use codegen. Do NOT use for general web browsing, navigation, or automation — use Playwright MCP tools for those instead. Opens a codegen browser, records interactions until closed, then returns executable test code.","inputSchema":{"type":"object","properties":{"url":{"type":"string","description":"The starting URL to navigate to. Default: https://example.com"},"target":{"type":"string","description":"Target language for generated code. Default: javascript.","enum":["javascript","python","python-async","python-pytest","csharp","java"]},"device":{"type":"string","description":"Device to emulate (e.g. 'Pixel 5', 'iPhone 12'). Optional."},"browser":{"type":"string","description":"Browser to use. Default: chromium.","enum":["chromium","firefox","webkit"]}},"required":[]}}""",
        // ── Agent Delegation ──
        """{"name":"delegate_task","description":"Delegate a task to a specialized sub-agent for autonomous execution. Available agent types: Explore (fast codebase search), Plan (architecture and design), Bash (shell commands), general-purpose (all tools).","inputSchema":{"type":"object","properties":{"description":{"type":"string","description":"A short (3-5 word) description of the task"},"prompt":{"type":"string","description":"The detailed task for the agent to perform"},"subagent_type":{"type":"string","description":"The type of agent: Explore, Plan, Bash, or general-purpose"},"model":{"type":"string","description":"Optional model override"},"max_turns":{"type":"integer","description":"Maximum number of agentic turns before stopping"}},"required":["description","prompt","subagent_type"]}}""",
        // ── Team Tools ──
        """{"name":"create_team","description":"Create a new agent team with persistent teammate agents that communicate via mailboxes.","inputSchema":{"type":"object","properties":{"name":{"type":"string","description":"Team name"},"description":{"type":"string","description":"What this team is for"},"members":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string","description":"Teammate name"},"agentType":{"type":"string","description":"Agent type (e.g., Explore, general-purpose)"},"initialPrompt":{"type":"string","description":"Initial task for this teammate"},"model":{"type":"string","description":"Optional model override"}},"required":["name","agentType","initialPrompt"]},"description":"List of teammates to spawn"}},"required":["name","members"]}}""",
        """{"name":"send_message","description":"Send a message to a teammate's mailbox.","inputSchema":{"type":"object","properties":{"to":{"type":"string","description":"Recipient teammate name"},"text":{"type":"string","description":"Message content"},"summary":{"type":"string","description":"Optional brief summary"}},"required":["to","text"]}}""",
        """{"name":"delete_team","description":"Disband the active team and stop all teammates.","inputSchema":{"type":"object","properties":{"name":{"type":"string","description":"Team name to delete"}},"required":["name"]}}""",
    )

    fun execute(name: String, input: JsonObject, workspaceRoot: String): String {
        val executor = executors[name] ?: return "Error: Unknown built-in tool: $name"
        return try {
            executor(input, workspaceRoot)
        } catch (e: Exception) {
            "Error executing $name: ${e.message}"
        }
    }

    // ── File Operations ──────────────────────────────────────────

    private fun executeReadFile(input: JsonObject, ws: String): String {
        val filePath = input.str("filePath") ?: return "Error: filePath is required"
        val file = File(filePath)
        if (!file.exists()) return "Error: File not found: $filePath"
        val lines = file.readLines()
        val start = input.int("startLineNumberBaseOne") ?: 1
        val end = input.int("endLineNumberBaseOne") ?: lines.size
        val selected = lines.subList((start - 1).coerceIn(0, lines.size), end.coerceIn(0, lines.size))
        return "File `$filePath`. Total ${lines.size} lines. Lines $start-$end:\n```\n${selected.joinToString("\n")}\n```"
    }

    private fun executeListDir(input: JsonObject, ws: String): String {
        val path = input.str("path") ?: return "Error: path is required"
        val dir = File(path)
        if (!dir.isDirectory) return "Error: Not a directory: $path"
        return dir.listFiles()?.sorted()?.joinToString("\n") { f ->
            if (f.isDirectory) "[dir]  ${f.name}" else "[file] ${f.name} (${f.length()} bytes)"
        } ?: "Error: Cannot list directory"
    }

    private fun executeCreateFile(input: JsonObject, ws: String): String {
        val filePath = input.str("filePath") ?: return "Error: filePath is required"
        val content = input.str("content") ?: return "Error: content is required"
        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return "Created file: $filePath (${content.length} chars)"
    }

    private fun executeCreateDirectory(input: JsonObject, ws: String): String {
        val dirPath = input.str("dirPath") ?: return "Error: dirPath is required"
        File(dirPath).mkdirs()
        return "Created directory: $dirPath"
    }

    private fun executeInsertEdit(input: JsonObject, ws: String): String {
        val filePath = input.str("filePath") ?: return "Error: filePath is required"
        val code = input.str("code") ?: return "Error: code is required"
        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.writeText(code)
        return "Wrote ${code.length} chars to $filePath"
    }

    private fun executeReplaceString(input: JsonObject, ws: String): String {
        val filePath = input.str("filePath") ?: return "Error: filePath is required"
        val oldString = input.str("oldString") ?: return "Error: oldString is required"
        val newString = input.str("newString") ?: return "Error: newString is required"
        val file = File(filePath)
        if (!file.exists()) return "Error: File not found: $filePath"
        val content = file.readText()
        if (oldString !in content) return "Error: oldString not found in $filePath"
        file.writeText(content.replaceFirst(oldString, newString))
        return "Replaced in $filePath"
    }

    private fun executeMultiReplace(input: JsonObject, ws: String): String {
        val replacements = input["replacements"]?.jsonArray ?: return "Error: replacements is required"
        val results = mutableListOf<String>()
        for (rep in replacements) {
            val obj = rep.jsonObject
            val filePath = obj.str("filePath") ?: continue
            val oldString = obj.str("oldString") ?: continue
            val newString = obj.str("newString") ?: continue
            val file = File(filePath)
            if (!file.exists()) { results.add("SKIP $filePath: not found"); continue }
            val content = file.readText()
            if (oldString !in content) { results.add("SKIP $filePath: oldString not found"); continue }
            file.writeText(content.replaceFirst(oldString, newString))
            results.add("OK $filePath")
        }
        return results.joinToString("\n")
    }

    private fun executeApplyPatch(input: JsonObject, ws: String): String {
        val patch = input.str("input") ?: return "Error: input is required"
        return runCommand(listOf("patch", "-p1", "--directory=$ws"), stdin = patch, timeout = 30)
    }

    // ── Search & Navigation ──────────────────────────────────────

    private fun executeGrepSearch(input: JsonObject, ws: String): String {
        val query = input.str("query") ?: return "Error: query is required"
        val isRegexp = input.bool("isRegexp") ?: false
        val include = input.str("includePattern")
        val cmd = mutableListOf("grep", "-rn")
        if (!isRegexp) cmd.add("-F")
        if (include != null) { cmd.add("--include"); cmd.add(include) }
        cmd.add(query); cmd.add(ws)
        val result = runCommand(cmd, timeout = 30)
        // grep exits 1 when no matches — not an error
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

    // ── Execution ────────────────────────────────────────────────

    private fun executeRunInTerminal(input: JsonObject, ws: String): String {
        val command = input.str("command") ?: return "Error: command is required"
        return runCommand(listOf("sh", "-c", command), workingDir = ws, timeout = 60)
    }

    private fun executeRunTests(input: JsonObject, ws: String): String {
        val command = input.str("command") ?: return "Error: command is required"
        return runCommand(listOf("sh", "-c", command), workingDir = ws, timeout = 120)
    }

    // ── Documentation & Info ─────────────────────────────────────

    private fun executeGetErrors(input: JsonObject, ws: String): String {
        // Simple fallback: run compile check if available
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

    private fun executeGetDocInfo(input: JsonObject, ws: String): String {
        val filePaths = input.strArray("filePaths") ?: return "Error: filePaths is required"
        return filePaths.joinToString("\n\n") { path ->
            val file = File(path)
            if (!file.exists()) return@joinToString "$path: not found"
            val content = file.readText().take(OUTPUT_LIMIT)
            // Extract doc comments and signatures
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

    private fun executeGetProjectSetupInfo(input: JsonObject, ws: String): String {
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

    private fun executeGetLibraryDocs(input: JsonObject, ws: String): String {
        val rawId = input.str("libraryId") ?: return "Error: libraryId is required"
        val query = input.str("query") ?: ""

        // Normalize: Context7 format "/microsoft/playwright" → extract last segment
        val libraryId = rawId.trim().removePrefix("/").split("/").last().lowercase()

        // Try local bundled docs first
        val localResult = LibraryDocs.searchDocs(libraryId, query, OUTPUT_LIMIT)
        if (localResult.isNotEmpty()) return localResult

        // Fallback: try Context7 API for external libraries
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

    private fun executeResolveLibraryId(input: JsonObject, ws: String): String {
        val libraryName = input.str("libraryName") ?: return "Error: libraryName is required"

        // Try local bundled docs first
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

        // Fallback: try Context7 API for external libraries
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

    // ── Web & External ───────────────────────────────────────────

    private fun executeFetchWebPage(input: JsonObject, ws: String): String {
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

    private fun executeGithubRepo(input: JsonObject, ws: String): String {
        val repo = input.str("repo") ?: return "Error: repo is required"
        val query = input.str("query") ?: return "Error: query is required"
        return runCommand(listOf("gh", "search", "code", "--repo", repo, query), timeout = 30)
            .take(OUTPUT_LIMIT).ifBlank { "No results found." }
    }

    // ── Utilities ────────────────────────────────────────────────

    // ── Memory & Knowledge ────────────────────────────────────

    private val memoryJson = Json { ignoreUnknownKeys = true }

    /**
     * Search project code index via vector similarity.
     */
    private fun executeSemanticSearch(input: JsonObject, ws: String): String {
        val query = input.str("query") ?: return "Error: query is required"
        val topK = input.int("topK") ?: 5

        return try {
            val store = VectorStore.getInstance()
            val collection = projectCollectionName(ws)
            store.ensureCollection(collection, LocalEmbeddings.vectorDimension())

            val queryVector = LocalEmbeddings.embed(query)
            val results = store.search(collection, queryVector, topK, 0.25f)

            if (results.isEmpty()) return "No relevant code found for: $query"

            buildString {
                for (result in results) {
                    val filePath = result.payload["filePath"] ?: continue
                    val startLine = result.payload["startLine"] ?: ""
                    val endLine = result.payload["endLine"] ?: ""
                    val symbolName = result.payload["symbolName"]
                    val content = result.payload["content"] ?: continue

                    val relativePath = if (filePath.startsWith(ws))
                        filePath.removePrefix(ws).removePrefix("/") else filePath

                    append("--- $relativePath")
                    if (startLine.isNotEmpty()) append(":$startLine-$endLine")
                    if (!symbolName.isNullOrEmpty()) append(" ($symbolName)")
                    appendLine(" [score: ${"%.2f".format(result.score)}] ---")
                    appendLine(content)
                    appendLine()
                }
            }.take(OUTPUT_LIMIT)
        } catch (e: Exception) {
            "Error: semantic search failed: ${e.message}"
        }
    }

    /**
     * Store a fact with category — persisted as JSONL on disk and embedded in vector store.
     */
    private fun executeRemember(input: JsonObject, ws: String): String {
        val fact = input.str("fact") ?: return "Error: fact is required"
        val category = input.str("category") ?: "general"

        val memDir = File(System.getProperty("user.home"), ".copilot-chat/memories")
        memDir.mkdirs()

        // Persist as JSONL (one JSON object per line)
        val entry = buildJsonObject {
            put("fact", fact)
            put("category", category)
            put("timestamp", System.currentTimeMillis().toString())
        }
        val file = File(memDir, "$category.jsonl")
        file.appendText(memoryJson.encodeToString(JsonObject.serializer(), entry) + "\n")

        // Embed into vector store for semantic recall
        try {
            val store = VectorStore.getInstance()
            val collection = MEMORIES_COLLECTION
            store.ensureCollection(collection, LocalEmbeddings.vectorDimension())

            val vector = LocalEmbeddings.embed(fact)
            val point = VectorPoint(
                id = UUID.randomUUID().toString(),
                vector = vector,
                payload = mapOf(
                    "fact" to fact,
                    "category" to category,
                    "timestamp" to System.currentTimeMillis().toString(),
                ),
            )
            store.upsertPoints(collection, listOf(point))
            store.save(collection)
        } catch (e: Exception) {
            log.warn("Failed to embed memory (file storage still succeeded): ${e.message}")
        }

        return "Remembered [$category]: $fact"
    }

    /**
     * Retrieve stored knowledge by topic — combines semantic vector search with keyword matching.
     */
    private fun executeRecall(input: JsonObject, ws: String): String {
        val topic = input.str("topic") ?: return "Error: topic is required"
        val category = input.str("category")

        val sections = mutableListOf<String>()

        // 1. Semantic search via vector store
        try {
            val store = VectorStore.getInstance()
            val collection = MEMORIES_COLLECTION
            store.ensureCollection(collection, LocalEmbeddings.vectorDimension())

            val queryVector = LocalEmbeddings.embed(topic)
            val vectorResults = store.search(collection, queryVector, 10, 0.3f)
                .filter { category == null || it.payload["category"] == category }

            if (vectorResults.isNotEmpty()) {
                sections.add("=== Semantic matches ===")
                for (r in vectorResults) {
                    val cat = r.payload["category"] ?: "general"
                    sections.add("[$cat] (score: ${"%.2f".format(r.score)}) ${r.payload["fact"]}")
                }
            }
        } catch (e: Exception) {
            log.warn("Semantic recall failed: ${e.message}")
        }

        // 2. Keyword search in JSONL files
        val memDir = File(System.getProperty("user.home"), ".copilot-chat/memories")
        if (memDir.isDirectory) {
            val files = if (category != null) {
                listOfNotNull(File(memDir, "$category.jsonl").takeIf { it.exists() })
            } else {
                memDir.listFiles()?.filter { it.extension == "jsonl" }?.toList() ?: emptyList()
            }

            val keywordMatches = mutableListOf<String>()
            val topicLower = topic.lowercase()
            for (f in files) {
                for (line in f.readLines()) {
                    if (topicLower in line.lowercase()) {
                        try {
                            val obj = memoryJson.decodeFromString<JsonObject>(line)
                            val fact = obj["fact"]?.jsonPrimitive?.contentOrNull ?: continue
                            val cat = obj["category"]?.jsonPrimitive?.contentOrNull ?: "general"
                            keywordMatches.add("[$cat] $fact")
                        } catch (_: Exception) {}
                    }
                }
            }

            if (keywordMatches.isNotEmpty()) {
                sections.add("=== Keyword matches ===")
                sections.addAll(keywordMatches.distinct())
            }
        }

        return if (sections.isEmpty()) "No memories found for: $topic"
        else sections.joinToString("\n").take(OUTPUT_LIMIT)
    }

    private const val MEMORIES_COLLECTION = "copilot-memories"

    /** Derive the project's vector store collection name from workspace root, matching RagIndexer logic. */
    private fun projectCollectionName(ws: String): String {
        val projectName = File(ws).name
        val hash = md5(projectName).take(8)
        return "copilot-chat-$hash"
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ── Browser Recording ────────────────────────────────────────

    private fun executeBrowserRecord(input: JsonObject, ws: String): String {
        val url = input.str("url") ?: "https://example.com"
        val target = input.str("target") ?: "javascript"
        val device = input.str("device")
        val browser = input.str("browser") ?: "chromium"

        val pw = PlaywrightManager
        if (!pw.isInstalled && !pw.ensureInstalled()) {
            return "Error: Failed to install Playwright. Check IDE logs for details."
        }

        val ext = when (target) {
            "python", "python-async", "python-pytest" -> ".py"
            "csharp" -> ".cs"
            "java" -> ".java"
            else -> ".js"
        }
        val outputFile = File.createTempFile("recording_", ext)
        outputFile.deleteOnExit()

        val env = pw.buildProcessEnv()
        val node = pw.resolveCommand("node", env)

        val cmd = mutableListOf(
            node, pw.playwrightCli.absolutePath, "codegen",
            "--target=$target",
            "--output=${outputFile.absolutePath}",
        )
        if (device != null) cmd.add("--device=$device")
        if (browser != "chromium") cmd.add("--browser=$browser")
        cmd.add(url)

        val process = ProcessBuilder(cmd)
            .directory(pw.home)
            .redirectErrorStream(true)
            .apply { environment().putAll(env) }
            .start()

        // Drain output in background thread to prevent buffer deadlock
        val outputCapture = StringBuilder()
        val drainThread = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { outputCapture.appendLine(it) }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        // Wait for user to close the browser (up to 10 minutes)
        val finished = process.waitFor(600, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (process.isAlive) process.destroyForcibly()
            return "Error: Recording timed out after 10 minutes."
        }

        drainThread.join(3000)

        val code = if (outputFile.exists() && outputFile.length() > 0) {
            outputFile.readText()
        } else {
            return "Recording session ended but no code was generated. The user may not have interacted with the page.\nProcess output: ${outputCapture.toString().take(2000)}"
        }

        outputFile.delete()
        return "Recording completed successfully. Generated $target code:\n\n$code"
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun runCommand(
        cmd: List<String>,
        stdin: String? = null,
        workingDir: String? = null,
        timeout: Long = 30,
    ): String {
        return try {
            val pb = ProcessBuilder(cmd)
            if (workingDir != null) pb.directory(File(workingDir))
            pb.redirectErrorStream(true)
            val process = pb.start()
            if (stdin != null) {
                process.outputStream.write(stdin.toByteArray())
                process.outputStream.close()
            }
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(timeout, java.util.concurrent.TimeUnit.SECONDS)
            val exitCode = if (process.isAlive) { process.destroyForcibly(); -1 } else process.exitValue()
            val result = output.take(OUTPUT_LIMIT)
            if (exitCode != 0 && result.isBlank()) "Exit code: $exitCode" else result
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // JSON helpers
    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.strArray(key: String): List<String>? =
        this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
}
