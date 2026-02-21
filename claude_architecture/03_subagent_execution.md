# Claude Code: Subagent Execution

## Overview

When the main agent calls the Task tool, it spawns a subagent by invoking `UR()` — which runs the **exact same agentic loop** (`Ly`) with isolated context.

## Subagent Runner (UR, deobfuscated)

```javascript
async function* UR({
    agentDefinition,
    promptMessages,
    toolUseContext,
    canUseTool,
    isAsync,
    canShowPermissionPrompts,
    forkContextMessages,
    querySource,       // "agent:custom"
    override,
    model,
    maxTurns,
    preserveToolUseResults,
    availableTools,
    allowedTools,
}) {
    // 1. Resolve model
    //    Priority: env var > explicit override > agent definition > inherit from parent
    let resolvedModel = resolveSubagentModel(
        agentDefinition.model,
        toolUseContext.options.mainLoopModel,
        model,
        permissionMode,
        agentDefinition.agentType
    );

    // 2. Generate unique agent ID
    let agentId = override?.agentId || generateAgentId();

    // 3. Build messages
    //    - If forkContext: include parent's conversation history
    //    - Always include the prompt messages
    let messages = [
        ...(forkContextMessages ? cloneMessages(forkContextMessages) : []),
        ...promptMessages,
    ];

    // 4. Build system prompt specific to the agent
    let systemPrompt = override?.systemPrompt ||
        buildAgentSystemPrompt(agentDefinition, toolUseContext, resolvedModel);

    // 5. Resolve tools
    //    - Start with agent's allowed tools
    //    - Apply disallowedTools filter
    //    - Resolve against available tools
    let resolvedTools = resolveAgentTools(agentDefinition, availableTools, isAsync);

    // 6. Set up permission mode (may differ from parent)
    let getAppState = async () => {
        let state = await toolUseContext.getAppState();
        let permContext = state.toolPermissionContext;

        if (agentDefinition.permissionMode &&
            permContext.mode !== "bypassPermissions" &&
            permContext.mode !== "acceptEdits") {
            permContext = { ...permContext, mode: agentDefinition.permissionMode };
        }

        // Async agents avoid permission prompts by default
        if (isAsync && !canShowPermissionPrompts) {
            permContext = { ...permContext, shouldAvoidPermissionPrompts: true };
        }

        // Apply allowed tools if specified
        if (allowedTools !== undefined) {
            permContext = {
                ...permContext,
                alwaysAllowRules: { session: [...allowedTools] },
            };
        }

        return { ...state, toolPermissionContext: permContext };
    };

    // 7. Set up abort controller (own or inherited)
    let abortController = override?.abortController ||
        (isAsync ? new AbortController() : toolUseContext.abortController);

    // 8. Run SubagentStart hooks
    for await (let hookResult of runSubagentStartHooks(agentId, agentDefinition, abortController.signal)) {
        if (hookResult.additionalContexts) {
            messages.push(...hookResult.additionalContexts);
        }
    }

    // 9. Register agent-specific hooks from definition
    if (agentDefinition.hooks) {
        registerSessionHooks(agentId, agentDefinition.hooks);
    }

    // 10. Preload skills
    for (let skillName of agentDefinition.skills || []) {
        let skill = findSkill(skillName);
        if (skill) {
            let prompt = await skill.getPromptForCommand("", toolUseContext);
            messages.push(systemMessage({ content: prompt }));
        }
    }

    // 11. Set up MCP clients for this agent
    let { clients, tools: mcpTools, cleanup } = await setupMcpClients(agentDefinition);
    let allTools = deduplicateTools([...resolvedTools, ...mcpTools]);

    // 12. Build the tool use context for this subagent
    let subagentToolUseContext = {
        ...toolUseContext,
        options: {
            ...toolUseContext.options,
            tools: allTools,
            mainLoopModel: resolvedModel,
        },
        getAppState,
        abortController,
        agentId,
    };

    // 13. Run the SAME agentic loop (Ly) with agent-specific context
    try {
        for await (let message of Ly({
            messages,
            systemPrompt,
            tools: allTools,
            maxTurns,
            querySource: "agent:custom",
            toolUseContext: subagentToolUseContext,
            canUseTool,
            model: resolvedModel,
        })) {
            yield message; // Stream results back to parent
        }
    } finally {
        // Clean up MCP clients
        await cleanup();
    }
}
```

## Model Resolution

```javascript
function resolveSubagentModel(agentModel, mainLoopModel, overrideModel, permissionMode) {
    // Environment variable override (testing/debugging)
    if (process.env.CLAUDE_CODE_SUBAGENT_MODEL)
        return resolveModel(process.env.CLAUDE_CODE_SUBAGENT_MODEL);

    // Explicit override from Task tool call
    if (overrideModel) return resolveModel(overrideModel);

    // Agent definition's model
    let model = agentModel ?? "inherit";
    if (model === "inherit")
        return getSessionModel({ permissionMode, mainLoopModel });

    return resolveModel(model);
}

// Available model choices for subagents:
// "sonnet", "opus", "haiku", "inherit"
```

## Context Isolation

| Scenario | Parent Messages | Own Messages |
|---|---|---|
| `forkContext: false` (default) | Not visible | Fresh context |
| `forkContext: true` | Cloned into subagent | Added after parent messages |

When `forkContext` is true, the subagent sees the full parent conversation history. This is useful for agents that need to understand what the user asked about. However, `forkContext` agents **must** use `model: inherit` to avoid context length mismatches.

## Tool Resolution

```javascript
function resolveAgentTools(agentDefinition, availableTools, isAsync) {
    let { tools, disallowedTools } = agentDefinition;

    // Start with specified tools or all available
    let resolved;
    if (tools && tools.length > 0 && tools[0] !== "*") {
        resolved = availableTools.filter(t => tools.includes(t.name));
    } else {
        resolved = [...availableTools];
    }

    // Remove disallowed tools
    if (disallowedTools && disallowedTools.length > 0) {
        let blocked = new Set(disallowedTools);
        resolved = resolved.filter(t => !blocked.has(t.name));
    }

    return resolved;
}
```

## Subagent Lifecycle

```
1. Main agent calls Task tool with { subagent_type, prompt, ... }
2. Task tool invokes UR() with the agent definition
3. UR() resolves model, tools, permissions, system prompt
4. UR() runs Ly() — the same while(true) loop as the main agent
5. Subagent calls tools, thinks, produces output
6. Ly() exits when model stops calling tools (or maxTurns hit)
7. UR() yields final results back to Task tool
8. Task tool returns result as tool_result to main loop
9. Main loop continues with subagent result in context
```

## Key Constraints

- **No nesting**: Subagents cannot spawn other subagents (Task tool is excluded from subagent tool sets when running inside a subagent — checked via `isInProcess()`)
- **No cross-subagent communication**: Subagents don't know about each other
- **Result is text**: The subagent's output is flattened to text for the parent's tool_result
- **Resumable**: Each subagent's conversation is saved to `~/.claude/projects/{project}/{sessionId}/subagents/agent-{agentId}.jsonl` and can be resumed via the `resume` parameter

## Source Location

Extracted from `/tmp/claude-code-npm/package/cli.js` (minified, v2.1.50):
- Subagent runner `UR`: around offset ~10258000
- Model resolution `Fz1`: around offset ~5507000
- Tool resolution `Tl`: referenced in UR body
