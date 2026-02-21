# Claude Code: Mailbox System

## Overview

Teammates communicate through a **file-based mailbox** system. Each agent has a JSON inbox file on disk. Messages are written with file locks to prevent corruption.

## File Layout

```
~/.claude/teams/
  {team-name}/
    config.json              # Team config (members, lead, etc.)
    inboxes/
      {agent-name}.json      # Each agent's inbox file
      {agent-name}.json.lock # Lock file during write
```

## Path Resolution

```javascript
// Base directory for all team data
function teamsBaseDir() {
    return join(getProjectDir(), "teams");
}

// Team directory
function teamDir(teamName) {
    return join(teamsBaseDir(), sanitizeName(teamName));
}

// Inbox file path for an agent
function getInboxPath(agentName, teamName) {
    let team = teamName || getTeamName() || "default";
    let teamDir = sanitizeName(team);
    let agentDir = sanitizeName(agentName);
    let inboxesDir = join(teamsBaseDir(), teamDir, "inboxes");
    return join(inboxesDir, `${agentDir}.json`);
}

// Sanitize names for filesystem safety
function sanitizeName(name) {
    return name.replace(/[^a-zA-Z0-9_-]/g, "-");
}
```

## Message Format

Each message in the inbox JSON array:

```typescript
interface MailboxMessage {
    from: string;           // Sender agent name
    text: string;           // Message content (can be JSON-encoded for structured messages)
    timestamp: string;      // ISO timestamp
    color?: string;         // Sender's color label
    summary?: string;       // Optional summary
    read: boolean;          // Whether the recipient has read this
}
```

## Core Operations (from source)

### Read Mailbox (`Sc`)

```javascript
function readMailbox(agentName, teamName) {
    let path = getInboxPath(agentName, teamName);

    if (!fileExists(path)) return [];

    try {
        let content = readFileSync(path, "utf-8");
        let messages = JSON.parse(content);
        return messages;
    } catch (err) {
        return [];
    }
}
```

### Read Unread Messages (`f96`)

```javascript
function readUnreadMessages(agentName, teamName) {
    let all = readMailbox(agentName, teamName);
    return all.filter(msg => !msg.read);
}
```

### Write to Mailbox (`t5`)

Uses `proper-lockfile` for file locking to prevent concurrent write corruption:

```javascript
function writeToMailbox(recipientName, message, teamName) {
    ensureInboxDir(teamName);

    let path = getInboxPath(recipientName, teamName);
    let lockPath = `${path}.lock`;

    // Create inbox file if it doesn't exist
    if (!fileExists(path)) {
        writeFileSync(path, "[]", "utf-8");
    }

    let unlock;
    try {
        // Acquire file lock
        unlock = lockfile.lockSync(path, { lockfilePath: lockPath });

        // Read current messages
        let existing = readMailbox(recipientName, teamName);

        // Append new message
        let newMessage = { ...message, read: false };
        existing.push(newMessage);

        // Write back
        writeFileSync(path, JSON.stringify(existing, null, 2), "utf-8");
    } catch (err) {
        // Log error but don't crash
    } finally {
        if (unlock) unlock();
    }
}
```

### Mark Message as Read (`yQ6`)

```javascript
function markMessageAsReadByIndex(agentName, teamName, index) {
    let path = getInboxPath(agentName, teamName);

    if (!fileExists(path)) return;

    let lockPath = `${path}.lock`;
    let unlock;

    try {
        unlock = lockfile.lockSync(path, { lockfilePath: lockPath });

        let messages = readMailbox(agentName, teamName);

        if (index < 0 || index >= messages.length) return;

        let msg = messages[index];
        if (!msg || msg.read) return;

        messages[index] = { ...msg, read: true };
        writeFileSync(path, JSON.stringify(messages, null, 2), "utf-8");
    } catch (err) {
        // Log error
    } finally {
        if (unlock) unlock();
    }
}
```

### Mark All as Read (`RQ6`)

```javascript
function markAllMessagesAsRead(agentName, teamName) {
    let path = getInboxPath(agentName, teamName);

    if (!fileExists(path)) return;

    let lockPath = `${path}.lock`;
    let unlock;

    try {
        unlock = lockfile.lockSync(path, { lockfilePath: lockPath });

        let messages = readMailbox(agentName, teamName);
        if (messages.length === 0) return;

        let unreadCount = messages.filter(m => !m.read).length;

        let updated = messages.map(m => ({ ...m, read: true }));
        writeFileSync(path, JSON.stringify(updated, null, 2), "utf-8");

        // Verify write
        let verify = JSON.parse(readFileSync(path, "utf-8"));
        let stillUnread = verify.filter(m => !m.read).length;
    } catch (err) {
        // Log error
    } finally {
        if (unlock) unlock();
    }
}
```

### Clear Inbox (`IPY`)

```javascript
function clearInbox(agentName, teamName) {
    let path = getInboxPath(agentName, teamName);
    if (!fileExists(path)) return;

    try {
        writeFileSync(path, "[]", "utf-8");
    } catch (err) {
        // Log error
    }
}
```

## Structured Message Types

The `text` field can contain JSON-encoded structured messages:

### Idle Notification
```javascript
function createIdleNotification(agentName, options) {
    return {
        type: "idle_notification",
        from: agentName,
        timestamp: new Date().toISOString(),
        idleReason: options?.idleReason,        // "available" | "interrupted" | "failed"
        summary: options?.summary,
        completedTaskId: options?.completedTaskId,
        completedStatus: options?.completedStatus,
        failureReason: options?.failureReason,
    };
}
```

### Shutdown Request
```javascript
function createShutdownRequest({ requestId, from, reason }) {
    return {
        type: "shutdown_request",
        requestId,
        from,
        reason,
        timestamp: new Date().toISOString(),
    };
}
```

### Shutdown Response (Approved/Rejected)
```javascript
function createShutdownApproved({ requestId, from, paneId, backendType }) {
    return { type: "shutdown_approved", requestId, from, timestamp: new Date().toISOString(), paneId, backendType };
}

function createShutdownRejected({ requestId, from, reason }) {
    return { type: "shutdown_rejected", requestId, from, reason, timestamp: new Date().toISOString() };
}
```

### Permission Request/Response
```javascript
function createPermissionRequest(data) {
    return {
        type: "permission_request",
        request_id: data.request_id,
        agent_id: data.agent_id,
        tool_name: data.tool_name,
        tool_use_id: data.tool_use_id,
        description: data.description,
        input: data.input,
        permission_suggestions: data.permission_suggestions || [],
    };
}
```

## Message Formatting for the Model

When messages are fed back to the model as prompts, they're wrapped in XML:

```javascript
function formatTeammateMessage(from, text, color, summary) {
    let colorAttr = color ? ` color="${color}"` : "";
    let summaryAttr = summary ? ` summary="${summary}"` : "";
    return `<teammate-message teammate_id="${from}"${colorAttr}${summaryAttr}>
${text}
</teammate-message>`;
}
```

## SendMessage Tool

The `SendMessage` tool supports multiple operations:

```javascript
{
    name: "SendMessage",
    async call(input, context) {
        switch (input.type) {
            case "message":          // Direct message to one teammate
                return sendDirectMessage(input, context);
            case "broadcast":        // Message to all teammates
                return broadcastMessage(input, context);
            case "shutdown_request":  // Request teammate shutdown
                return sendShutdownRequest(input, context);
            case "shutdown_response": // Approve/reject shutdown
                if (input.approve) return approveShutdown(input, context);
                return rejectShutdown(input);
            case "plan_approval_response": // Approve/reject plan
                if (input.approve) return approvePlan(input, context);
                return rejectPlan(input, context);
        }
    },
}
```

## Key Design Decisions

- **File-based**: No in-memory message bus. Files on disk. Simple and debuggable.
- **Locking**: Uses `proper-lockfile` (`.lock` sidecar files) for write safety.
- **Polling**: 500ms interval. No file watchers or push notifications.
- **No message ordering guarantees**: Messages from team-lead are prioritized, but otherwise first-unread wins.
- **JSON arrays**: Each inbox is a JSON array of messages. The entire file is read/written on each operation.
- **Read tracking**: Each message has a `read` boolean. Mark-as-read is a separate locked write.

## Source Location

Extracted from `/tmp/claude-code-npm/package/cli.js` (minified, v2.1.50):
- `readMailbox` (Sc): offset ~7150411
- `writeToMailbox` (t5): offset ~7150700
- `markMessageAsReadByIndex` (yQ6): offset ~7151100
- `markAllMessagesAsRead` (RQ6): offset ~7151800
- `clearInbox` (IPY): offset ~7152500
- `getInboxPath` (G96): offset ~7150200
- `ensureInboxDir` (hPY): offset ~7150300
