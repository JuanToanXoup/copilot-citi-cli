package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.io.HttpRequests
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

class CreateIssueTool : LanguageModelToolRegistration {

    private val log = Logger.getInstance(CreateIssueTool::class.java)

    override val toolDefinition = LanguageModelTool(
        "speckit_create_issue",
        "Create an issue on the project's VCS provider (GitHub, Bitbucket, or GitLab). Auto-detects provider from git remote.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "title" to mapOf(
                    "type" to "string",
                    "description" to "Issue title"
                ),
                "body" to mapOf(
                    "type" to "string",
                    "description" to "Issue body (Markdown)"
                ),
                "labels" to mapOf(
                    "type" to "array",
                    "items" to mapOf("type" to "string"),
                    "description" to "Labels to apply (optional)"
                )
            ),
            "required" to listOf("title", "body")
        ),
        null,
        "function",
        "enabled"
    )

    override suspend fun handleInvocation(
        request: ToolInvocationRequest
    ): LanguageModelToolResult {
        val title = request.input?.get("title")?.asString
            ?: return LanguageModelToolResult.Companion.error("Missing required parameter: title")
        val body = request.input?.get("body")?.asString
            ?: return LanguageModelToolResult.Companion.error("Missing required parameter: body")
        val labels = request.input?.get("labels")?.asJsonArray
            ?.map { it.asString } ?: emptyList()

        return try {
            val result = createIssue(title, body, labels)
            LanguageModelToolResult.Companion.success(result)
        } catch (e: Exception) {
            log.warn("speckit_create_issue failed", e)
            LanguageModelToolResult.Companion.error("Error: ${e.message}")
        }
    }

    private fun createIssue(title: String, body: String, labels: List<String>): String {
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
            ?: throw IllegalStateException("no open project found")
        val basePath = project.basePath
            ?: throw IllegalStateException("no project base path")

        val remoteUrl = getGitRemoteUrl(basePath)

        val remote = parseRemoteUrl(remoteUrl)
        val token = resolveToken(remote)
            ?: throw IllegalStateException(
                "no auth token found. Set ${remote.tokenEnvHint} or store in IntelliJ password safe as 'speckit.${remote.provider}.token'"
            )

        return when (remote.provider) {
            "github" -> postGitHub(remote, token, title, body, labels)
            "bitbucket" -> postBitbucket(remote, token, title, body)
            "gitlab" -> postGitLab(remote, token, title, body, labels)
            else -> throw IllegalStateException("unsupported VCS provider '${remote.host}'. Supported: github.com, bitbucket.org, gitlab.com")
        }
    }

    // ── Git remote detection ───────────────────────────────────────────────

    private fun getGitRemoteUrl(basePath: String): String {
        val cmd = GeneralCommandLine("git", "config", "--get", "remote.origin.url")
            .withWorkDirectory(basePath)
        val process = cmd.createProcess()
        val exited = process.waitFor(10, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            throw IllegalStateException("git command timed out")
        }
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.exitValue() != 0 || output.isBlank()) {
            throw IllegalStateException("no git remote configured")
        }
        return output
    }

    // ── Remote URL parsing ──────────────────────────────────────────────────

    data class RemoteInfo(
        val provider: String,
        val host: String,
        val owner: String,
        val repo: String,
        val tokenEnvHint: String
    )

    private fun parseRemoteUrl(url: String): RemoteInfo {
        // SSH: git@github.com:owner/repo.git
        val sshMatch = Regex("""git@([^:]+):(.+)/(.+?)(?:\.git)?$""").find(url)
        if (sshMatch != null) {
            val (host, owner, repo) = sshMatch.destructured
            return buildRemoteInfo(host, owner, repo)
        }

        // HTTPS: https://github.com/owner/repo.git
        val httpsMatch = Regex("""https?://([^/]+)/(.+)/(.+?)(?:\.git)?$""").find(url)
        if (httpsMatch != null) {
            val (host, owner, repo) = httpsMatch.destructured
            return buildRemoteInfo(host, owner, repo)
        }

        throw IllegalStateException("cannot parse git remote URL: $url")
    }

    private fun buildRemoteInfo(host: String, owner: String, repo: String): RemoteInfo {
        val provider = when {
            host.contains("github") -> "github"
            host.contains("bitbucket") -> "bitbucket"
            host.contains("gitlab") -> "gitlab"
            else -> "unknown"
        }
        val envHint = when (provider) {
            "github" -> "GITHUB_TOKEN or GH_TOKEN"
            "bitbucket" -> "BITBUCKET_APP_PASSWORD + BITBUCKET_USERNAME"
            "gitlab" -> "GITLAB_TOKEN"
            else -> "(unsupported provider)"
        }
        return RemoteInfo(provider, host, owner, repo, envHint)
    }

    // ── Auth resolution ─────────────────────────────────────────────────────

    private fun resolveToken(remote: RemoteInfo): String? {
        // 1. IntelliJ PasswordSafe
        val credKey = "speckit.${remote.provider}.token"
        val attrs = CredentialAttributes(generateServiceName("Speckit", credKey))
        val stored = PasswordSafe.instance.getPassword(attrs)
        if (!stored.isNullOrBlank()) return stored

        // 2. Environment variables
        return when (remote.provider) {
            "github" -> System.getenv("GITHUB_TOKEN") ?: System.getenv("GH_TOKEN")
            "bitbucket" -> System.getenv("BITBUCKET_APP_PASSWORD")
            "gitlab" -> System.getenv("GITLAB_TOKEN")
            else -> null
        }
    }

    // ── Provider-specific POST ──────────────────────────────────────────────

    private fun postGitHub(
        remote: RemoteInfo, token: String,
        title: String, body: String, labels: List<String>
    ): String {
        val apiUrl = "https://api.github.com/repos/${remote.owner}/${remote.repo}/issues"
        val payload = JsonObject().apply {
            addProperty("title", title)
            addProperty("body", body)
            if (labels.isNotEmpty()) {
                add("labels", JsonArray().apply { labels.forEach { add(it) } })
            }
        }
        val resp = httpPost(apiUrl, payload.toString(), "token $token")
        val json = JsonParser.parseString(resp).asJsonObject
        val number = json.get("number").asInt
        val url = json.get("html_url").asString
        return "Created issue #$number: $url"
    }

    private fun postBitbucket(
        remote: RemoteInfo, token: String,
        title: String, body: String
    ): String {
        val username = System.getenv("BITBUCKET_USERNAME")
            ?: throw IllegalStateException("BITBUCKET_USERNAME env var required for Bitbucket")
        val apiUrl = "https://api.bitbucket.org/2.0/repositories/${remote.owner}/${remote.repo}/issues"
        val payload = JsonObject().apply {
            addProperty("title", title)
            add("content", JsonObject().apply {
                addProperty("raw", body)
            })
        }
        val basicAuth = java.util.Base64.getEncoder()
            .encodeToString("$username:$token".toByteArray())
        val resp = httpPost(apiUrl, payload.toString(), "Basic $basicAuth")
        val json = JsonParser.parseString(resp).asJsonObject
        val id = json.get("id").asInt
        val url = json.get("links")?.asJsonObject?.get("html")?.asJsonObject?.get("href")?.asString
            ?: "https://bitbucket.org/${remote.owner}/${remote.repo}/issues/$id"
        return "Created issue #$id: $url"
    }

    private fun postGitLab(
        remote: RemoteInfo, token: String,
        title: String, body: String, labels: List<String>
    ): String {
        val encodedPath = java.net.URLEncoder.encode("${remote.owner}/${remote.repo}", "UTF-8")
        val apiUrl = "https://${remote.host}/api/v4/projects/$encodedPath/issues"
        val payload = JsonObject().apply {
            addProperty("title", title)
            addProperty("description", body)
            if (labels.isNotEmpty()) {
                addProperty("labels", labels.joinToString(","))
            }
        }
        val resp = httpPost(apiUrl, payload.toString(), "Bearer $token")
        val json = JsonParser.parseString(resp).asJsonObject
        val iid = json.get("iid").asInt
        val url = json.get("web_url").asString
        return "Created issue #$iid: $url"
    }

    // ── HTTP helper ─────────────────────────────────────────────────────────

    private fun httpPost(url: String, jsonBody: String, authHeader: String): String {
        return HttpRequests.post(url, "application/json")
            .tuner { conn ->
                conn.setRequestProperty("Authorization", authHeader)
                conn.setRequestProperty("Accept", "application/json")
            }
            .connect { request ->
                request.write(jsonBody)
                val conn = request.connection as HttpURLConnection
                val code = conn.responseCode
                val responseBody = if (code in 200..299) {
                    request.readString()
                } else {
                    conn.errorStream?.bufferedReader()?.readText() ?: ""
                }
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code: $responseBody")
                }
                responseBody
            }
    }
}
