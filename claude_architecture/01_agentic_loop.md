# Claude Code: Core Agentic Loop

## Overview

The entire architecture runs through a single `async function*` generator (`Ly` in minified source). It's a `while(true)` loop that calls the Claude API, checks for tool use, executes tools, and feeds results back.

## The Loop (deobfuscated from source)

```javascript
async function* Ly({ messages, systemPrompt, maxTurns, ... }) {
    let turnCount = 1;

    while (true) {
        // 1. Auto-compact messages if context is getting large
        let compactedMessages = await autoCompact(messages, ...);

        // 2. Stream API call
        for await (let event of streamingAPICall({
            messages: compactedMessages,
            systemPrompt,
            tools,
            model,
            thinkingConfig,
            ...
        })) {
            // Collect assistant response chunks
            if (event.type === "assistant") {
                assistantMessages.push(event);

                // Streaming tool execution: start tools as they appear
                if (streamingToolExecution) {
                    let toolUseBlocks = event.message.content
                        .filter(b => b.type === "tool_use");
                    for (let block of toolUseBlocks) {
                        streamingExecutor.addTool(block, event);
                    }
                }
            }
            yield event; // stream to caller
        }

        // 3. Check if aborted
        if (abortController.signal.aborted) {
            yield { type: "interrupted" };
            return;
        }

        // 4. Extract tool_use blocks from response
        let toolUseBlocks = assistantMessages.flatMap(m =>
            m.message.content.filter(b => b.type === "tool_use")
        );

        // 5. If no tool calls → run Stop hooks → DONE
        if (!toolUseBlocks.length) {
            let stopResult = yield* runStopHooks(...);
            if (stopResult.preventContinuation) return;
            if (stopResult.blockingErrors.length > 0) {
                // Feed errors back and continue
                messages = [...messages, ...assistantMsgs, ...stopResult.blockingErrors];
                continue;
            }
            return; // Model is done
        }

        // 6. Execute tools
        //    - Sequential (Fs9): tools run one at a time
        //    - Parallel (ps9): concurrency-safe tools run simultaneously
        let toolResults = [];
        for await (let result of executeTool(toolUseBlocks, ...)) {
            yield result.message; // stream tool results
            toolResults.push(result);
        }

        // 7. Run post-tool hooks and attachments
        for await (let attachment of runAttachments(...)) {
            yield attachment;
            toolResults.push(attachment);
        }

        // 8. Check maxTurns limit
        let nextTurn = turnCount + 1;
        if (maxTurns && nextTurn > maxTurns) {
            yield { type: "max_turns_reached", maxTurns, turnCount: nextTurn };
            return;
        }

        // 9. Merge everything back and loop
        // "query_recursive_call"
        messages = [...messages, ...assistantMessages, ...toolResults];
        turnCount = nextTurn;
        continue; // ← back to while(true)
    }
}
```

## Key Details

- **Termination**: The model decides when it's done by not emitting `tool_use` blocks. No external evaluator.
- **`stop_reason`**: `"end_turn"` = model is done, `"tool_use"` = model wants to call tools.
- **Max output tokens recovery**: If response is cut off (`max_output_tokens`), injects a "continue from where you left off" message and loops back (up to 3 retries).
- **Auto-compaction**: When messages exceed context limits, automatically summarizes older messages to free space.
- **Streaming tool execution**: Tools can start executing while the model is still streaming its response (when tools are concurrency-safe).
- **Model fallback**: If the primary model fails with a specific error, falls back to a secondary model.
- **Stop hooks**: User-defined hooks that run when the model stops. Can prevent continuation or inject blocking errors.

## Tool Execution Functions

```javascript
// Sequential execution — tools run one at a time, respecting order
async function* Fs9(toolUseBlocks, assistantMessages, context, appState) {
    for (let tool of toolUseBlocks) {
        appState.setInProgressToolUseIDs(ids => new Set([...ids, tool.id]));
        for await (let result of executeSingleTool(tool, ...)) {
            if (result.contextModifier)
                appState = result.contextModifier.modifyContext(appState);
            yield { message: result.message, newContext: appState };
        }
        appState.removeInProgressToolUseID(tool.id);
    }
}

// Parallel execution — concurrency-safe tools run simultaneously
async function* ps9(toolUseBlocks, assistantMessages, context, appState) {
    yield* mergeAsyncGenerators(
        toolUseBlocks.map(async function*(tool) {
            appState.setInProgressToolUseIDs(ids => new Set([...ids, tool.id]));
            yield* executeSingleTool(tool, ...);
            appState.removeInProgressToolUseID(tool.id);
        }),
        concurrencyLimit()
    );
}
```

## Single Tool Executor (HF6)

```javascript
async function* HF6(toolUseBlock, assistantMessage, context, appState) {
    let toolName = toolUseBlock.name;
    let tool = findTool(appState.options.tools, toolName);

    if (!tool) {
        yield { message: toolResult(toolUseBlock.id, "Error: No such tool", true) };
        return;
    }

    if (appState.abortController.signal.aborted) {
        yield { message: toolResult(toolUseBlock.id, "Cancelled", true) };
        return;
    }

    // Run pre-tool hooks
    yield* executePreToolHooks(toolName, toolUseBlock.id, toolUseBlock.input, ...);

    // Execute the actual tool
    let result = await tool.run(toolUseBlock.input);

    // Run post-tool hooks
    yield* executePostToolHooks(toolName, toolUseBlock.id, result, ...);

    yield { message: toolResult(toolUseBlock.id, result) };
}
```

## Source Location

Extracted from `/tmp/claude-code-npm/package/cli.js` (minified, v2.1.50):
- Main loop `Ly`: around offset ~10155000
- Tool executors `Fs9`/`ps9`: around offset ~5970000
- Single tool executor `HF6`: around offset ~5968000
- Stop hook processor `v_q`: around offset ~10148000
