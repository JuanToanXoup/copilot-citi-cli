package com.speckit.plugin.tools

import java.io.File

data class TaskItem(
    val id: String,
    val checked: Boolean,
    val parallel: Boolean,
    val story: String?,
    val description: String,
    val rawLine: String,
    val lineNumber: Int
)

data class TaskPhase(
    val number: Int,
    val name: String,
    val priority: String?,
    val isMvp: Boolean,
    val goal: String?,
    val tasks: List<TaskItem>
)

data class TasksFile(
    val featureName: String,
    val phases: List<TaskPhase>,
    val file: File
) {
    val totalTasks get() = phases.sumOf { it.tasks.size }
    val completedTasks get() = phases.sumOf { p -> p.tasks.count { it.checked } }
}

object TasksParser {

    private val featureNamePattern = Regex("""^#\s+Tasks:\s+(.+)""")
    private val phasePattern = Regex("""^##\s+Phase\s+(\d+):\s+(.+)""")
    private val priorityPattern = Regex("""\(Priority:\s*(P\d)\)""")
    private val goalPattern = Regex("""^\*\*Goal\*\*:\s*(.+)""")
    private val taskPattern = Regex("""^-\s+\[([ xX])]\s+(T\d{3,4})\s*(\[P])?\s*(\[US\d+])?\s*(.+)$""")

    fun parse(file: File): TasksFile? {
        if (!file.isFile) return null
        val lines = file.readLines()
        if (lines.isEmpty()) return null

        var featureName = ""
        val phases = mutableListOf<TaskPhase>()
        var currentPhaseNumber = 0
        var currentPhaseName = ""
        var currentPriority: String? = null
        var currentIsMvp = false
        var currentGoal: String? = null
        var currentTasks = mutableListOf<TaskItem>()

        fun flushPhase() {
            if (currentPhaseNumber > 0) {
                phases.add(TaskPhase(currentPhaseNumber, currentPhaseName, currentPriority, currentIsMvp, currentGoal, currentTasks.toList()))
            }
        }

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trimStart()

            val featureMatch = featureNamePattern.find(trimmed)
            if (featureMatch != null) {
                featureName = featureMatch.groupValues[1].trim()
                continue
            }

            val phaseMatch = phasePattern.find(trimmed)
            if (phaseMatch != null) {
                flushPhase()
                currentPhaseNumber = phaseMatch.groupValues[1].toInt()
                val headerText = phaseMatch.groupValues[2].trim()
                currentPriority = priorityPattern.find(headerText)?.groupValues?.get(1)
                currentIsMvp = headerText.contains("MVP", ignoreCase = true)
                // Strip priority and MVP markers from the name
                currentPhaseName = headerText
                    .replace(priorityPattern, "")
                    .replace(Regex("""MVP""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""\(\s*,?\s*\)"""), "")  // leftover empty parens
                    .trim()
                    .trimEnd(',', ' ')
                currentGoal = null
                currentTasks = mutableListOf()
                continue
            }

            val goalMatch = goalPattern.find(trimmed)
            if (goalMatch != null && currentPhaseNumber > 0) {
                currentGoal = goalMatch.groupValues[1].trim()
                continue
            }

            val taskMatch = taskPattern.find(trimmed)
            if (taskMatch != null && currentPhaseNumber > 0) {
                val checked = taskMatch.groupValues[1].lowercase() == "x"
                val id = taskMatch.groupValues[2]
                val parallel = taskMatch.groupValues[3].isNotEmpty()
                val story = taskMatch.groupValues[4].takeIf { it.isNotEmpty() }
                    ?.removeSurrounding("[", "]")
                val description = taskMatch.groupValues[5].trim()
                currentTasks.add(TaskItem(id, checked, parallel, story, description, line, index + 1))
            }
        }
        flushPhase()

        if (phases.isEmpty()) return null
        return TasksFile(featureName, phases, file)
    }
}
