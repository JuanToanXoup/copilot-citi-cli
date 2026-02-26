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

class SpeckitParseCoverage : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_parse_coverage",
        "Find and read the coverage report for a project or service. Auto-detects report format (JaCoCo XML, lcov, coverage.json, coverage.out). Returns raw report content for analysis.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string", "description" to "Service directory — absolute path or relative to project root (default: '.')"),
                "report_path" to mapOf("type" to "string", "description" to "Override: explicit path to coverage report file")
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
        val explicitReport = request.input?.get("report_path")?.asString
        val workDir = when {
            path == "." -> basePath
            path.startsWith("/") -> path
            else -> "$basePath/$path"
        }

        val lfs = LocalFileSystem.getInstance()
        val d = lfs.findFileByIoFile(File(workDir))
        if (d == null || !d.isDirectory) {
            return LanguageModelToolResult.Companion.error(
                "Directory not found: $workDir (path='$path', basePath='$basePath'). " +
                "If this is a file path, use the report_path parameter instead."
            )
        }

        val reportFile = if (explicitReport != null) {
            val reportPath = if (explicitReport.startsWith("/")) explicitReport else "$workDir/$explicitReport"
            val f = lfs.findFileByIoFile(File(reportPath))
            if (f == null || f.isDirectory) return LanguageModelToolResult.Companion.error(
                "Report not found: $reportPath (report_path='$explicitReport', workDir='$workDir')"
            )
            f
        } else {
            // Try discovery memory first for the known report path
            findFromDiscoveryMemory(workDir, basePath)
                ?: findCoverageReport(d)
                ?: return LanguageModelToolResult.Companion.error(
                    "No coverage report found in '$workDir'. Run speckit_run_tests with coverage=true first.\n" +
                    "Checked discovery memory, static paths, and recursive search.\n" +
                    "Tip: use report_path parameter to specify the exact file location."
                )
        }

        val content = VfsUtilCore.loadText(reportFile)
        val format = detectFormat(reportFile.name)

        return LanguageModelToolResult.Companion.success(
            "Coverage report: ${reportFile.path}\nFormat: $format\nSize: ${content.length} chars\n\n$content"
        )
    }

    private fun findFromDiscoveryMemory(workDir: String, basePath: String): VirtualFile? {
        val lfs = LocalFileSystem.getInstance()
        val candidates = listOf(
            lfs.findFileByIoFile(File(workDir, ".specify/memory/discovery-report.md")),
            lfs.findFileByIoFile(File(basePath, ".specify/memory/discovery-report.md"))
        )
        val memoryFile = candidates.firstOrNull { it != null && !it.isDirectory } ?: return null
        val content = VfsUtilCore.loadText(memoryFile)

        // Extract coverage report path from the discovery report
        val regex = Regex("""\*\*Coverage report path\*\*:\s*\[?(.+?)\]?\s*$""", RegexOption.MULTILINE)
        val match = regex.find(content) ?: return null
        val reportPath = match.groupValues[1].trim()
        if (reportPath.startsWith("e.g.,") || reportPath == "UNKNOWN" || reportPath.isEmpty()) return null

        val fullPath = if (reportPath.startsWith("/")) reportPath else "$workDir/$reportPath"
        val f = lfs.findFileByIoFile(File(fullPath))
        return if (f != null && !f.isDirectory) f else null
    }

    private fun findCoverageReport(d: VirtualFile): VirtualFile? {
        // 1. Check well-known static paths first
        val staticCandidates = listOf(
            "build/reports/jacoco/test/jacocoTestReport.xml",
            "target/site/jacoco/jacoco.xml",
            "coverage/lcov.info",
            "coverage/coverage-final.json",
            "coverage/clover.xml",
            "htmlcov/coverage.json",
            "coverage.out",
        )
        for (candidate in staticCandidates) {
            val vf = d.findFileByRelativePath(candidate)
            if (vf != null && !vf.isDirectory) return vf
        }

        // 2. Recursive fallback — handles multi-module projects, non-standard paths
        val reportFileNames = setOf(
            "jacocoTestReport.xml", "jacoco.xml", "lcov.info",
            "coverage-final.json", "clover.xml", "coverage.out"
        )
        val results = mutableListOf<VirtualFile>()
        val rootSlashCount = d.path.count { it == '/' }
        VfsUtilCore.iterateChildrenRecursively(
            d,
            { dir -> dir.path.count { it == '/' } - rootSlashCount < 5 },
            { child ->
                if (!child.isDirectory && child.name in reportFileNames) {
                    results.add(child)
                }
                true
            }
        )
        return results
            .sortedByDescending { it.timeStamp }  // most recent first
            .firstOrNull()
    }

    private fun detectFormat(fileName: String): String = when {
        fileName.endsWith(".xml") && fileName.contains("jacoco", ignoreCase = true) -> "JaCoCo XML"
        fileName.endsWith(".xml") && fileName.contains("clover", ignoreCase = true) -> "Clover XML"
        fileName.endsWith(".xml") -> "XML (unknown format)"
        fileName == "lcov.info" -> "LCOV"
        fileName.endsWith(".json") -> "JSON"
        fileName == "coverage.out" -> "Go coverage profile"
        else -> "Unknown"
    }
}
