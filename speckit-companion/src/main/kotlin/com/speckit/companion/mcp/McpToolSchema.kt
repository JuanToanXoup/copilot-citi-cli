package com.speckit.companion.mcp

data class McpToolSchema(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any>
)
