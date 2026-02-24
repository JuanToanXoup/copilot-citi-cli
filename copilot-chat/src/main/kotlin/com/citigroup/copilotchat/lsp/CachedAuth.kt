package com.citigroup.copilotchat.lsp

/**
 * Shared auth state holder that can be populated once and reused across
 * multiple [LspClient] instances managed by [LspClientPool].
 *
 * Thread-safe: all fields are @Volatile.
 */
class CachedAuth {
    @Volatile var binaryPath: String? = null
    @Volatile var lspEnv: Map<String, String> = emptyMap()
    @Volatile var authToken: String? = null
    @Volatile var authUser: String = ""
    @Volatile var appId: String = ""
    @Volatile var featureFlags: Map<String, Any> = emptyMap()
    @Volatile var isServerMcpEnabled: Boolean = false
    @Volatile var proxyUrl: String = ""

    /** True when binary and auth have been resolved at least once. */
    val isResolved: Boolean get() = binaryPath != null && authToken != null
}
