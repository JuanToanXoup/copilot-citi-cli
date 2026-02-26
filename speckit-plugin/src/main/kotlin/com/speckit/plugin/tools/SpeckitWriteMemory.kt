package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationManager
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.io.File
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CompletableFuture

class SpeckitWriteMemory : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_write_memory",
        "Write or update a memory file in .specify/memory/.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name" to mapOf("type" to "string", "description" to "File name, e.g. 'constitution.md'"),
                "content" to mapOf("type" to "string", "description" to "Full content to write to the file")
            ),
            "required" to listOf("name", "content")
        ),
        null,
        "function",
        "enabled"
    )

    override suspend fun handleInvocation(
        request: ToolInvocationRequest
    ): LanguageModelToolResult {
        val manager = ApplicationManager.getApplication().getService(ToolInvocationManager::class.java)
        val project = manager.findProjectForInvocation(request.identifier)
            ?: return LanguageModelToolResult.Companion.error("No project found for invocation")
        val basePath = project.basePath
            ?: return LanguageModelToolResult.Companion.error("No project base path")

        val name = request.input?.get("name")?.asString
            ?: return LanguageModelToolResult.Companion.error("Missing required parameter: name")
        val content = request.input?.get("content")?.asString
            ?: return LanguageModelToolResult.Companion.error("Missing required parameter: content")

        val filePath = "$basePath/.specify/memory/$name"
        val ioFile = File(filePath)
        val parentPath = ioFile.parent

        if (project.isDisposed) {
            return LanguageModelToolResult.Companion.error(
                "Project is disposed — cannot write to $filePath"
            )
        }

        val future = CompletableFuture<LanguageModelToolResult>()
        scheduleVfsWrite(project, parentPath, ioFile, name, content, future)

        return try {
            withTimeout(120_000) { future.await() }
        } catch (e: Exception) {
            // EDT was busy (e.g. indexing). Retry once.
            val retryFuture = CompletableFuture<LanguageModelToolResult>()
            scheduleVfsWrite(project, parentPath, ioFile, name, content, retryFuture)
            try {
                withTimeout(120_000) { retryFuture.await() }
            } catch (retryEx: Exception) {
                LanguageModelToolResult.Companion.error(
                    "Timed out writing .specify/memory/$name after retry: ${retryEx.message}"
                )
            }
        }
    }

    private fun scheduleVfsWrite(
        project: com.intellij.openapi.project.Project,
        parentPath: String,
        ioFile: File,
        name: String,
        content: String,
        future: CompletableFuture<LanguageModelToolResult>
    ) {
        runInEdt {
            if (project.isDisposed) {
                future.complete(LanguageModelToolResult.Companion.error("Project disposed"))
                return@runInEdt
            }
            try {
                WriteCommandAction.runWriteCommandAction(project) {
                    var parentVFile = LocalFileSystem.getInstance()
                        .findFileByIoFile(File(parentPath))
                    if (parentVFile == null) {
                        parentVFile = VfsUtil.createDirectories(parentPath)
                    }
                    if (parentVFile == null) {
                        future.complete(LanguageModelToolResult.Companion.error(
                            "Failed to create directory: $parentPath"
                        ))
                        return@runWriteCommandAction
                    }
                    parentVFile.refresh(false, false)
                    val existingVFile = parentVFile.findChild(ioFile.name)
                    if (existingVFile != null) {
                        VfsUtil.saveText(existingVFile, content)
                        future.complete(LanguageModelToolResult.Companion.success(
                            "Updated ${content.length} chars in ${existingVFile.path}"
                        ))
                    } else {
                        val vFile = parentVFile.createChildData(this@SpeckitWriteMemory, ioFile.name)
                        VfsUtil.saveText(vFile, content)
                        future.complete(LanguageModelToolResult.Companion.success(
                            "Written ${content.length} chars to ${vFile.path}"
                        ))
                    }
                }
            } catch (e: Exception) {
                if (!future.isDone) {
                    future.complete(LanguageModelToolResult.Companion.error(
                        "Failed to write .specify/memory/$name: ${e.message}"
                    ))
                }
            }
        }
    }
}
