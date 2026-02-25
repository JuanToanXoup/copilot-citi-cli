# Injecting In-Process Tools into GitHub Copilot Chat's LSP

This guide explains how `speckit-companion` injects custom tools into the official
GitHub Copilot IntelliJ plugin and how to replicate the pattern for a new plugin.

---

## How It Works (3-Sentence Summary)

A standalone IntelliJ plugin declares `<depends>com.github.copilot</depends>` and
compiles against the Copilot plugin's internal classes. At project startup, a
`postStartupActivity` grabs Copilot's singleton `ToolRegistryImpl`, calls
`registerTool()` for each custom tool, then calls
`ConversationToolService.registerTools()` to push the full list to the LSP server.
When the model invokes a tool, Copilot's own handler calls back into your
`handleInvocation()` — entirely in-process, no server-side MCP, no content policy.

---

## Architecture

```
IntelliJ JVM
├── com.github.copilot  (official plugin)
│   ├── ToolRegistryProvider.getInstance() → ToolRegistryImpl
│   ├── ToolRegistryImpl.registerTool(registration)
│   └── ConversationToolService.registerTools(all) → LSP "conversation/registerTools"
│
└── your-plugin  (your new plugin)
    └── YourToolInstaller : StartupActivity.DumbAware
        ├── Instantiates your tool classes
        ├── Registers them into ToolRegistryImpl
        └── Calls ConversationToolService.registerTools()

Invocation flow:
  LSP server → "conversation/invokeClientTool" → Copilot plugin
    → ToolRegistryImpl.findTool(name) → YourTool.handleInvocation()
    → your logic runs → result returned to LSP
```

---

## Step-by-Step: Create a New Plugin

### 1. Scaffold the directory structure

```
your-plugin/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/
│   ├── wrapper/
│   │   └── gradle-wrapper.properties
│   └── libs.versions.toml
└── src/main/
    ├── kotlin/com/yourorg/yourplugin/
    │   ├── installer/
    │   │   └── YourToolInstaller.kt
    │   └── tools/
    │       ├── YourFirstTool.kt
    │       └── ScriptRunner.kt          (optional — for shell execution)
    └── resources/META-INF/
        └── plugin.xml
```

### 2. `gradle/libs.versions.toml`

```toml
[versions]
gson = "2.11.0"
intellij = "1.17.4"
kotlin = "2.1.20"

[libraries]
gson = { group = "com.google.code.gson", name = "gson", version.ref = "gson" }

[plugins]
intellij = { id = "org.jetbrains.intellij", version.ref = "intellij" }
kotlin = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
```

### 3. `gradle.properties`

```properties
pluginGroup = com.yourorg.yourplugin
pluginName = Your Plugin Name
pluginVersion = 0.1.0

pluginSinceBuild = 243
platformType = IC
platformVersion = 2024.3.3

gradleVersion = 8.10
kotlin.stdlib.default.dependency = false
org.gradle.configuration-cache = false
org.gradle.caching = true
```

### 4. `settings.gradle.kts`

```kotlin
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "your-plugin"
```

### 5. `build.gradle.kts`

The critical piece — compile against the installed Copilot plugin:

```kotlin
plugins {
    id("java")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intellij)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

dependencies {
    // Copilot uses Gson for ToolInvocationRequest.input (JsonObject)
    compileOnly(libs.gson)
}

// --- Auto-detect GitHub Copilot plugin from local IDE installation ---
fun findCopilotPlugin(): String {
    // Allow explicit override: -PcopilotPluginPath=/path/to/github-copilot-intellij
    val explicit = providers.gradleProperty("copilotPluginPath").orNull
    if (explicit != null && file(explicit).exists()) return explicit

    val home = System.getProperty("user.home")
    val candidates = listOf(
        "$home/Library/Application Support/JetBrains",           // macOS
        "$home/.local/share/JetBrains",                          // Linux
        "${System.getenv("APPDATA") ?: "$home/AppData/Roaming"}/JetBrains",  // Windows
    )
    val idePatterns = listOf("IntelliJIdea*", "IdeaIC*")
    for (base in candidates) {
        val baseDir = file(base)
        if (!baseDir.isDirectory) continue
        for (pattern in idePatterns) {
            baseDir.listFiles { f ->
                f.isDirectory && f.name.matches(Regex(pattern.replace("*", ".*")))
            }
            ?.sortedByDescending { it.name }
            ?.forEach { ideDir ->
                val copilotDir = file("${ideDir.absolutePath}/plugins/github-copilot-intellij")
                if (copilotDir.isDirectory) return copilotDir.absolutePath
            }
        }
    }
    error("""
        Cannot find GitHub Copilot plugin. Set copilotPluginPath:
          - gradle.properties:  copilotPluginPath=/path/to/github-copilot-intellij
          - command line:       ./gradlew build -PcopilotPluginPath=/path/to/...
    """.trimIndent())
}

intellij {
    pluginName.set(providers.gradleProperty("pluginName"))
    version.set(providers.gradleProperty("platformVersion"))
    type.set(providers.gradleProperty("platformType"))

    plugins.set(listOf(findCopilotPlugin()))   // <-- this is the key line
}

tasks {
    patchPluginXml {
        version.set(providers.gradleProperty("pluginVersion"))
        sinceBuild.set(providers.gradleProperty("pluginSinceBuild"))
        untilBuild.set("")
    }
    buildSearchableOptions { enabled = false }
}
```

### 6. `plugin.xml`

```xml
<idea-plugin>
    <id>com.yourorg.yourplugin</id>
    <name>Your Plugin Name</name>
    <version>0.1.0</version>
    <vendor>yourorg</vendor>
    <description>Registers custom tools on the GitHub Copilot Chat LSP client.</description>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.github.copilot</depends>          <!-- HARD dep — guarantees load order -->

    <extensions defaultExtensionNs="com.intellij">
        <postStartupActivity
            implementation="com.yourorg.yourplugin.installer.YourToolInstaller"/>
    </extensions>
</idea-plugin>
```

### 7. `YourToolInstaller.kt` — the entry point

```kotlin
package com.yourorg.yourplugin.installer

import com.github.copilot.chat.conversation.agent.tool.ConversationToolService
import com.github.copilot.chat.conversation.agent.tool.ToolRegistryImpl
import com.github.copilot.chat.conversation.agent.tool.ToolRegistryProvider
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.yourorg.yourplugin.tools.YourFirstTool

class YourToolInstaller : StartupActivity.DumbAware {

    private val log = Logger.getInstance(YourToolInstaller::class.java)

    override fun runActivity(project: Project) {
        val basePath = project.basePath ?: return
        try {
            registerTools(basePath)
        } catch (e: Exception) {
            log.warn("Tool registration failed — Copilot Chat may not be ready yet", e)
        }
    }

    private fun registerTools(basePath: String) {
        // 1. Get Copilot's mutable tool registry
        val registry = ToolRegistryProvider.getInstance()
        if (registry !is ToolRegistryImpl) {
            log.warn("ToolRegistry is not ToolRegistryImpl — cannot register tools")
            return
        }

        // 2. Register your tools
        val tools = listOf(
            YourFirstTool(basePath),
            // add more tools here...
        )
        for (tool in tools) {
            registry.registerTool(tool)
            log.info("Registered tool: ${tool.toolDefinition.name}")
        }

        // 3. Push the full tool list to Copilot's LSP server
        val allTools = registry.getRegisteredTools()
        ConversationToolService.Companion.getInstance().registerTools(allTools)
        log.info("Pushed ${allTools.size} tools to Copilot LSP client")
    }
}
```

### 8. `YourFirstTool.kt` — implementing a tool

Every tool implements `LanguageModelToolRegistration` from the Copilot plugin:

```kotlin
package com.yourorg.yourplugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest

class YourFirstTool(private val basePath: String) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "your_tool_name",                    // unique tool name the model will call
        "Description of what this tool does", // LLM-facing description
        mapOf(                                // JSON Schema for input parameters
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf(
                    "type" to "string",
                    "description" to "The search query"
                )
            ),
            "required" to listOf("query")
        ),
        null,                                 // outputSchema (optional, usually null)
        "function",                           // type — always "function"
        "enabled"                             // status — always "enabled"
    )

    override suspend fun handleInvocation(
        request: ToolInvocationRequest
    ): LanguageModelToolResult {
        // Extract parameters from request.input (Gson JsonObject)
        val query = request.input?.get("query")?.asString
            ?: return LanguageModelToolResult.Companion.error("Missing required parameter: query")

        return try {
            val result = doWork(query)
            LanguageModelToolResult.Companion.success(result)
        } catch (e: Exception) {
            LanguageModelToolResult.Companion.error("Tool failed: ${e.message}")
        }
    }

    private fun doWork(query: String): String {
        // Your tool logic here — examples:
        //   - Run a shell command via ScriptRunner
        //   - Read/write files
        //   - Call an API
        //   - Query a database
        return "Result for: $query"
    }
}
```

### 9. (Optional) `ScriptRunner.kt` — run shell commands

Copy this utility for tools that need to execute processes:

```kotlin
package com.yourorg.yourplugin.tools

import java.io.File
import java.util.concurrent.TimeUnit

object ScriptRunner {
    fun exec(
        command: List<String>,
        workingDir: String,
        timeoutSeconds: Long = 120
    ): Result {
        val process = ProcessBuilder(command)
            .directory(File(workingDir))
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            return Result(1, "$output\n[TIMEOUT after ${timeoutSeconds}s]")
        }
        return Result(process.exitValue(), output)
    }

    data class Result(val exitCode: Int, val output: String) {
        val success get() = exitCode == 0
    }
}
```

---

## Including in the Parent Project

If your plugin lives alongside `copilot-chat` in a composite build, add it to the
root `settings.gradle.kts`:

```kotlin
includeBuild("your-plugin")
```

Build with:

```bash
cd your-plugin && ./gradlew buildPlugin
```

The output `.zip` goes to `your-plugin/build/distributions/`. Install it via
**Settings > Plugins > gear icon > Install from Disk**.

---

## Bridging MCP Servers as In-Process Tools

If you want to bridge an external MCP server (stdio) into Copilot's registry
instead of writing native Kotlin tools, reuse the `McpClient` + `McpToolBridge`
pattern. Add this to your installer:

```kotlin
private fun registerMcpTools(registry: ToolRegistryImpl) {
    val client = McpClient.start("npx", listOf("@playwright/mcp@latest"))
    for (tool in client.listTools()) {
        registry.registerTool(McpToolBridge(client, tool, namePrefix = "pw"))
        log.info("Registered MCP tool: pw_${tool.name}")
    }
}
```

This spawns the MCP server locally, lists its tools, wraps each as a
`LanguageModelToolRegistration`, and registers them. The model calls them like
native tools; your bridge forwards to the MCP server transparently.

---

## Key Copilot Internal Classes (Undocumented)

| Class | Package | Purpose |
|---|---|---|
| `ToolRegistryProvider` | `com.github.copilot.chat.conversation.agent.tool` | Singleton accessor for the tool registry |
| `ToolRegistryImpl` | same | Mutable registry — `registerTool()`, `getRegisteredTools()` |
| `ConversationToolService` | same | Pushes tool schemas to LSP via `conversation/registerTools` |
| `LanguageModelToolRegistration` | same | Interface your tools must implement |
| `LanguageModelTool` | `com.github.copilot.chat.conversation.agent.rpc.command` | Tool definition (name, description, schema) |
| `LanguageModelToolResult` | same | Return type — `.success(text)` or `.error(text)` |
| `ToolInvocationRequest` | `...agent.tool` | Carries `input: JsonObject?` (Gson) |

---

## Why This Works (and Risks)

**Why it bypasses content policy:**
Server-side MCP tools registered via `workspace/didChangeConfiguration` go through
Copilot's content policy filter. Tools registered via `conversation/registerTools`
(the client-tool channel) do not. This plugin injects directly into the registry
that feeds `conversation/registerTools`.

**Fragility:**
These are undocumented internal classes. A Copilot plugin update could rename or
restructure them at any time. The `try/catch` in the installer means a breaking
change degrades silently (tools just don't register, a warning is logged). Pin
your Copilot plugin version in CI if stability matters.

**Scope:**
`postStartupActivity` runs once per project open. Tools are bound to `project.basePath`
at startup. There's no dynamic re-registration if the project root changes.

---

## Checklist for a New Plugin

- [ ] `build.gradle.kts` with `findCopilotPlugin()` and `plugins.set(listOf(...))`
- [ ] `plugin.xml` with `<depends>com.github.copilot</depends>` and `postStartupActivity`
- [ ] Installer class: `StartupActivity.DumbAware` → get registry → register tools → push
- [ ] Each tool: implement `LanguageModelToolRegistration` with `toolDefinition` + `handleInvocation()`
- [ ] `compileOnly(libs.gson)` for `JsonObject` in tool request/response
- [ ] Wrap in `try/catch` at the installer level for graceful degradation
- [ ] Build: `./gradlew buildPlugin` → install `.zip` from `build/distributions/`
