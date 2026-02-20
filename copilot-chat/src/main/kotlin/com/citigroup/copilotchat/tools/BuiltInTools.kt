package com.citigroup.copilotchat.tools

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.*
import java.io.File

/**
 * All client-side tools ported from cli/src/copilot_cli/tools/.
 * The server only makes tools available to the model once the client registers them.
 */
object BuiltInTools {

    private val log = Logger.getInstance(BuiltInTools::class.java)
    private const val OUTPUT_LIMIT = 4000

    val toolNames: Set<String> get() = executors.keys

    private val executors: Map<String, (JsonObject, String) -> String> = mapOf(
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
        "memory" to ::executeMemory,
        "github_repo" to ::executeGithubRepo,
        "get_library_docs" to ::executeGetLibraryDocs,
        "resolve_library_id" to ::executeResolveLibraryId,
    )

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
        """{"name":"get_library_docs","description":"Fetches documentation and code examples for a library. Call resolve_library_id first to get the library ID.","inputSchema":{"type":"object","properties":{"libraryId":{"type":"string","description":"Library ID from resolve_library_id."},"query":{"type":"string","description":"Specific question or task."}},"required":["libraryId","query"]}}""",
        """{"name":"resolve_library_id","description":"Resolves a library/package name to a library ID. Call this BEFORE get_library_docs.","inputSchema":{"type":"object","properties":{"libraryName":{"type":"string","description":"Library name to search for."},"query":{"type":"string","description":"The user's question or task."}},"required":["libraryName"]}}""",
        // ── Web & External ──
        """{"name":"fetch_web_page","description":"Fetch content from one or more URLs.","inputSchema":{"type":"object","properties":{"urls":{"type":"array","items":{"type":"string"},"description":"URLs to fetch content from."},"query":{"type":"string","description":"What to look for in the page content."}},"required":["urls","query"]}}""",
        """{"name":"github_repo","description":"Search code in a GitHub repository using the GitHub CLI (gh).","inputSchema":{"type":"object","properties":{"repo":{"type":"string","description":"The GitHub repository in 'owner/repo' format."},"query":{"type":"string","description":"The search query for code search."}},"required":["repo","query"]}}""",
        // ── Utilities ──
        """{"name":"memory","description":"Persistent memory store. Save, read, list, or delete named memory files for cross-session recall.","inputSchema":{"type":"object","properties":{"command":{"type":"string","description":"The operation: 'save', 'read', 'list', or 'delete'.","enum":["save","read","list","delete"]},"path":{"type":"string","description":"Memory file name. Required for save/read/delete."},"content":{"type":"string","description":"Content to save. Required for 'save' command."}},"required":["command"]}}""",
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
        return runCommand(cmd, timeout = 30).ifBlank { "No matches found." }
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
        val libraryId = input.str("libraryId") ?: return "Error: libraryId is required"
        val query = input.str("query") ?: ""
        return "Library docs lookup for '$libraryId' (query: $query) — use resolve_library_id first. Bundled docs available for: Playwright, Selenium, Cucumber, Gherkin, Java."
    }

    private fun executeResolveLibraryId(input: JsonObject, ws: String): String {
        val libraryName = input.str("libraryName") ?: return "Error: libraryName is required"
        val known = mapOf(
            "playwright" to "playwright", "selenium" to "selenium",
            "cucumber" to "cucumber", "gherkin" to "gherkin", "java" to "java",
        )
        val id = known[libraryName.lowercase()]
        return if (id != null) "Resolved: $id" else "Unknown library: $libraryName. Try Context7 API for external docs."
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

    private fun executeMemory(input: JsonObject, ws: String): String {
        val command = input.str("command") ?: return "Error: command is required"
        val memDir = File(System.getProperty("user.home"), ".copilot-cli/memories")
        memDir.mkdirs()
        return when (command) {
            "save" -> {
                val path = input.str("path") ?: return "Error: path is required for save"
                val content = input.str("content") ?: return "Error: content is required for save"
                File(memDir, path).writeText(content)
                "Saved: $path"
            }
            "read" -> {
                val path = input.str("path") ?: return "Error: path is required for read"
                val file = File(memDir, path)
                if (file.exists()) file.readText() else "Memory not found: $path"
            }
            "list" -> {
                memDir.listFiles()?.joinToString("\n") { it.name } ?: "No memories."
            }
            "delete" -> {
                val path = input.str("path") ?: return "Error: path is required for delete"
                val file = File(memDir, path)
                if (file.exists()) { file.delete(); "Deleted: $path" } else "Not found: $path"
            }
            else -> "Unknown memory command: $command"
        }
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
