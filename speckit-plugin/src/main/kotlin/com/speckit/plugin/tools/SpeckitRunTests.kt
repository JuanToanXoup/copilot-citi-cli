package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationManager
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SpeckitRunTests : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_run_tests",
        "Detect the test command and coverage report location for a project. Auto-detects build system (Gradle/Maven/npm/pytest/Go) and coverage tooling. Returns the command to run — use run_in_terminal to execute it.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string", "description" to "Service directory — absolute path or relative to project root (default: '.')"),
                "coverage" to mapOf("type" to "boolean", "description" to "Include coverage flags in the command (default: true)")
            ),
            "required" to listOf<String>()
        ),
        null,
        "function",
        "enabled"
    )

    override suspend fun handleInvocation(
        request: ToolInvocationRequest
    ): LanguageModelToolResult {
        val manager = ApplicationManager.getApplication().getService(ToolInvocationManager::class.java)
        val project = manager.findProjectForInvocation(request.identifier)
            ?: return LanguageModelToolResult.Companion.error("No project found for invocation")
        val basePath = project.basePath
            ?: return LanguageModelToolResult.Companion.error("No project base path")

        val path = request.input?.get("path")?.asString ?: "."
        val coverage = request.input?.get("coverage")?.asBoolean ?: true
        val workDir = PathSandbox.resolveWorkDir(basePath, path)
            ?: return LanguageModelToolResult.Companion.error(
                "Path '$path' resolves outside the project root"
            )

        val lfs = LocalFileSystem.getInstance()
        val d = lfs.findFileByIoFile(File(workDir))
        if (d == null || !d.isDirectory) {
            return LanguageModelToolResult.Companion.error(
                "Directory not found: $workDir (path='$path', basePath='$basePath'). " +
                "Verify the path parameter points to a valid project directory."
            )
        }

        return withContext(Dispatchers.IO) {
        // Check discovery memory first
        val discovery = readDiscoveryMemory(workDir, basePath)
        val memoryCommand = if (coverage) discovery?.coverageCommand else discovery?.testCommand

        val command = memoryCommand
            ?: detectTestCommand(d, coverage)
            ?: return@withContext LanguageModelToolResult.Companion.error(
                "No build system detected in '$workDir' (path='$path'). Looked for: build.gradle.kts, build.gradle, pom.xml, package.json, pyproject.toml, setup.py, go.mod. " +
                "Run speckit_discover first, or provide the test command directly to run_in_terminal."
            )

        val reportPath = discovery?.coverageReportPath
        val existingReport = if (reportPath != null) {
            val fullPath = PathSandbox.resolve(workDir, reportPath)
                ?: if (reportPath.startsWith("/")) null else "$workDir/$reportPath"
            if (fullPath != null) {
                val f = lfs.findFileByIoFile(File(fullPath))
                if (f != null && !f.isDirectory) f.path else null
            } else null
        } else null
            ?: findCoverageReport(d)

        val source = if (memoryCommand != null) "discovery memory" else "auto-detect"

        val output = buildString {
            appendLine("## Detected Test Configuration")
            appendLine("- **Working directory**: $workDir")
            appendLine("- **Command**: `$command`")
            appendLine("- **Source**: $source")
            appendLine("- **Coverage enabled**: $coverage")
            if (existingReport != null) {
                appendLine("- **Existing coverage report**: $existingReport")
            }
            if (discovery?.coverageReportPath != null) {
                appendLine("- **Expected report path**: ${discovery.coverageReportPath}")
            }
            appendLine()
            appendLine("## Next Steps")
            appendLine("1. Run the command using `run_in_terminal`")
            appendLine("2. After tests complete, use `speckit_parse_coverage` to analyze the report")
        }

        LanguageModelToolResult.Companion.success(output)
        }
    }

    private fun readDiscoveryMemory(workDir: String, basePath: String): DiscoveryConfig? {
        val lfs = LocalFileSystem.getInstance()
        // Check both the workDir and basePath for the memory file
        val candidates = listOf(
            lfs.findFileByIoFile(File(workDir, ".specify/memory/discovery-report.md")),
            lfs.findFileByIoFile(File(basePath, ".specify/memory/discovery-report.md"))
        )
        val memoryFile = candidates.firstOrNull { it != null && !it.isDirectory } ?: return null
        return parseDiscoveryReport(VfsUtilCore.loadText(memoryFile))
    }

    private fun parseDiscoveryReport(content: String): DiscoveryConfig? {
        fun extractField(label: String): String? {
            val regex = Regex("""\*\*$label\*\*:\s*\[?(.+?)\]?\s*$""", RegexOption.MULTILINE)
            val match = regex.find(content) ?: return null
            val value = match.groupValues[1].trim()
            // Skip unfilled template placeholders
            if (value.startsWith("e.g.,") || value == "UNKNOWN" || value.isEmpty()) return null
            return value
        }

        val testCommand = extractField("Test command")
        val coverageCommand = extractField("Coverage command")
        val coverageReportPath = extractField("Coverage report path")
        val coverageReportFormat = extractField("Coverage report format")

        // Only return if at least one useful field was found
        if (testCommand == null && coverageCommand == null && coverageReportPath == null) return null

        return DiscoveryConfig(testCommand, coverageCommand, coverageReportPath, coverageReportFormat)
    }

    private data class DiscoveryConfig(
        val testCommand: String?,
        val coverageCommand: String?,
        val coverageReportPath: String?,
        val coverageReportFormat: String?
    )

    private fun detectTestCommand(d: VirtualFile, coverage: Boolean): String? {
        // Use batch/quiet flags to reduce terminal output — verbose Maven/Gradle output
        // overflows the IntelliJ terminal buffer (25 rows) causing JediTerm SEVERE errors
        // and connection drops. The coverage report is read from disk, not terminal output.
        return when {
            d.findChild("build.gradle.kts") != null || d.findChild("build.gradle") != null ->
                if (coverage) "./gradlew test jacocoTestReport --console=plain -q" else "./gradlew test --console=plain -q"
            d.findChild("pom.xml") != null ->
                if (coverage) "mvn test jacoco:report -B -q" else "mvn test -B -q"
            d.findChild("package.json") != null ->
                if (coverage) "npm test -- --coverage" else "npm test"
            d.findChild("pyproject.toml") != null || d.findChild("setup.py") != null ->
                if (coverage) "pytest --cov --cov-report=json --cov-report=term -q" else "pytest -q"
            d.findChild("go.mod") != null ->
                if (coverage) "go test -coverprofile=coverage.out -covermode=atomic ./..." else "go test ./..."
            else -> null
        }
    }

    private fun findCoverageReport(d: VirtualFile): String? {
        // 1. Check well-known static paths first
        val staticCandidates = listOf(
            "build/reports/jacoco/test/jacocoTestReport.xml",
            "build/reports/jacoco/test/html/index.html",
            "target/site/jacoco/jacoco.xml",
            "coverage/lcov.info",
            "coverage/coverage-final.json",
            "coverage/clover.xml",
            "htmlcov/coverage.json",
            "coverage.out",
        )
        for (candidate in staticCandidates) {
            val vf = d.findFileByRelativePath(candidate)
            if (vf != null && !vf.isDirectory) return vf.path
        }

        // 2. Recursive fallback — handles multi-module projects
        val reportFileNames = setOf(
            "jacocoTestReport.xml", "jacoco.xml", "lcov.info",
            "coverage-final.json", "clover.xml", "coverage.out", "index.html"
        )
        val results = mutableListOf<VirtualFile>()
        val rootSlashCount = d.path.count { it == '/' }
        VfsUtilCore.iterateChildrenRecursively(
            d,
            { dir -> dir.path.count { it == '/' } - rootSlashCount < 5 },
            { child ->
                if (!child.isDirectory && child.name in reportFileNames) {
                    // Only include index.html from jacoco directories
                    if (child.name != "index.html" || child.parent?.name == "jacoco") {
                        results.add(child)
                    }
                }
                true
            }
        )
        return results
            .sortedByDescending { it.timeStamp }
            .firstOrNull()
            ?.path
    }
}
