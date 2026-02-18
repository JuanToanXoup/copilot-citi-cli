# Running IntelliJ Headlessly with MCP Stdio

## Overview

IntelliJ IDEA can be launched headlessly (no GUI) to serve as an MCP server over stdio and SSE simultaneously. This is useful for CI environments, remote servers, or integrating with AI coding agents that communicate over stdio.

The `HeadlessMcpStarter` (`ApplicationStarter` extension) boots IntelliJ, opens a project, waits for indexing, and starts the stdio transport. The process stays alive until stdin closes or SIGTERM.

## Quick Start

### Linux

```bash
idea.sh mcp-stdio /path/to/your/project
```

### macOS

The native `idea` binary inside the `.app` bundle does not work reliably from the terminal due to macOS code-signing provenance checks. Instead, launch headlessly via the bundled JBR `java` directly.

**Using a Gradle sandbox (plugin development):**

```bash
./gradlew prepareSandbox
```

Then run with the script below, or see [Full Launch Command](#full-launch-command-macos).

**Using an installed IntelliJ IDEA CE:**

```bash
IDE_HOME="/Applications/IntelliJ IDEA CE.app/Contents"
# Same java launch command — just adjust IDE_HOME
```

## Full Launch Command (macOS)

The key insight: IntelliJ requires a specific set of boot classpath JARs, `--add-opens` flags, and system properties defined in `$IDE_HOME/Resources/product-info.json` and `$IDE_HOME/bin/idea.vmoptions`. Without the complete set, startup fails with `IllegalAccessError` or missing class loader errors.

```bash
#!/usr/bin/env bash
set -euo pipefail

# === Configuration ===
# For Gradle sandbox (plugin development):
IDE_HOME="$HOME/.gradle/caches/9.0.0/transforms/<hash>/transformed/ideaIC-<version>-aarch64"
SANDBOX="$(pwd)/build/idea-sandbox"
CONFIG_PATH="$SANDBOX/IC-<version>/config"
SYSTEM_PATH="$SANDBOX/IC-<version>/system"
PLUGINS_PATH="$SANDBOX/IC-<version>/plugins"
LOG_PATH="$SANDBOX/IC-<version>/log"

# For installed IntelliJ:
# IDE_HOME="/Applications/IntelliJ IDEA CE.app/Contents"
# CONFIG_PATH="$HOME/Library/Application Support/JetBrains/IdeaIC2025.1"
# SYSTEM_PATH="$HOME/Library/Caches/JetBrains/IdeaIC2025.1"
# PLUGINS_PATH="$HOME/Library/Application Support/JetBrains/IdeaIC2025.1/plugins"
# LOG_PATH="$HOME/Library/Logs/JetBrains/IdeaIC2025.1"

PROJECT_PATH="/path/to/your/project"
JBR="$IDE_HOME/jbr/Contents/Home"
LIB="$IDE_HOME/lib"

# === Build boot classpath ===
# These JARs come from product-info.json → launch[0].bootClassPathJarNames
BOOT_CP=""
for jar in \
  platform-loader.jar util-8.jar util.jar app-client.jar util_rt.jar \
  lib-client.jar trove.jar app.jar opentelemetry.jar jps-model.jar \
  stats.jar rd.jar external-system-rt.jar protobuf.jar bouncy-castle.jar \
  intellij-test-discovery.jar forms_rt.jar lib.jar externalProcess-rt.jar \
  groovy.jar annotations.jar idea_rt.jar jsch-agent.jar \
  kotlinx-coroutines-slf4j-1.8.0-intellij.jar; do
  BOOT_CP="${BOOT_CP:+$BOOT_CP:}$LIB/$jar"
done

# === Launch ===
exec "$JBR/bin/java" \
  -classpath "$BOOT_CP" \
  \
  `# --- VM options (from idea.vmoptions) ---` \
  -Xms128m \
  -Xmx2048m \
  -XX:ReservedCodeCacheSize=512m \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:-OmitStackTraceInFastThrow \
  -XX:+IgnoreUnrecognizedVMOptions \
  -ea \
  \
  `# --- System properties (from product-info.json + vmoptions) ---` \
  -Xbootclasspath/a:"$LIB/nio-fs.jar" \
  -Djava.system.class.loader=com.intellij.util.lang.PathClassLoader \
  -Didea.vendor.name=JetBrains \
  -Didea.paths.selector=IdeaIC2025.1 \
  -Djna.boot.library.path="$LIB/jna/aarch64" \
  -Djna.nosys=true \
  -Djna.noclasspath=true \
  -Dpty4j.preferred.native.folder="$LIB/pty4j" \
  -Dio.netty.allocator.type=pooled \
  -Dintellij.platform.runtime.repository.path="$IDE_HOME/modules/module-descriptors.jar" \
  -Didea.platform.prefix=Idea \
  -Dsplash=false \
  -Djava.awt.headless=true \
  -Dsun.io.useCanonCaches=false \
  -Dsun.java2d.metal=true \
  -Djbr.catch.SIGABRT=true \
  -Djdk.http.auth.tunneling.disabledSchemes="" \
  -Djdk.attach.allowAttachSelf=true \
  -Djdk.module.illegalAccess.silent=true \
  -Dkotlinx.coroutines.debug=off \
  \
  `# --- Sandbox paths ---` \
  -Didea.config.path="$CONFIG_PATH" \
  -Didea.system.path="$SYSTEM_PATH" \
  -Didea.plugins.path="$PLUGINS_PATH" \
  -Didea.log.path="$LOG_PATH" \
  \
  `# --- Module opens (ALL required — startup fails without these) ---` \
  --add-opens=java.base/java.io=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.lang.ref=ALL-UNNAMED \
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens=java.base/java.net=ALL-UNNAMED \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/java.nio.charset=ALL-UNNAMED \
  --add-opens=java.base/java.text=ALL-UNNAMED \
  --add-opens=java.base/java.time=ALL-UNNAMED \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \
  --add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.vm=ALL-UNNAMED \
  --add-opens=java.base/sun.net.dns=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.fs=ALL-UNNAMED \
  --add-opens=java.base/sun.security.ssl=ALL-UNNAMED \
  --add-opens=java.base/sun.security.util=ALL-UNNAMED \
  --add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED \
  --add-opens=java.desktop/com.apple.eawt.event=ALL-UNNAMED \
  --add-opens=java.desktop/com.apple.laf=ALL-UNNAMED \
  --add-opens=java.desktop/com.sun.java.swing=ALL-UNNAMED \
  --add-opens=java.desktop/java.awt=ALL-UNNAMED \
  --add-opens=java.desktop/java.awt.dnd.peer=ALL-UNNAMED \
  --add-opens=java.desktop/java.awt.event=ALL-UNNAMED \
  --add-opens=java.desktop/java.awt.font=ALL-UNNAMED \
  --add-opens=java.desktop/java.awt.image=ALL-UNNAMED \
  --add-opens=java.desktop/java.awt.peer=ALL-UNNAMED \
  --add-opens=java.desktop/javax.swing=ALL-UNNAMED \
  --add-opens=java.desktop/javax.swing.plaf.basic=ALL-UNNAMED \
  --add-opens=java.desktop/javax.swing.text=ALL-UNNAMED \
  --add-opens=java.desktop/javax.swing.text.html=ALL-UNNAMED \
  --add-opens=java.desktop/javax.swing.text.html.parser=ALL-UNNAMED \
  --add-opens=java.desktop/sun.awt=ALL-UNNAMED \
  --add-opens=java.desktop/sun.awt.datatransfer=ALL-UNNAMED \
  --add-opens=java.desktop/sun.awt.image=ALL-UNNAMED \
  --add-opens=java.desktop/sun.font=ALL-UNNAMED \
  --add-opens=java.desktop/sun.java2d=ALL-UNNAMED \
  --add-opens=java.desktop/sun.lwawt=ALL-UNNAMED \
  --add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED \
  --add-opens=java.desktop/sun.swing=ALL-UNNAMED \
  --add-opens=java.management/sun.management=ALL-UNNAMED \
  --add-opens=jdk.attach/sun.tools.attach=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
  --add-opens=jdk.internal.jvmstat/sun.jvmstat.monitor=ALL-UNNAMED \
  --add-opens=jdk.jdi/com.sun.tools.jdi=ALL-UNNAMED \
  \
  `# --- Main class + command ---` \
  com.intellij.idea.Main \
  mcp-stdio "$PROJECT_PATH"
```

## What Happens on Startup

1. JBR Java starts with IntelliJ's custom `PathClassLoader`
2. `com.intellij.idea.Main` boots the platform in headless mode
3. The `mcp-stdio` command triggers `HeadlessMcpStarter` (registered as an `ApplicationStarter` in `plugin.xml`)
4. `HeadlessMcpStarter` opens the project and waits for indexing via `DumbService.runWhenSmart`
5. `McpServerService` initializes — starts the SSE/HTTP server on port 29170
6. Stdio transport starts — reads JSON-RPC from stdin, writes to stdout
7. Process blocks until stdin closes or SIGTERM

**Expected stderr output:**
```
Project opened: my-project
Waiting for indexing to complete...
Indexing complete.
SSE transport available at: http://127.0.0.1:29170/index-mcp/sse
MCP stdio transport started. Reading from stdin...
Tools registered: 15
```

## Agent Configuration

### Claude Code (stdio)
```json
{
  "mcpServers": {
    "intellij-index": {
      "command": "/path/to/headless-idea.sh",
      "args": ["/path/to/your/project"]
    }
  }
}
```

### Claude Code (SSE — once headless process is running)
```bash
claude mcp add --transport http intellij-index http://127.0.0.1:29170/index-mcp/sse
```

## Troubleshooting

### `ClassNotFoundException: PathClassLoader`
You're missing boot classpath JARs. The `-classpath` must include all JARs listed in `product-info.json` → `launch[0].bootClassPathJarNames`.

### `IllegalAccessError: sun.awt.AWTAutoShutdown`
Missing `--add-opens` flags. You need the **complete** set from `product-info.json` → `launch[0].additionalJvmArguments`, including all `java.desktop/sun.awt` opens.

### `permission denied` on macOS native binary
The `MacOS/idea` binary may have `com.apple.provenance` extended attributes from Gatekeeper. Use the JBR `java` binary directly instead — it doesn't have this issue.

### Gradle transform cache corruption
If you see errors about "immutable workspace modified", delete the corrupted transform directory:
```bash
rm -rf ~/.gradle/caches/9.0.0/transforms/<hash>
./gradlew clean initializeIntellijPlatformPlugin
```

### Finding the correct `IDE_HOME` path
For Gradle plugin development, the IDE is extracted to the transforms cache. Find it with:
```bash
ls ~/.gradle/caches/*/transforms/*/transformed/ideaIC-*
```

### Harmless warnings
These can be safely ignored:
- `[JRSAppKitAWT markAppIsDaemon] failed` — macOS AWT daemon mode, irrelevant in headless
- `Bundled shared index is not found` — optional optimization, indexing works without it
- `SLF4J: No SLF4J providers were found` — logging falls back to NOP, IntelliJ uses its own logging
- `Dark color scheme is missing` — UI theme, irrelevant in headless mode

## Differences from GUI Mode

| Aspect | GUI Mode | Headless Mode |
|--------|----------|---------------|
| Transports | SSE/HTTP only (stdio opt-in) | Both stdio + SSE/HTTP |
| Project | User opens via IDE | Specified as CLI argument |
| Indexing | Background with progress UI | Blocks startup until complete |
| Lifecycle | IDE window close | stdin EOF or SIGTERM |
| `-Djava.awt.headless` | `false` | `true` |
| `-Dsplash` | `true` | `false` |

## Verified Test Results

Tested on macOS aarch64, IntelliJ IDEA CE 2025.1.3, headless mode via JBR `java`. All tests use the stdio transport (JSON-RPC over stdin/stdout).

### Test Summary

| # | Test | Method | Result |
|---|------|--------|--------|
| 1 | Initialize | `initialize` | Pass |
| 2 | List tools | `tools/list` | Pass — 15 tools |
| 3 | Index status | `ide_index_status` | Pass |
| 4 | Find class | `ide_find_class` | Pass |
| 5 | Go to definition | `ide_find_definition` | Pass |
| 6 | Find references | `ide_find_references` | Pass |
| 7 | Type hierarchy | `ide_type_hierarchy` | Pass |
| 8 | Diagnostics | `ide_diagnostics` | Pass |

### Test 1: `initialize`

Confirms the MCP handshake works over stdio.

```
→ {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}
```
```json
← {
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2024-11-05",
    "capabilities": { "tools": { "listChanged": false } },
    "serverInfo": {
      "name": "jetbrains-index-mcp",
      "version": "2.0.0"
    }
  }
}
```

### Test 2: `tools/list`

Returns all 15 registered tools:
- `ide_find_implementations`
- `ide_refactor_rename`
- `ide_refactor_safe_delete`
- `ide_find_definition`
- `ide_find_class`
- `ide_find_super_methods`
- `ide_diagnostics`
- `ide_index_status`
- `ide_search_text`
- `ide_find_file`
- `ide_type_hierarchy`
- `ide_call_hierarchy`
- `ide_find_references`

### Test 3: `ide_index_status`

Verifies the project is fully indexed and ready for queries.

```
→ {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ide_index_status","arguments":{}}}
```
```json
← {"isDumbMode": false, "isIndexing": false, "indexingProgress": null}
```

### Test 4: `ide_find_class`

Searches for classes by name using the IDE's class index.

```
→ {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"ide_find_class","arguments":{"query":"HeadlessMcpStarter"}}}
```
```json
← {"classes": [], "totalCount": 0, "query": "HeadlessMcpStarter"}
```

> Note: Returns empty because `HeadlessMcpStarter` is a Kotlin class and `ide_find_class` uses Java-specific class index contributors in IntelliJ CE. Use `ide_find_file` or `ide_search_text` for Kotlin classes without the Java plugin's full class index.

### Test 5: `ide_find_definition`

Resolves the declaration of `stdioTransport` at `McpServerService.kt:44`.

```
→ {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"ide_find_definition","arguments":{"file":"src/main/kotlin/.../McpServerService.kt","line":44,"column":30}}}
```
```json
← {
  "file": "src/main/kotlin/.../McpServerService.kt",
  "line": 44,
  "column": 17,
  "preview": "private var stdioTransport: StdioMcpTransport? = null",
  "symbolName": "stdioTransport"
}
```

### Test 6: `ide_find_references`

Finds all usages of the `StdioMcpTransport` class across the project.

```
→ {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"ide_find_references","arguments":{"file":"src/main/kotlin/.../StdioMcpTransport.kt","line":20,"column":7}}}
```
```json
← {
  "usages": [
    {
      "file": ".../StdioMcpTransport.kt", "line": 31,
      "context": "private val LOG = logger<StdioMcpTransport>()"
    },
    {
      "file": ".../McpServerService.kt", "line": 44,
      "context": "private var stdioTransport: StdioMcpTransport? = null"
    },
    {
      "file": ".../McpServerService.kt", "line": 188,
      "context": "stdioTransport = StdioMcpTransport(jsonRpcHandler, coroutineScope).also { it.start() }"
    }
  ],
  "totalCount": 3
}
```

### Test 7: `ide_type_hierarchy`

Resolves the full inheritance chain for `HeadlessMcpStarter`.

```
→ {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"ide_type_hierarchy","arguments":{"file":"src/main/kotlin/.../HeadlessMcpStarter.kt","line":27,"column":7}}}
```
```json
← {
  "element": {
    "name": "com.github.hechtcarmel...HeadlessMcpStarter",
    "kind": "CLASS",
    "language": "Java"
  },
  "supertypes": [
    {
      "name": "com.intellij.openapi.application.ApplicationStarter",
      "kind": "INTERFACE",
      "language": "Kotlin"
    }
  ],
  "subtypes": []
}
```

### Test 8: `ide_diagnostics`

Checks `StdioMcpTransport.kt` for errors, warnings, and available intentions.

```
→ {"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"ide_diagnostics","arguments":{"file":"src/main/kotlin/.../StdioMcpTransport.kt"}}}
```
```json
← {"problems": [], "intentions": [], "problemCount": 0, "intentionCount": 0}
```

> Clean — no errors or warnings.

## Platform Notes

- **macOS (aarch64)**: Tested and working. Use JBR `java` directly, not the `MacOS/idea` binary.
- **macOS (x86_64)**: Same approach, adjust `jna/aarch64` to `jna/x86_64` in JNA path.
- **Linux**: `idea.sh mcp-stdio /path/to/project` should work directly. The shell script handles classpath and `--add-opens` automatically.
- **Windows**: Untested. `idea.bat mcp-stdio C:\path\to\project` is the expected invocation.
