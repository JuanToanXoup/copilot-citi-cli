package com.citigroup.copilotchat.tools

import com.citigroup.copilotchat.tools.BuiltInToolUtils.OUTPUT_LIMIT
import com.citigroup.copilotchat.tools.BuiltInToolUtils.runCommand
import com.citigroup.copilotchat.tools.BuiltInToolUtils.str
import kotlinx.serialization.json.*
import java.io.File

/**
 * In-process tools for the SpecKit spec-driven development pipeline.
 *
 * Exposes each SpecKit stage as a callable tool so the LLM can drive
 * the full specify → clarify → plan → tasks → analyze → checklist → implement
 * workflow without relying on an external MCP server.
 *
 * Each tool runs the corresponding `.specify/scripts/bash/` helper and
 * returns its output. The LLM then uses the output to proceed with
 * the pipeline (reading/writing spec artifacts, etc.).
 */
object SpeckitTools : ToolGroup {

    override val schemas: List<String> = listOf(
        // speckit_setup_feature: creates branch + spec scaffold
        """{"name":"speckit_setup_feature","description":"Initialize a new SpecKit feature: creates a numbered branch, spec directory, and empty spec.md scaffold. Returns JSON with BRANCH_NAME, SPEC_FILE, and FEATURE_DIR paths. Run this before writing a spec.","inputSchema":{"type":"object","properties":{"description":{"type":"string","description":"The feature description provided by the user."},"number":{"type":"integer","description":"Feature number to use (next available). Omit to auto-detect."},"short_name":{"type":"string","description":"Short kebab-case name for the branch (2-4 words, e.g. 'user-auth')."}},"required":["description","short_name"]}}""",

        // speckit_setup_plan: prepares plan directory + copies template
        """{"name":"speckit_setup_plan","description":"Prepare the plan phase for the current SpecKit feature: copies the plan template into the feature directory and returns JSON with FEATURE_SPEC, IMPL_PLAN, SPECS_DIR, and BRANCH paths.","inputSchema":{"type":"object","properties":{},"required":[]}}""",

        // speckit_check_prerequisites: validates feature artifacts exist
        """{"name":"speckit_check_prerequisites","description":"Check that required SpecKit artifacts exist for the current feature branch. Returns JSON with FEATURE_DIR and AVAILABLE_DOCS list. Use flags to require/include tasks.md.","inputSchema":{"type":"object","properties":{"require_tasks":{"type":"boolean","description":"If true, require tasks.md to exist (for implementation phase).","default":false},"include_tasks":{"type":"boolean","description":"If true, include tasks content in output.","default":false},"paths_only":{"type":"boolean","description":"If true, only output path variables without validation.","default":false}},"required":[]}}""",

        // speckit_update_agent_context: syncs agent context files
        """{"name":"speckit_update_agent_context","description":"Update agent-specific context files after plan changes. Detects the AI agent in use and updates the appropriate context file with new technology from the current plan.","inputSchema":{"type":"object","properties":{"agent_type":{"type":"string","description":"Agent type identifier (e.g. 'copilot').","default":"copilot"}},"required":[]}}""",

        // speckit_read_template: read a SpecKit template file
        """{"name":"speckit_read_template","description":"Read a SpecKit template file from .specify/templates/. Available templates: spec-template.md, plan-template.md, tasks-template.md, checklist-template.md, constitution-template.md.","inputSchema":{"type":"object","properties":{"template":{"type":"string","description":"Template filename (e.g. 'spec-template.md', 'plan-template.md')."}},"required":["template"]}}""",

        // speckit_read_constitution: read the project constitution
        """{"name":"speckit_read_constitution","description":"Read the project constitution from .specify/memory/constitution.md. Returns the constitution content or an error if it doesn't exist yet.","inputSchema":{"type":"object","properties":{},"required":[]}}""",

        // speckit_list_features: list existing feature spec directories
        """{"name":"speckit_list_features","description":"List existing SpecKit feature directories under specs/. Returns the directory names and their contents.","inputSchema":{"type":"object","properties":{},"required":[]}}""",

        // speckit_read_artifact: read a specific artifact from the current feature
        """{"name":"speckit_read_artifact","description":"Read a SpecKit artifact file from the current feature directory. Artifacts include: spec.md, plan.md, tasks.md, research.md, data-model.md, quickstart.md, and files under contracts/ and checklists/.","inputSchema":{"type":"object","properties":{"artifact":{"type":"string","description":"Artifact filename or path relative to feature dir (e.g. 'spec.md', 'plan.md', 'checklists/requirements.md', 'contracts/api.md')."}},"required":["artifact"]}}""",

        // speckit_write_artifact: write content to a feature artifact
        """{"name":"speckit_write_artifact","description":"Write content to a SpecKit artifact file in the current feature directory. Creates parent directories as needed.","inputSchema":{"type":"object","properties":{"artifact":{"type":"string","description":"Artifact filename or path relative to feature dir (e.g. 'spec.md', 'plan.md', 'checklists/ux.md')."},"content":{"type":"string","description":"The content to write to the artifact file."}},"required":["artifact","content"]}}""",

        // speckit_get_feature_dir: resolve the current feature directory
        """{"name":"speckit_get_feature_dir","description":"Resolve the current SpecKit feature directory from the active git branch. Returns the absolute path to the feature's specs/ directory, or an error if not on a feature branch.","inputSchema":{"type":"object","properties":{},"required":[]}}""",

        // speckit_analyze_project: analyze project for coverage planning
        """{"name":"speckit_analyze_project","description":"Analyze the current project to detect build system, test framework, source layout, and existing test patterns. Returns structured JSON for coverage planning.","inputSchema":{"type":"object","properties":{},"required":[]}}""",

        // speckit_run_tests: run tests with coverage
        """{"name":"speckit_run_tests","description":"Run the project's test suite with coverage enabled. Auto-detects build system and coverage tool. Returns JSON with pass/fail, coverage percentage, and per-package breakdown. Timeout: 5 minutes.","inputSchema":{"type":"object","properties":{"package_filter":{"type":"string","description":"Optional: only run tests for this package/path. If omitted, runs all tests."}},"required":[]}}""",
    )

    override val executors: Map<String, (JsonObject, String) -> String> = mapOf(
        "speckit_setup_feature" to ::executeSetupFeature,
        "speckit_setup_plan" to ::executeSetupPlan,
        "speckit_check_prerequisites" to ::executeCheckPrerequisites,
        "speckit_update_agent_context" to ::executeUpdateAgentContext,
        "speckit_read_template" to ::executeReadTemplate,
        "speckit_read_constitution" to ::executeReadConstitution,
        "speckit_list_features" to ::executeListFeatures,
        "speckit_read_artifact" to ::executeReadArtifact,
        "speckit_write_artifact" to ::executeWriteArtifact,
        "speckit_get_feature_dir" to ::executeGetFeatureDir,
        "speckit_analyze_project" to ::executeAnalyzeProject,
        "speckit_run_tests" to ::executeRunTests,
    )

    // -- Tool implementations --

    private fun executeSetupFeature(input: JsonObject, ws: String): String {
        val description = input.str("description") ?: return "Error: description is required"
        val shortName = input.str("short_name") ?: return "Error: short_name is required"
        val number = input["number"]?.toString()?.toIntOrNull()

        val scriptPath = File(ws, ".specify/scripts/bash/create-new-feature.sh")
        if (!scriptPath.exists()) return "Error: SpecKit not initialized — .specify/scripts/bash/create-new-feature.sh not found"

        val cmd = mutableListOf("bash", scriptPath.absolutePath, "--json")
        if (number != null) {
            cmd.addAll(listOf("--number", number.toString()))
        }
        cmd.addAll(listOf("--short-name", shortName, description))

        return runCommand(cmd, workingDir = ws, timeout = 30)
    }

    private fun executeSetupPlan(input: JsonObject, ws: String): String {
        val scriptPath = File(ws, ".specify/scripts/bash/setup-plan.sh")
        if (!scriptPath.exists()) return "Error: SpecKit not initialized — .specify/scripts/bash/setup-plan.sh not found"

        return runCommand(listOf("bash", scriptPath.absolutePath, "--json"), workingDir = ws, timeout = 30)
    }

    private fun executeCheckPrerequisites(input: JsonObject, ws: String): String {
        val scriptPath = File(ws, ".specify/scripts/bash/check-prerequisites.sh")
        if (!scriptPath.exists()) return "Error: SpecKit not initialized — .specify/scripts/bash/check-prerequisites.sh not found"

        val cmd = mutableListOf("bash", scriptPath.absolutePath, "--json")
        if (input.str("require_tasks") == "true" || input["require_tasks"]?.toString() == "true") {
            cmd.add("--require-tasks")
        }
        if (input.str("include_tasks") == "true" || input["include_tasks"]?.toString() == "true") {
            cmd.add("--include-tasks")
        }
        if (input.str("paths_only") == "true" || input["paths_only"]?.toString() == "true") {
            cmd.add("--paths-only")
        }

        return runCommand(cmd, workingDir = ws, timeout = 30)
    }

    private fun executeUpdateAgentContext(input: JsonObject, ws: String): String {
        val agentType = input.str("agent_type") ?: "copilot"
        val scriptPath = File(ws, ".specify/scripts/bash/update-agent-context.sh")
        if (!scriptPath.exists()) return "Error: SpecKit not initialized — .specify/scripts/bash/update-agent-context.sh not found"

        return runCommand(listOf("bash", scriptPath.absolutePath, agentType), workingDir = ws, timeout = 30)
    }

    private fun executeReadTemplate(input: JsonObject, ws: String): String {
        val template = input.str("template") ?: return "Error: template is required"

        // Prevent path traversal
        if (template.contains("..")) return "Error: path traversal not allowed"

        val templateFile = File(ws, ".specify/templates/$template")
        if (!templateFile.exists()) {
            val available = File(ws, ".specify/templates").listFiles()
                ?.filter { it.isFile && it.name.endsWith(".md") }
                ?.joinToString(", ") { it.name }
                ?: "none found"
            return "Error: template '$template' not found. Available: $available"
        }

        return templateFile.readText().take(OUTPUT_LIMIT)
    }

    private fun executeReadConstitution(input: JsonObject, ws: String): String {
        val constitutionFile = File(ws, ".specify/memory/constitution.md")
        if (!constitutionFile.exists()) {
            return "Error: constitution not found at .specify/memory/constitution.md. " +
                "Run speckit_read_template with template='constitution-template.md' to see the template, " +
                "then create the constitution."
        }

        return constitutionFile.readText().take(OUTPUT_LIMIT)
    }

    private fun executeListFeatures(input: JsonObject, ws: String): String {
        val specsDir = File(ws, "specs")
        if (!specsDir.isDirectory) return "No specs/ directory found. No features have been created yet."

        val features = specsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?: return "specs/ directory is empty."

        if (features.isEmpty()) return "specs/ directory is empty."

        return buildString {
            appendLine("Feature directories:")
            for (dir in features) {
                appendLine("  ${dir.name}/")
                dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                    val marker = if (f.isDirectory) "/" else ""
                    appendLine("    ${f.name}$marker")
                }
            }
        }.take(OUTPUT_LIMIT)
    }

    private fun executeReadArtifact(input: JsonObject, ws: String): String {
        val artifact = input.str("artifact") ?: return "Error: artifact is required"

        // Prevent path traversal
        if (artifact.contains("..")) return "Error: path traversal not allowed"

        // Resolve feature dir from current branch
        val featureDir = resolveFeatureDir(ws) ?: return "Error: not on a SpecKit feature branch. " +
            "Expected branch name matching pattern NNN-feature-name."

        val artifactFile = File(featureDir, artifact)
        if (!artifactFile.exists()) {
            val available = listAvailableArtifacts(featureDir)
            return "Error: artifact '$artifact' not found in ${featureDir.name}/. Available: $available"
        }

        return artifactFile.readText().take(OUTPUT_LIMIT)
    }

    private fun executeWriteArtifact(input: JsonObject, ws: String): String {
        val artifact = input.str("artifact") ?: return "Error: artifact is required"
        val content = input.str("content") ?: return "Error: content is required"

        // Prevent path traversal
        if (artifact.contains("..")) return "Error: path traversal not allowed"

        // Resolve feature dir from current branch
        val featureDir = resolveFeatureDir(ws) ?: return "Error: not on a SpecKit feature branch. " +
            "Expected branch name matching pattern NNN-feature-name."

        val artifactFile = File(featureDir, artifact)
        artifactFile.parentFile?.mkdirs()
        artifactFile.writeText(content)

        return "Written ${content.length} chars to ${featureDir.name}/$artifact"
    }

    private fun executeGetFeatureDir(input: JsonObject, ws: String): String {
        val featureDir = resolveFeatureDir(ws)
            ?: return "Error: not on a SpecKit feature branch. Expected branch name matching pattern NNN-feature-name."

        return buildString {
            appendLine("Feature directory: ${featureDir.absolutePath}")
            appendLine("Available artifacts: ${listAvailableArtifacts(featureDir)}")
        }
    }

    // -- Helpers --

    /**
     * Resolve the feature directory by reading the current git branch name
     * and looking for a matching specs/NNN-feature-name/ directory.
     */
    private fun resolveFeatureDir(ws: String): File? {
        val branchOutput = runCommand(listOf("git", "rev-parse", "--abbrev-ref", "HEAD"), workingDir = ws, timeout = 5)
        val branch = branchOutput.trim()
        if (branch.isBlank() || branch == "HEAD") return null

        // Branch format: NNN-feature-name or feature/NNN-feature-name
        val branchName = branch.substringAfterLast("/")

        // Try direct match first
        val specsDir = File(ws, "specs")
        if (!specsDir.isDirectory) return null

        val direct = File(specsDir, branchName)
        if (direct.isDirectory) return direct

        // Try matching by pattern: look for specs dirs starting with same number
        val match = Regex("^(\\d+)-(.+)$").find(branchName)
        if (match != null) {
            val number = match.groupValues[1]
            val found = specsDir.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("$number-") }
                ?.firstOrNull()
            if (found != null) return found
        }

        return null
    }

    private fun listAvailableArtifacts(featureDir: File): String {
        val artifacts = mutableListOf<String>()
        featureDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (f.isFile) {
                artifacts.add(f.name)
            } else if (f.isDirectory) {
                f.listFiles()?.forEach { sub ->
                    artifacts.add("${f.name}/${sub.name}")
                }
            }
        }
        return if (artifacts.isEmpty()) "none" else artifacts.joinToString(", ")
    }

    // -- Coverage analysis tools --

    private fun executeAnalyzeProject(input: JsonObject, ws: String): String {
        val buildSystem = detectBuildSystem(ws)
        val language = detectLanguage(ws, buildSystem)
        val testFramework = detectTestFramework(ws, buildSystem)
        val (coverageTool, configured) = detectCoverageTool(ws, buildSystem)
        val (sourceRoot, testRoot) = detectSourceLayout(ws, buildSystem)
        val exts = sourceExtensions(language)
        val sourcePackages = listPackages(File(ws, sourceRoot), exts)
        val testPackages = listPackages(File(ws, testRoot), exts)
        val untestedPackages = sourcePackages - testPackages.toSet()

        return buildJsonObject {
            put("build_system", buildSystem)
            put("language", language)
            put("test_framework", testFramework)
            put("coverage_tool", coverageTool)
            put("coverage_tool_configured", configured)
            put("source_root", sourceRoot)
            put("test_root", testRoot)
            putJsonArray("source_packages") { sourcePackages.forEach { add(it) } }
            putJsonArray("test_packages") { testPackages.forEach { add(it) } }
            putJsonArray("untested_packages") { untestedPackages.forEach { add(it) } }
            put("source_file_count", countFiles(File(ws, sourceRoot), exts))
            put("test_file_count", countFiles(File(ws, testRoot), exts))
        }.toString()
    }

    private fun executeRunTests(input: JsonObject, ws: String): String {
        val packageFilter = input.str("package_filter")
        val buildSystem = detectBuildSystem(ws)
        val cmd = buildTestCommand(buildSystem, packageFilter, ws)
            ?: return buildJsonObject {
                put("status", "error")
                put("error", "Unsupported or unrecognized build system: $buildSystem")
                put("coverage_percent", -1)
            }.toString()

        // Run tests with 5-minute timeout
        val output = runCommand(cmd, workingDir = ws, timeout = 300)

        // Parse exit code appended by the shell wrapper
        val exitCodeMatch = Regex("EXIT_CODE=(\\d+)").find(output)
        val exitCode = exitCodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val testOutput = output.substringBefore("EXIT_CODE=").trim()

        val status = if (exitCode == 0) "pass" else "fail"

        // Parse coverage report from disk
        val coverage = parseCoverageReport(buildSystem, ws)
        val coveragePercent = coverage["percent"] as? Double ?: -1.0
        @Suppress("UNCHECKED_CAST")
        val byPackage = coverage["by_package"] as? Map<String, Double> ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val uncoveredPkgs = coverage["uncovered_packages"] as? List<String> ?: emptyList()

        return buildJsonObject {
            put("status", status)
            put("exit_code", exitCode)
            put("coverage_percent", coveragePercent)
            putJsonObject("coverage_by_package") {
                byPackage.forEach { (pkg, pct) -> put(pkg, pct) }
            }
            putJsonArray("uncovered_packages") { uncoveredPkgs.forEach { add(it) } }
            put("output_tail", testOutput.takeLast(500))
        }.toString()
    }

    // -- Coverage detection helpers --

    private fun detectBuildSystem(ws: String): String = when {
        File(ws, "build.gradle.kts").exists() || File(ws, "build.gradle").exists() -> "gradle"
        File(ws, "pom.xml").exists() -> "maven"
        File(ws, "package.json").exists() -> "npm"
        File(ws, "pyproject.toml").exists() || File(ws, "setup.py").exists() -> "python"
        File(ws, "Cargo.toml").exists() -> "rust"
        File(ws, "go.mod").exists() -> "go"
        else -> "unknown"
    }

    private fun detectLanguage(ws: String, buildSystem: String): String = when (buildSystem) {
        "gradle" -> if (File(ws, "src/main/kotlin").isDirectory || File(ws, "build.gradle.kts").exists()) "kotlin" else "java"
        "maven" -> if (File(ws, "src/main/kotlin").isDirectory) "kotlin" else "java"
        "npm" -> if (File(ws, "tsconfig.json").exists()) "typescript" else "javascript"
        "python" -> "python"
        "rust" -> "rust"
        "go" -> "go"
        else -> "unknown"
    }

    private fun detectTestFramework(ws: String, buildSystem: String): String {
        val content = when (buildSystem) {
            "gradle" -> (File(ws, "build.gradle.kts").takeIf { it.exists() }
                ?: File(ws, "build.gradle").takeIf { it.exists() })?.readText() ?: ""
            "maven" -> File(ws, "pom.xml").takeIf { it.exists() }?.readText() ?: ""
            "npm" -> File(ws, "package.json").takeIf { it.exists() }?.readText() ?: ""
            "python" -> File(ws, "pyproject.toml").takeIf { it.exists() }?.readText() ?: ""
            else -> ""
        }
        return when (buildSystem) {
            "gradle", "maven" -> when {
                content.contains("junit-jupiter") || content.contains("org.junit.jupiter") -> "junit5"
                content.contains("spock") || content.contains("org.spockframework") -> "spock"
                content.contains("testng") || content.contains("org.testng") -> "testng"
                content.contains("kotest") -> "kotest"
                content.contains("junit") -> "junit4"
                else -> "junit5"
            }
            "npm" -> when {
                content.contains("vitest") -> "vitest"
                content.contains("jest") -> "jest"
                content.contains("mocha") -> "mocha"
                else -> "jest"
            }
            "python" -> if (content.contains("pytest")) "pytest" else "unittest"
            "go" -> "go-test"
            "rust" -> "cargo-test"
            else -> "unknown"
        }
    }

    private fun detectCoverageTool(ws: String, buildSystem: String): Pair<String, Boolean> {
        val content = when (buildSystem) {
            "gradle" -> (File(ws, "build.gradle.kts").takeIf { it.exists() }
                ?: File(ws, "build.gradle").takeIf { it.exists() })?.readText() ?: ""
            "maven" -> File(ws, "pom.xml").takeIf { it.exists() }?.readText() ?: ""
            "npm" -> File(ws, "package.json").takeIf { it.exists() }?.readText() ?: ""
            "python" -> (File(ws, "pyproject.toml").takeIf { it.exists() }?.readText() ?: "") +
                (File(ws, "requirements.txt").takeIf { it.exists() }?.readText() ?: "")
            else -> ""
        }
        return when (buildSystem) {
            "gradle", "maven" -> "jacoco" to content.contains("jacoco")
            "npm" -> when {
                content.contains("c8") -> "c8" to true
                content.contains("istanbul") || content.contains("nyc") -> "istanbul" to true
                content.contains("jest") -> "jest-coverage" to true
                content.contains("vitest") -> "vitest-coverage" to true
                else -> "unknown" to false
            }
            "python" -> "pytest-cov" to content.contains("pytest-cov")
            "go" -> "go-cover" to true
            "rust" -> "tarpaulin" to false
            else -> "unknown" to false
        }
    }

    private fun detectSourceLayout(ws: String, buildSystem: String): Pair<String, String> = when (buildSystem) {
        "gradle", "maven" -> {
            val src = if (File(ws, "src/main/kotlin").isDirectory) "src/main/kotlin" else "src/main/java"
            val test = if (File(ws, "src/test/kotlin").isDirectory) "src/test/kotlin" else "src/test/java"
            src to test
        }
        "npm" -> {
            val src = listOf("src", "lib", "app").firstOrNull { File(ws, it).isDirectory } ?: "src"
            val test = listOf("__tests__", "test", "tests").firstOrNull { File(ws, it).isDirectory } ?: "test"
            src to test
        }
        "python" -> {
            val src = listOf("src", "lib").firstOrNull { File(ws, it).isDirectory } ?: "."
            val test = listOf("tests", "test").firstOrNull { File(ws, it).isDirectory } ?: "tests"
            src to test
        }
        "go", "rust" -> "." to "."
        else -> "src" to "test"
    }

    private fun sourceExtensions(language: String): Set<String> = when (language) {
        "kotlin" -> setOf("kt", "kts")
        "java" -> setOf("java")
        "typescript" -> setOf("ts", "tsx")
        "javascript" -> setOf("js", "jsx")
        "python" -> setOf("py")
        "go" -> setOf("go")
        "rust" -> setOf("rs")
        else -> setOf("kt", "java", "ts", "js", "py", "go", "rs")
    }

    private fun listPackages(root: File, extensions: Set<String>): List<String> {
        if (!root.isDirectory) return emptyList()
        val packages = mutableSetOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension in extensions }
            .forEach { file ->
                val rel = file.parentFile.toRelativeString(root)
                if (rel.isNotBlank()) {
                    packages.add(rel.replace(File.separatorChar, '.'))
                }
            }
        return packages.sorted()
    }

    private fun countFiles(root: File, extensions: Set<String>): Int {
        if (!root.isDirectory) return 0
        return root.walkTopDown().count { it.isFile && it.extension in extensions }
    }

    private fun buildTestCommand(buildSystem: String, packageFilter: String?, ws: String): List<String>? {
        return when (buildSystem) {
            "gradle" -> {
                val wrapper = if (File(ws, "gradlew").exists()) "./gradlew" else "gradle"
                val filter = if (packageFilter != null) " --tests \"${packageFilter}.*\"" else ""
                listOf("bash", "-c", "$wrapper test jacocoTestReport --no-daemon -q$filter 2>&1; echo EXIT_CODE=\$?")
            }
            "maven" -> {
                val filter = if (packageFilter != null) " -Dtest=\"${packageFilter}.*\"" else ""
                listOf("bash", "-c", "mvn test$filter -q 2>&1; echo EXIT_CODE=\$?")
            }
            "npm" -> {
                val filter = if (packageFilter != null) " $packageFilter" else ""
                listOf("bash", "-c", "npx jest --coverage$filter 2>&1; echo EXIT_CODE=\$?")
            }
            "python" -> {
                val filter = if (packageFilter != null) " $packageFilter" else ""
                listOf("bash", "-c", "python -m pytest --cov --cov-report=json -q$filter 2>&1; echo EXIT_CODE=\$?")
            }
            "go" -> {
                val pkg = packageFilter ?: "./..."
                listOf("bash", "-c", "go test -coverprofile=coverage.out $pkg 2>&1; echo EXIT_CODE=\$?")
            }
            else -> null
        }
    }

    // -- Coverage report parsers --

    private fun parseCoverageReport(buildSystem: String, ws: String): Map<String, Any> = when (buildSystem) {
        "gradle", "maven" -> parseJacocoCoverage(ws)
        "npm" -> parseJestCoverage(ws)
        "python" -> parsePytestCoverage(ws)
        else -> mapOf("percent" to -1.0)
    }

    private fun parseJacocoCoverage(ws: String): Map<String, Any> {
        val reportPaths = listOf(
            "build/reports/jacoco/test/jacocoTestReport.xml",
            "build/reports/jacoco/jacocoTestReport.xml",
            "target/site/jacoco/jacoco.xml",
        )
        val reportFile = reportPaths.map { File(ws, it) }.firstOrNull { it.exists() }
            ?: return mapOf("percent" to -1.0, "error" to "JaCoCo report not found")

        val content = reportFile.readText()
        val counterRegex = Regex("""<counter type="LINE" missed="(\d+)" covered="(\d+)"/>""")
        val allMatches = counterRegex.findAll(content).toList()
        if (allMatches.isEmpty()) return mapOf("percent" to -1.0, "error" to "No LINE counters in report")

        // Last match is the report-level total
        val last = allMatches.last()
        val missed = last.groupValues[1].toDouble()
        val covered = last.groupValues[2].toDouble()
        val total = missed + covered
        val percent = if (total > 0) "%.1f".format(covered / total * 100).toDouble() else 0.0

        // Per-package breakdown
        val pkgRegex = Regex("""<package name="([^"]+)">(.*?)</package>""", RegexOption.DOT_MATCHES_ALL)
        val byPackage = mutableMapOf<String, Double>()
        val uncovered = mutableListOf<String>()
        for (m in pkgRegex.findAll(content)) {
            val pkgName = m.groupValues[1].replace('/', '.')
            val pkgContent = m.groupValues[2]
            val pkgLine = counterRegex.findAll(pkgContent).lastOrNull() ?: continue
            val pm = pkgLine.groupValues[1].toDouble()
            val pc = pkgLine.groupValues[2].toDouble()
            val pt = pm + pc
            val pp = if (pt > 0) "%.1f".format(pc / pt * 100).toDouble() else 0.0
            byPackage[pkgName] = pp
            if (pp < 50.0) uncovered.add(pkgName)
        }

        return mapOf("percent" to percent, "by_package" to byPackage, "uncovered_packages" to uncovered)
    }

    private fun parseJestCoverage(ws: String): Map<String, Any> {
        val reportFile = File(ws, "coverage/coverage-summary.json")
        if (!reportFile.exists()) return mapOf("percent" to -1.0, "error" to "Jest coverage report not found")
        return try {
            val j = Json { ignoreUnknownKeys = true }
            val root = j.parseToJsonElement(reportFile.readText()).jsonObject
            val pct = root["total"]?.jsonObject?.get("lines")?.jsonObject
                ?.get("pct")?.jsonPrimitive?.doubleOrNull ?: -1.0
            mapOf("percent" to pct)
        } catch (e: Exception) {
            mapOf("percent" to -1.0, "error" to "Failed to parse Jest coverage: ${e.message}")
        }
    }

    private fun parsePytestCoverage(ws: String): Map<String, Any> {
        val reportFile = File(ws, "coverage.json")
        if (!reportFile.exists()) return mapOf("percent" to -1.0, "error" to "pytest-cov report not found")
        return try {
            val j = Json { ignoreUnknownKeys = true }
            val root = j.parseToJsonElement(reportFile.readText()).jsonObject
            val pct = root["totals"]?.jsonObject?.get("percent_covered")
                ?.jsonPrimitive?.doubleOrNull ?: -1.0
            mapOf("percent" to pct)
        } catch (e: Exception) {
            mapOf("percent" to -1.0, "error" to "Failed to parse pytest-cov: ${e.message}")
        }
    }
}
