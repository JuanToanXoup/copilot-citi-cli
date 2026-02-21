# Claude Code: Agent Teams

## Overview

Agent Teams are an **experimental** multi-agent coordination system where a lead agent spawns teammates that run persistent loops, communicate via file-based mailboxes, and coordinate through a shared task list.

## Team Lifecycle

### 1. Lead Creates a Team (`TeamCreate` tool)

```javascript
async call({ team_name, description, agent_type }, context) {
    // Prevent multiple teams
    let existing = appState.teamContext?.teamName;
    if (existing) throw Error(`Already leading team "${existing}"`);

    // Sanitize and create
    let sanitizedName = sanitizeName(team_name);
    let leadAgentId = buildAgentId("team-lead", sanitizedName);  // "team-lead@my-team"
    let leadModel = resolveModel(appState.mainLoopModel);

    // Create team directory and config
    let teamDir = join(teamsBaseDir(), sanitizedName);
    let configPath = join(teamDir, "config.json");

    let config = {
        name: sanitizedName,
        description,
        createdAt: Date.now(),
        leadAgentId,
        leadSessionId: getSessionId(),
        members: [{
            agentId: leadAgentId,
            name: "team-lead",
            agentType: agent_type || "team-lead",
            model: leadModel,
            joinedAt: Date.now(),
            tmuxPaneId: "",
            cwd: getCwd(),
            subscriptions: [],
        }],
    };

    writeFileSync(configPath, JSON.stringify(config, null, 2));

    // Update app state
    setAppState(state => ({
        ...state,
        teamContext: {
            teamName: sanitizedName,
            teamFilePath: configPath,
            leadAgentId,
            teammates: {
                [leadAgentId]: {
                    name: "team-lead",
                    agentType: agent_type,
                    color: assignColor(leadAgentId),
                    cwd: getCwd(),
                    spawnedAt: Date.now(),
                },
            },
        },
    }));

    return { team_name: sanitizedName, team_file_path: configPath, lead_agent_id: leadAgentId };
}
```

### 2. Lead Spawns Teammates (via Task tool)

The lead agent uses the standard Task tool to spawn teammates. Each teammate gets injected with 7 team-specific tools.

### 3. Teammate Initialization (`spawnInProcessTeammate`)

```javascript
async function spawnInProcessTeammate({ name, teamName, prompt, color, planModeRequired, model }, context) {
    let agentId = buildAgentId(name, teamName);  // "researcher@my-team"
    let taskId = generateTaskId("in_process_teammate");

    let abortController = new AbortController();

    let teammateContext = {
        agentId,
        agentName: name,
        teamName,
        color,
        planModeRequired,
        parentSessionId: getSessionId(),
        isInProcess: true,
    };

    // Create task entry in app state
    let task = {
        type: "in_process_teammate",
        status: "running",
        identity: { agentId, agentName: name, teamName, color, planModeRequired, parentSessionId },
        prompt,
        model,
        abortController,
        awaitingPlanApproval: false,
        isIdle: false,
        shutdownRequested: false,
        pendingUserMessages: [],
        messages: [],
    };

    registerTask(task, setAppState);

    return { success: true, agentId, taskId, abortController, teammateContext };
}
```

## Teammate Main Loop (`Qqz`)

Each teammate runs a persistent outer loop that processes prompts and waits for messages:

```javascript
async function teammateMainLoop(config) {
    let { identity, taskId, prompt, agentDefinition, teammateContext,
          toolUseContext, abortController, model, systemPrompt } = config;

    // Build teammate's agent definition
    let agentDef = {
        agentType: identity.agentName,
        whenToUse: `In-process teammate: ${identity.agentName}`,
        getSystemPrompt: () => systemPrompt,
        // ALL tools + 7 team tools always injected:
        tools: agentDefinition?.tools
            ? [...new Set([...agentDefinition.tools,
                "SendMessage", "TeamCreate", "TeamDelete",
                "TaskCreate", "TaskGet", "TaskList", "TaskUpdate"])]
            : ["*"],
        source: "projectSettings",
        permissionMode: "default",
    };

    let conversationHistory = [];
    let currentPrompt = formatMessage("team-lead", prompt);  // Initial task from lead
    let shutdown = false;

    while (!abortController.signal.aborted && !shutdown) {

        // --- Phase 1: Execute work ---
        let workAbortController = new AbortController();
        updateTask(taskId, { status: "running", isIdle: false });

        // Run the standard agentic loop (UR → Ly)
        let messages = [];
        for await (let msg of UR({
            agentDefinition: agentDef,
            promptMessages: [userMessage(currentPrompt)],
            toolUseContext,
            isAsync: true,
            querySource: "agent:custom",
            override: { abortController: workAbortController },
            model,
            forkContextMessages: conversationHistory.length > 0 ? conversationHistory : undefined,
        })) {
            messages.push(msg);
            conversationHistory.push(msg);

            if (abortController.signal.aborted) break;   // Lifecycle abort
            if (workAbortController.signal.aborted) break; // Escape pressed
        }

        // --- Phase 2: Go idle ---
        let wasInterrupted = workAbortController.signal.aborted;

        updateTask(taskId, { isIdle: true });

        // Notify team lead that we're idle
        if (!alreadyIdle) {
            sendIdleNotification(identity.agentName, identity.color, identity.teamName, {
                idleReason: wasInterrupted ? "interrupted" : "available",
                summary: summarizeConversation(conversationHistory),
            });
        }

        // --- Phase 3: Wait for next message (poll loop) ---
        let event = await pollForMessages(identity, abortController, taskId, ...);

        switch (event.type) {
            case "shutdown_request":
                // Team lead or another teammate requested shutdown
                currentPrompt = formatMessage(event.request.from, event.originalMessage);
                break;

            case "new_message":
                if (event.from === "user") {
                    // Direct message from user
                    currentPrompt = event.message;
                } else {
                    // Message from another teammate or team-lead
                    currentPrompt = formatMessage(event.from, event.message, event.color);
                }
                break;

            case "aborted":
                shutdown = true;
                break;
        }
    }

    // Cleanup
    updateTask(taskId, { status: "completed", endTime: Date.now() });
}
```

## Message Polling (`pqz`)

Teammates poll every 500ms for new work:

```javascript
async function pollForMessages(identity, abortController, taskId, getAppState, setAppState) {
    let pollCount = 0;

    while (!abortController.signal.aborted) {
        // 1. Check for pending user messages (direct from lead/user)
        let task = (await getAppState()).tasks[taskId];
        if (task?.type === "in_process_teammate" && task.pendingUserMessages.length > 0) {
            let message = task.pendingUserMessages[0];
            // Remove from queue
            setAppState(state => ({
                ...state,
                tasks: {
                    ...state.tasks,
                    [taskId]: {
                        ...state.tasks[taskId],
                        pendingUserMessages: state.tasks[taskId].pendingUserMessages.slice(1),
                    },
                },
            }));
            return { type: "new_message", message, from: "user" };
        }

        if (pollCount > 0) await sleep(500);
        pollCount++;

        // 2. Check mailbox for messages from teammates
        let inbox = readMailbox(identity.agentName, identity.teamName);

        // 2a. Prioritize shutdown requests
        for (let i = 0; i < inbox.length; i++) {
            let msg = inbox[i];
            if (msg && !msg.read) {
                let shutdownReq = parseShutdownRequest(msg.text);
                if (shutdownReq) {
                    markMessageAsRead(identity.agentName, identity.teamName, i);
                    return { type: "shutdown_request", request: shutdownReq, originalMessage: msg.text };
                }
            }
        }

        // 2b. Check for regular messages (team-lead first, then anyone)
        let firstUnread = -1;
        for (let i = 0; i < inbox.length; i++) {
            if (inbox[i] && !inbox[i].read && inbox[i].from === "team-lead") {
                firstUnread = i;
                break;
            }
        }
        if (firstUnread === -1) {
            firstUnread = inbox.findIndex(msg => !msg.read);
        }

        if (firstUnread !== -1) {
            let msg = inbox[firstUnread];
            markMessageAsRead(identity.agentName, identity.teamName, firstUnread);
            return {
                type: "new_message",
                message: msg.text,
                from: msg.from,
                color: msg.color,
                summary: msg.summary,
            };
        }

        // 3. Check task list for unclaimed tasks
        let taskListUpdate = checkTaskList(identity.parentSessionId, identity.agentName);
        if (taskListUpdate) {
            return { type: "new_message", message: taskListUpdate, from: "task-list" };
        }
    }

    return { type: "aborted" };
}
```

## Team-Specific Tools

Every teammate gets these 7 tools injected:

| Tool | Purpose |
|---|---|
| **SendMessage** | Send messages to teammates or broadcast |
| **TeamCreate** | Create a new team (usually only lead) |
| **TeamDelete** | Remove team and clean up |
| **TaskCreate** | Add tasks to shared task list |
| **TaskGet** | Get full details of a task |
| **TaskList** | List all tasks and their states |
| **TaskUpdate** | Update task status, set owner, manage dependencies |

## Team System Prompt

Injected as `<system-reminder>` into every teammate:

```
# Team Coordination

You are a teammate in team "{teamName}".

**Your Identity:**
- Name: {agentName}

**Team Resources:**
- Team config: {teamConfigPath}
- Task list: {taskListPath}

**Team Leader:** The team lead's name is "team-lead".
Send updates and completion notifications to them.

Read the team config to discover your teammates' names.
Check the task list periodically.
Create new tasks when work should be divided.
Mark tasks resolved when complete.

**IMPORTANT:** Always refer to teammates by their NAME
(e.g., "team-lead", "analyzer", "researcher"), never by UUID.
```

## Two Backends

| Backend | How it works |
|---|---|
| **in-process** | Same Node.js process, poll-based (500ms), shared AppState |
| **tmux** | Separate terminal panes, separate Claude Code processes, file-based coordination |

The in-process backend is the primary one. Tmux is for visual separation (iTerm2 split panes).

## Agent IDs

```javascript
// Build: "agentName@teamName"
function buildAgentId(name, teamName) {
    return `${name}@${teamName}`;
}

// Parse: { agentName, teamName }
function parseAgentId(agentId) {
    let idx = agentId.indexOf("@");
    if (idx === -1) return null;
    return {
        agentName: agentId.slice(0, idx),
        teamName: agentId.slice(idx + 1),
    };
}
```

## Source Location

Extracted from `/tmp/claude-code-npm/package/cli.js` (minified, v2.1.50):
- Team creation: offset ~10339800
- Teammate spawn (`AZ6`): offset ~10880500
- Teammate main loop (`Qqz`): offset ~10200000
- Poll loop (`pqz`): offset ~10197000
- Team system prompt: offset ~10401640
- Team tools registered: offset ~10200287
