package com.citigroup.copilotchat.workingset

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.openapi.progress.DumbProgressIndicator

/**
 * Computes which line ranges the agent changed using IntelliJ's ComparisonManager.
 * Returns hunk-level attribution data: which lines in the current file were written by the agent.
 */
object HunkDiffEngine {

    /**
     * A contiguous range of lines attributed to the agent.
     * @param startLine 1-based inclusive start line
     * @param endLine 1-based inclusive end line
     */
    data class HunkRange(val startLine: Int, val endLine: Int) {
        val lineCount: Int get() = endLine - startLine + 1
    }

    /**
     * Compute which line ranges in [currentContent] were changed by the agent
     * relative to [originalContent].
     *
     * For new files (originalContent == null), returns a single hunk covering all lines.
     */
    fun computeAgentHunks(originalContent: String?, currentContent: String): List<HunkRange> {
        if (originalContent == null) {
            // New file — entire content is agent-authored
            val lineCount = currentContent.lines().size
            return if (lineCount > 0) listOf(HunkRange(1, lineCount)) else emptyList()
        }

        if (originalContent == currentContent) return emptyList()

        val fragments = ComparisonManager.getInstance().compareLines(
            originalContent,
            currentContent,
            ComparisonPolicy.DEFAULT,
            DumbProgressIndicator.INSTANCE,
        )

        return fragments.map { fragment ->
            // fragment.startLine2 / endLine2 are 0-based line indices in the "after" text
            // endLine2 is exclusive, so we convert to 1-based inclusive
            val start = fragment.startLine2 + 1
            val end = fragment.endLine2.coerceAtLeast(fragment.startLine2 + 1)
            HunkRange(start, end)
        }.filter { it.lineCount > 0 }
    }
}
