package com.speckit.plugin.service

import com.speckit.plugin.model.TableRow

/**
 * Pure parsing and serialization logic for discovery.md files.
 * No Swing dependencies.
 */
object DiscoveryService {

    /** Strip YAML front-matter (---...---) from template content. */
    fun extractBody(content: String): String {
        val match = Regex("^---\\s*\\n.*?\\n---\\s*\\n?", RegexOption.DOT_MATCHES_ALL).find(content) ?: return content
        return content.substring(match.range.last + 1)
    }

    /** Parse `## Category` headings and `- Attribute = Answer` lines into rows. */
    fun parseDiscovery(content: String): List<TableRow> {
        val rows = mutableListOf<TableRow>()
        var currentCategory = ""
        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue

            if (trimmed.startsWith("## ")) {
                currentCategory = trimmed.removePrefix("## ").trim()
                continue
            }

            if (trimmed.startsWith("- ") && currentCategory.isNotEmpty()) {
                val eqIdx = trimmed.indexOf('=')
                if (eqIdx > 0) {
                    val attribute = trimmed.substring(2, eqIdx).trim()
                    val answer = trimmed.substring(eqIdx + 1).trim()
                    rows.add(TableRow(currentCategory, attribute, answer))
                }
            }
        }
        return rows
    }

    /** Serialize category data back to markdown format. */
    fun serializeToMarkdown(categories: List<Pair<String, List<Pair<String, String>>>>): String {
        val sb = StringBuilder()
        var first = true
        for ((category, attributes) in categories) {
            if (!first) sb.appendLine()
            first = false
            sb.appendLine("## $category")
            for ((attribute, answer) in attributes) {
                sb.appendLine("- $attribute = $answer")
            }
        }
        return sb.toString()
    }
}
