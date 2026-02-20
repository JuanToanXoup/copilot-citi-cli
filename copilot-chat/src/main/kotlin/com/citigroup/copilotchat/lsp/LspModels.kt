package com.citigroup.copilotchat.lsp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * JSON-RPC message envelope — covers requests, responses, and notifications.
 */
@Serializable
data class JsonRpcMessage(
    val jsonrpc: String = "2.0",
    val id: Int? = null,
    val method: String? = null,
    val params: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

// ── Initialize ──────────────────────────────────────────────────

@Serializable
data class InitializeParams(
    val processId: Int,
    val capabilities: ClientCapabilities,
    val rootUri: String,
    val workspaceFolders: List<WorkspaceFolder>,
    val clientInfo: ClientInfo,
    val initializationOptions: InitializationOptions,
)

@Serializable
data class ClientCapabilities(
    val textDocumentSync: TextDocumentSyncCapability? = null,
    val workspace: WorkspaceCapability? = null,
)

@Serializable
data class TextDocumentSyncCapability(
    val openClose: Boolean = true,
    val change: Int = 1,
    val save: Boolean = true,
)

@Serializable
data class WorkspaceCapability(
    val workspaceFolders: Boolean = true,
    val didChangeWatchedFiles: DynamicRegistration? = null,
    val fileOperations: FileOperations? = null,
)

@Serializable
data class DynamicRegistration(val dynamicRegistration: Boolean = true)

@Serializable
data class FileOperations(
    val didCreate: Boolean = true,
    val didRename: Boolean = true,
    val didDelete: Boolean = true,
)

@Serializable
data class WorkspaceFolder(val uri: String, val name: String)

@Serializable
data class ClientInfo(val name: String, val version: String)

@Serializable
data class InitializationOptions(
    val editorInfo: EditorInfo,
    val editorPluginInfo: EditorPluginInfo,
    val editorConfiguration: JsonObject = JsonObject(emptyMap()),
    val networkProxy: JsonObject = JsonObject(emptyMap()),
    val githubAppId: String = "",
)

@Serializable
data class EditorInfo(val name: String, val version: String)

@Serializable
data class EditorPluginInfo(val name: String, val version: String)

// ── setEditorInfo ───────────────────────────────────────────────

@Serializable
data class SetEditorInfoParams(
    val editorInfo: EditorInfo,
    val editorPluginInfo: EditorPluginInfo,
    val editorConfiguration: JsonObject = JsonObject(emptyMap()),
    val networkProxy: JsonObject = JsonObject(emptyMap()),
)

// ── Conversation ────────────────────────────────────────────────

@Serializable
data class ConversationCreateParams(
    val workDoneToken: String,
    val turns: List<ConversationTurn>,
    val capabilities: ConversationCapabilities = ConversationCapabilities(),
    val source: String = "panel",
    val chatMode: String? = null,
    val model: String? = null,
    val workspaceFolder: String? = null,
    val workspaceFolders: List<WorkspaceFolder>? = null,
    val needToolCallConfirmation: Boolean? = null,
)

@Serializable
data class ConversationTurn(val request: String)

@Serializable
data class ConversationCapabilities(val allSkills: Boolean = false)

@Serializable
data class ConversationTurnParams(
    val workDoneToken: String,
    val conversationId: String,
    val message: String,
    val source: String = "panel",
    val chatMode: String? = null,
    val model: String? = null,
    val workspaceFolder: String? = null,
    val workspaceFolders: List<WorkspaceFolder>? = null,
    val needToolCallConfirmation: Boolean? = null,
)

// ── Tool registration ───────────────────────────────────────────

@Serializable
data class RegisterToolsParams(val tools: List<ToolSchema>)

@Serializable
data class ToolSchema(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

// ── Progress ────────────────────────────────────────────────────

@Serializable
data class ProgressParams(
    val token: String,
    val value: JsonObject,
)

// ── invokeClientTool ────────────────────────────────────────────

@Serializable
data class InvokeClientToolParams(
    val name: String? = null,
    val toolName: String? = null,
    val input: JsonObject? = null,
    val arguments: JsonObject? = null,
) {
    val resolvedName: String get() = name ?: toolName ?: "unknown"
    val resolvedInput: JsonObject get() = input ?: arguments ?: JsonObject(emptyMap())
}

// ── Server info ─────────────────────────────────────────────────

@Serializable
data class ServerInfo(
    val name: String = "",
    val version: String = "",
)

@Serializable
data class InitializeResult(
    val capabilities: JsonObject? = null,
    val serverInfo: ServerInfo? = null,
)

// ── Auth ────────────────────────────────────────────────────────

@Serializable
data class CheckStatusResult(
    val status: String = "",
    val user: String = "",
)

// ── Models ──────────────────────────────────────────────────────

@Serializable
data class CopilotModel(
    val id: String = "",
    val name: String = "",
    @SerialName("object") val objectType: String = "",
    val version: String = "",
    val capabilities: ModelCapabilities? = null,
)

@Serializable
data class ModelCapabilities(
    val family: String = "",
    val type: String = "",
    val limits: ModelLimits? = null,
)

@Serializable
data class ModelLimits(
    val maxOutputTokens: Int? = null,
    val maxInputTokens: Int? = null,
)
