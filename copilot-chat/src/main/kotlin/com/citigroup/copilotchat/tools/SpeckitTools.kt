package com.citigroup.copilotchat.tools

import com.citigroup.copilotchat.tools.BuiltInToolUtils.OUTPUT_LIMIT
import com.citigroup.copilotchat.tools.BuiltInToolUtils.runCommand
import com.citigroup.copilotchat.tools.BuiltInToolUtils.str
import kotlinx.serialization.json.JsonObject
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
}
