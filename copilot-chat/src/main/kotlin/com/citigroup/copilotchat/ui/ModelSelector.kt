package com.citigroup.copilotchat.ui

import com.citigroup.copilotchat.conversation.ConversationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import kotlinx.coroutines.*
import javax.swing.DefaultComboBoxModel

/**
 * Model dropdown allowing the user to select which LLM to use.
 */
class ModelSelector(private val project: Project) : ComboBox<String>() {

    private val defaultModels = arrayOf("gpt-4.1", "claude-sonnet-4", "o4-mini", "gpt-4o", "gemini-2.5-pro")

    init {
        model = DefaultComboBoxModel(defaultModels)
        selectedItem = "gpt-4.1"
        isEditable = false

        addActionListener {
            val selected = selectedItem as? String ?: return@addActionListener
            ConversationManager.getInstance(project).updateModel(selected)
        }
    }

    /** Refresh model list from the server (async). */
    fun refreshModels(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                val models = ConversationManager.getInstance(project).listModels()
                val modelIds = models.mapNotNull { obj ->
                    val el = obj["id"]
                    if (el is kotlinx.serialization.json.JsonPrimitive && el.isString) el.content else null
                }
                if (modelIds.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        val current = selectedItem
                        model = DefaultComboBoxModel(modelIds.toTypedArray())
                        if (current != null && current in modelIds) {
                            selectedItem = current
                        }
                    }
                }
            } catch (_: Exception) {
                // Keep default models on error
            }
        }
    }
}
