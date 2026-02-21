package com.citigroup.copilotchat.tools

/**
 * Local library documentation index.
 *
 * Maps library names/aliases to bundled markdown docs loaded from classpath resources.
 * Used by resolve_library_id and get_library_docs tools.
 *
 * Ported from cli/src/copilot_cli/library_docs/__init__.py
 */
object LibraryDocs {

    data class LibraryMeta(
        val id: String,
        val title: String,
        val description: String,
        val aliases: List<String>,
        val resourceDir: String,
    )

    private val LIBRARIES: List<LibraryMeta> = listOf(
        LibraryMeta(
            id = "playwright",
            title = "Playwright",
            description = "Browser automation library for end-to-end testing (Java, Python, Node.js, .NET)",
            aliases = listOf("playwright", "playwright-java", "microsoft/playwright", "pw"),
            resourceDir = "playwright",
        ),
        LibraryMeta(
            id = "selenium",
            title = "Selenium WebDriver",
            description = "Browser automation framework for web testing (Java, Python, C#, Ruby, JS)",
            aliases = listOf("selenium", "selenium-java", "seleniumhq", "webdriver", "selenium-webdriver"),
            resourceDir = "selenium",
        ),
        LibraryMeta(
            id = "cucumber",
            title = "Cucumber",
            description = "BDD testing framework — runs Gherkin scenarios as automated tests (Java, JS, Ruby)",
            aliases = listOf("cucumber", "cucumber-java", "cucumber-jvm", "cucumber-junit"),
            resourceDir = "cucumber",
        ),
        LibraryMeta(
            id = "gherkin",
            title = "Gherkin",
            description = "Plain-text language for writing BDD scenarios (Given/When/Then syntax)",
            aliases = listOf("gherkin", "feature-file", "bdd", "given-when-then"),
            resourceDir = "gherkin",
        ),
        LibraryMeta(
            id = "java",
            title = "Java SE",
            description = "Java Standard Edition API — collections, streams, strings, IO, concurrency",
            aliases = listOf("java", "java-se", "jdk", "java-api", "java-lang"),
            resourceDir = "java",
        ),
        LibraryMeta(
            id = "mermaid",
            title = "Mermaid",
            description = "Text-based diagramming — flowcharts, sequence, class, state, ER, Gantt, pie, git graphs, and more",
            aliases = listOf("mermaid", "mermaid-js", "mermaidjs", "mermaid.js", "mermaid-diagram"),
            resourceDir = "mermaid",
        ),
    )

    private val byId: Map<String, LibraryMeta> = LIBRARIES.associateBy { it.id }

    /** All bundled library IDs for display in fallback messages. */
    val bundledIds: List<String> get() = LIBRARIES.map { it.id }

    /**
     * Find libraries matching a name/alias. Returns list of matches (exact first, then partial).
     */
    fun resolve(name: String): List<LibraryMeta> {
        val nameLower = name.lowercase().trim()
        val exact = mutableListOf<LibraryMeta>()
        val partial = mutableListOf<LibraryMeta>()

        for (lib in LIBRARIES) {
            when {
                // Exact match on ID or alias
                nameLower == lib.id || nameLower in lib.aliases -> exact.add(lib)
                // Partial: query is substring of ID or alias
                nameLower in lib.id || lib.aliases.any { nameLower in it } -> partial.add(lib)
                // Partial: ID or alias is substring of query
                lib.id in nameLower || lib.aliases.any { it in nameLower } -> partial.add(lib)
            }
        }

        return exact + partial
    }

    /**
     * Search local docs for a library by query. Returns matching sections within char budget.
     */
    fun searchDocs(libraryId: String, query: String, maxChars: Int = 4000): String {
        val meta = byId[libraryId] ?: return ""

        // Load all markdown from classpath resources
        val allSections = loadSections(meta.resourceDir)
        if (allSections.isEmpty()) return ""

        // Score sections by query relevance
        val queryTerms = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

        val scored = mutableListOf<Triple<Int, String, String>>() // (score, filename, section)
        for ((fname, section) in allSections) {
            val sectionLower = section.lowercase()
            // Count how many query terms appear in this section
            var score = queryTerms.count { it in sectionLower }
            // 2x bonus for terms in first line (heading)
            val firstLine = section.lineSequence().first().lowercase()
            score += queryTerms.count { it in firstLine } * 2
            if (score > 0) {
                scored.add(Triple(score, fname, section))
            }
        }

        if (scored.isEmpty()) {
            // No matches — return first few sections as overview
            val result = mutableListOf<String>()
            var total = 0
            for ((_, section) in allSections.take(5)) {
                val chunk = section.take(800)
                if (total + chunk.length > maxChars) break
                result.add(chunk)
                total += chunk.length
            }
            return result.joinToString("\n\n---\n\n")
        }

        // Return top-scoring sections
        scored.sortByDescending { it.first }
        val result = mutableListOf<String>()
        var total = 0
        for ((_, _, section) in scored.take(8)) {
            val chunk = section.take(1500)
            if (total + chunk.length > maxChars) {
                val remaining = scored.size - result.size
                if (remaining > 0) {
                    result.add("... $remaining more matching sections. Narrow your query.")
                }
                break
            }
            result.add(chunk)
            total += chunk.length
        }

        return result.joinToString("\n\n---\n\n")
    }

    /**
     * Load markdown files from classpath resources and split into sections by ## headings.
     */
    private fun loadSections(resourceDir: String): List<Pair<String, String>> {
        val sections = mutableListOf<Pair<String, String>>()

        // Known doc files per library directory
        val docFiles = when (resourceDir) {
            "playwright" -> listOf("api_reference.md")
            "selenium" -> listOf("api_reference.md")
            "cucumber" -> listOf("api_reference.md")
            "java" -> listOf("api_reference.md")
            "gherkin" -> listOf("reference.md")
            "mermaid" -> listOf("reference.md")
            else -> return sections
        }

        for (fname in docFiles) {
            val path = "/library-docs/$resourceDir/$fname"
            val content = LibraryDocs::class.java.getResourceAsStream(path)
                ?.bufferedReader()?.readText() ?: continue

            // Split by ## headings (keep the heading with its section)
            val parts = content.split(Regex("(?=^## )", RegexOption.MULTILINE))
            for (part in parts) {
                val trimmed = part.trim()
                if (trimmed.isNotEmpty()) {
                    sections.add(fname to trimmed)
                }
            }
        }

        return sections
    }
}
