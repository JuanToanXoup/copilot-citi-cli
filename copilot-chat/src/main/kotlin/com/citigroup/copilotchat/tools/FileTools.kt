package com.citigroup.copilotchat.tools

import com.citigroup.copilotchat.tools.BuiltInToolUtils.OUTPUT_LIMIT
import com.citigroup.copilotchat.tools.BuiltInToolUtils.runCommand
import com.citigroup.copilotchat.tools.BuiltInToolUtils.str
import com.citigroup.copilotchat.tools.BuiltInToolUtils.int
import com.citigroup.copilotchat.tools.BuiltInToolUtils.strArray
import kotlinx.serialization.json.*
import java.io.File

object FileTools : ToolGroup {

    override val schemas: List<String> = listOf(
        """{"name":"read_file","description":"Read the contents of a file, optionally specifying a line range.","inputSchema":{"type":"object","properties":{"filePath":{"type":"string","description":"The absolute path of the file to read."},"startLineNumberBaseOne":{"type":"number","description":"Start line (1-based). Default: 1."},"endLineNumberBaseOne":{"type":"number","description":"End line inclusive (1-based). Default: end of file."}},"required":["filePath"]}}""",
        """{"name":"list_dir","description":"List the contents of a directory.","inputSchema":{"type":"object","properties":{"path":{"type":"string","description":"The absolute path to the directory to list."}},"required":["path"]}}""",
        """{"name":"create_file","description":"Create a new file with the given content.","inputSchema":{"type":"object","properties":{"filePath":{"type":"string","description":"The absolute path for the new file."},"content":{"type":"string","description":"The content of the new file."}},"required":["filePath","content"]}}""",
        """{"name":"create_directory","description":"Create a new directory (and parent directories as needed).","inputSchema":{"type":"object","properties":{"dirPath":{"type":"string","description":"The absolute path of the directory to create."}},"required":["dirPath"]}}""",
        """{"name":"insert_edit_into_file","description":"Insert or replace text in a file. Creates the file if it doesn't exist.","inputSchema":{"type":"object","properties":{"filePath":{"type":"string","description":"The absolute path of the file to edit."},"code":{"type":"string","description":"The new code to insert."},"explanation":{"type":"string","description":"A short explanation of what this edit does."}},"required":["filePath","code"]}}""",
        """{"name":"replace_string_in_file","description":"Replace an exact string match in a file with new content.","inputSchema":{"type":"object","properties":{"filePath":{"type":"string","description":"The absolute path of the file to edit."},"oldString":{"type":"string","description":"The exact literal text to replace."},"newString":{"type":"string","description":"The replacement text."},"explanation":{"type":"string","description":"A short explanation."}},"required":["filePath","oldString","newString","explanation"]}}""",
        """{"name":"multi_replace_string","description":"Apply multiple string replacements across one or more files in a single operation.","inputSchema":{"type":"object","properties":{"explanation":{"type":"string","description":"A brief explanation of the multi-replace operation."},"replacements":{"type":"array","description":"Array of replacement operations.","items":{"type":"object","properties":{"explanation":{"type":"string"},"filePath":{"type":"string"},"oldString":{"type":"string"},"newString":{"type":"string"}},"required":["explanation","filePath","oldString","newString"]},"minItems":1}},"required":["explanation","replacements"]}}""",
        """{"name":"apply_patch","description":"Apply a unified diff patch to files.","inputSchema":{"type":"object","properties":{"input":{"type":"string","description":"The patch content to apply."},"explanation":{"type":"string","description":"A short description of what the patch does."}},"required":["input","explanation"]}}""",
    )

    override val executors: Map<String, (JsonObject, String) -> String> = mapOf(
        "read_file" to ::executeReadFile,
        "list_dir" to ::executeListDir,
        "create_file" to ::executeCreateFile,
        "create_directory" to ::executeCreateDirectory,
        "insert_edit_into_file" to ::executeInsertEdit,
        "replace_string_in_file" to ::executeReplaceString,
        "multi_replace_string" to ::executeMultiReplace,
        "apply_patch" to ::executeApplyPatch,
    )

    private fun executeReadFile(input: JsonObject, ws: String): String {
        val filePath = input.str("filePath") ?: return "Error: filePath is required"
        val file = File(filePath)
        if (!file.exists()) return "Error: File not found: $filePath"
        val lines = file.readLines()
        val start = input.int("startLineNumberBaseOne") ?: 1
        val end = input.int("endLineNumberBaseOne") ?: lines.size
        val selected = lines.subList((start - 1).coerceIn(0, lines.size), end.coerceIn(0, lines.size))
        return "File `$filePath`. Total ${lines.size} lines. Lines $start-$end:\n```\n${selected.joinToString("\n")}\n```"
    }

    private fun executeListDir(input: JsonObject, ws: String): String {
        val path = input.str("path") ?: return "Error: path is required"
        val dir = File(path)
        if (!dir.isDirectory) return "Error: Not a directory: $path"
        return dir.listFiles()?.sorted()?.joinToString("\n") { f ->
            if (f.isDirectory) "[dir]  ${f.name}" else "[file] ${f.name} (${f.length()} bytes)"
        } ?: "Error: Cannot list directory"
    }

    private fun executeCreateFile(input: JsonObject, ws: String): String {
        val filePath = input.str("filePath") ?: return "Error: filePath is required"
        val content = input.str("content") ?: return "Error: content is required"
        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return "Created file: $filePath (${content.length} chars)"
    }

    private fun executeCreateDirectory(input: JsonObject, ws: String): String {
        val dirPath = input.str("dirPath") ?: return "Error: dirPath is required"
        File(dirPath).mkdirs()
        return "Created directory: $dirPath"
    }

    private fun executeInsertEdit(input: JsonObject, ws: String): String {
        val filePath = input.str("filePath") ?: return "Error: filePath is required"
        val code = input.str("code") ?: return "Error: code is required"
        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.writeText(code)
        return "Wrote ${code.length} chars to $filePath"
    }

    private fun executeReplaceString(input: JsonObject, ws: String): String {
        val filePath = input.str("filePath") ?: return "Error: filePath is required"
        val oldString = input.str("oldString") ?: return "Error: oldString is required"
        val newString = input.str("newString") ?: return "Error: newString is required"
        val file = File(filePath)
        if (!file.exists()) return "Error: File not found: $filePath"
        val content = file.readText()
        if (oldString !in content) return "Error: oldString not found in $filePath"
        file.writeText(content.replaceFirst(oldString, newString))
        return "Replaced in $filePath"
    }

    private fun executeMultiReplace(input: JsonObject, ws: String): String {
        val replacements = input["replacements"]?.jsonArray ?: return "Error: replacements is required"
        val results = mutableListOf<String>()
        for (rep in replacements) {
            val obj = rep.jsonObject
            val filePath = obj.str("filePath") ?: continue
            val oldString = obj.str("oldString") ?: continue
            val newString = obj.str("newString") ?: continue
            val file = File(filePath)
            if (!file.exists()) { results.add("SKIP $filePath: not found"); continue }
            val content = file.readText()
            if (oldString !in content) { results.add("SKIP $filePath: oldString not found"); continue }
            file.writeText(content.replaceFirst(oldString, newString))
            results.add("OK $filePath")
        }
        return results.joinToString("\n")
    }

    private fun executeApplyPatch(input: JsonObject, ws: String): String {
        val patch = input.str("input") ?: return "Error: input is required"
        return runCommand(listOf("patch", "-p1", "--directory=$ws"), stdin = patch, timeout = 30)
    }
}
