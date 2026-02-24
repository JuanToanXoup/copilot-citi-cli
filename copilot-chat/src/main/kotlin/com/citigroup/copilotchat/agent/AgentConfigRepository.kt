package com.citigroup.copilotchat.agent

/**
 * Abstracts persistence of [AgentDefinition]s so consumers (e.g. UI panels)
 * don't depend on file-system details.
 *
 * The default implementation delegates to [AgentRegistry].
 */
interface AgentConfigRepository {
    /** Load all agents (built-in + custom from project and user dirs). */
    fun loadAll(projectBasePath: String?): List<AgentDefinition>

    /** Save an agent definition to disk. Returns an updated copy with resolved filePath. */
    fun saveAgent(agent: AgentDefinition, projectBasePath: String, existingFilePath: String?  = null): AgentDefinition

    /** Delete an agent's file from disk by path. */
    fun deleteAgentFile(filePath: String)

    /** Find an agent by name and delete its file. */
    fun deleteAgentByName(name: String, projectBasePath: String?)
}
