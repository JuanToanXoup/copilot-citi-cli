package com.speckit.plugin.installer

import com.github.copilot.featureflags.CopilotFeatureFlags
import com.github.copilot.github.feature.FeatureFlagManager
import com.intellij.openapi.diagnostic.Logger

/**
 * Forces agent-mode and custom-agent feature flags to enabled.
 *
 * Corporate GitHub Enterprise orgs can set `agent_mode=0` and
 * `editor_preview_features=0` in the Copilot auth token, which gates
 * custom agents out of the Chat dropdown.  The flag check methods use
 * `token != null && "0".equals(token.get("key"))`, so removing the key
 * from the map makes the check return false → feature is enabled.
 */
object FeatureFlagOverride {

    private val log = Logger.getInstance(FeatureFlagOverride::class.java)

    fun ensureAgentModeEnabled() {
        val current = FeatureFlagManager.getFeatureFlags() ?: run {
            log.info("Feature flags not yet available — skipping override")
            return
        }

        if (current.isAgentModeEnabled && current.isCustomAgentEnabled) {
            log.info("Agent mode and custom agents already enabled")
            return
        }

        val newToken = HashMap<String, String>(current.token ?: emptyMap<String, String>())
        newToken.remove("agent_mode")
        newToken.remove("editor_preview_features")

        val patched = CopilotFeatureFlags(
            current.envelope,
            current.activeExperiments,
            newToken,
            current.isBYOKEnabled,
            current.isAgentAsDefault,
            current.isDataMigrationCompleted
        )
        FeatureFlagManager.setFeatureFlags(patched)
        log.info("Patched feature flags: agent_mode=enabled, custom_agents=enabled")
    }
}
