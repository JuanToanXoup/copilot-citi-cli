# Claude Code: Subagent Definitions

## Agent Definition Shape

Every agent (built-in or custom) is an object with this shape:

```typescript
interface AgentDefinition {
    agentType: string;              // "Explore", "Plan", "Bash", etc.
    whenToUse: string;              // Description the main agent reads to decide when to delegate
    tools: string[] | ["*"];        // Allowed tools, or "*" for all
    disallowedTools?: string[];     // Tools explicitly denied (overrides tools)
    source: "built-in" | "plugin" | "projectSettings" | "userSettings";
    baseDir?: string;
    model?: "haiku" | "sonnet" | "opus" | "inherit";
    color?: string;                 // Color label for UI
    forkContext?: boolean;          // If true, subagent sees parent's full conversation
    permissionMode?: string;        // "default", "dontAsk", "plan", "bubble", etc.
    background?: boolean;           // Run asynchronously
    memory?: "user" | "project" | "local";
    isolation?: "worktree";         // Git worktree isolation
    skills?: string[];              // Preloaded skills
    getSystemPrompt: () => string;  // The subagent's system prompt
    criticalSystemReminder_EXPERIMENTAL?: string;
}
```

## Built-in Agents

### Bash
```javascript
{
    agentType: "Bash",
    whenToUse: "Command execution specialist for running bash commands. Use this for git operations, command execution, and other terminal tasks.",
    tools: ["Bash"],
    model: "inherit",
    source: "built-in",
}
```

### general-purpose
```javascript
{
    agentType: "general-purpose",
    whenToUse: "General-purpose agent for researching complex questions, searching for code, and executing multi-step tasks. When you are searching for a keyword or file and are not confident that you will find the right match in the first few tries use this agent to perform the search for you.",
    tools: ["*"],  // all tools
    model: "inherit",
    source: "built-in",
    getSystemPrompt: () => `You are an agent for Claude Code, Anthropic's official CLI for Claude. Given the user's message, you should use the tools available to complete the task. Do what has been asked; nothing more, nothing less. When you complete the task simply respond with a detailed writeup.

Your strengths:
- Searching for code, configurations, and patterns across large codebases
- Analyzing multiple files to understand system architecture...`
}
```

### Explore
```javascript
{
    agentType: "Explore",
    whenToUse: "Fast agent specialized for exploring codebases. Use this when you need to quickly find files by patterns, search code for keywords, or answer questions about the codebase. Specify the desired thoroughness level: quick, medium, or very thorough.",
    disallowedTools: ["Task", "ExitPlanMode", "Edit", "Write", "NotebookEdit"],
    model: "haiku",
    source: "built-in",
    criticalSystemReminder_EXPERIMENTAL: "CRITICAL: This is a READ-ONLY task. You CANNOT edit, write, or create files.",
}
```

### Plan
```javascript
{
    agentType: "Plan",
    whenToUse: "Software architect agent for designing implementation plans. Use this when you need to plan the implementation strategy for a task. Returns step-by-step plans, identifies critical files, and considers architectural trade-offs.",
    disallowedTools: ["Task", "ExitPlanMode", "Edit", "Write", "NotebookEdit"],
    tools: /* same as Explore */,
    model: "inherit",
    source: "built-in",
    criticalSystemReminder_EXPERIMENTAL: "CRITICAL: This is a READ-ONLY task. You CANNOT edit, write, or create files.",
}
```

### statusline-setup
```javascript
{
    agentType: "statusline-setup",
    whenToUse: "Use this agent to configure the user's Claude Code status line setting.",
    tools: ["Read", "Edit"],
    model: "sonnet",
    color: "orange",
    source: "built-in",
}
```

### claude-code-guide
```javascript
{
    agentType: "claude-code-guide",
    whenToUse: 'Use this agent when the user asks questions ("Can Claude...", "Does Claude...", "How do I...") about: (1) Claude Code (the CLI tool) - features, hooks, slash commands, MCP servers, settings, IDE integrations, keyboard shortcuts; (2) Claude Agent SDK - building custom agents; (3) Claude API - API usage, tool use, Anthropic SDK usage.',
    tools: ["Glob", "Grep", "Read", "WebFetch", "WebSearch"],
    model: "haiku",
    permissionMode: "dontAsk",
    source: "built-in",
}
```

## Summary Table

| Agent | Model | Tools | Read-Only |
|---|---|---|---|
| **Bash** | inherit | Bash only | No |
| **general-purpose** | inherit | All (`*`) | No |
| **Explore** | haiku | All except Task, ExitPlanMode, Edit, Write, NotebookEdit | Yes |
| **Plan** | inherit | All except Task, ExitPlanMode, Edit, Write, NotebookEdit | Yes |
| **statusline-setup** | sonnet | Read, Edit | No |
| **claude-code-guide** | haiku | Glob, Grep, Read, WebFetch, WebSearch | Yes |

## Task Tool Input Schema

The Task tool is how the main agent spawns subagents:

```javascript
{
    description:        string,   // "A short (3-5 word) description"
    prompt:             string,   // "The task for the agent to perform"
    subagent_type:      string,   // Must match an agentType
    model?:             "sonnet" | "opus" | "haiku",  // Override agent default
    resume?:            string,   // Agent ID to continue a previous session
    run_in_background?: boolean,  // Run asynchronously
    max_turns?:         number,   // Cap on API round-trips
}
```

## Custom Agents (`.claude/agents/*.md`)

Markdown files with YAML frontmatter, loaded from:
- `.claude/agents/` (project-level)
- `~/.claude/agents/` (user-level)
- Plugin `agentsPath` directories

```markdown
---
name: my-reviewer
description: "Code review specialist"
tools: [Read, Glob, Grep]
model: sonnet
forkContext: true
background: true
memory: project
isolation: worktree
skills: [commit]
permissionMode: dontAsk
---

You are a code reviewer. Review the code and provide feedback...
```

### Frontmatter Parser (from source)

```javascript
function parseFrontmatter(fileContent, filePath) {
    let frontmatterRegex = /^---\s*\n([\s\S]*?)---\s*\n?/;
    let match = fileContent.match(frontmatterRegex);
    if (!match) return { frontmatter: {}, content: fileContent };

    let yamlStr = match[1] || "";
    let body = fileContent.slice(match[0].length);
    let parsed = {};

    try {
        parsed = yamlParse(yamlStr);
    } catch {
        // Fallback: try parsing after sanitization
    }

    return { frontmatter: parsed, content: body };
}
```

### Agent Materializer (N54, from source)

```javascript
function N54(filePath, pluginName, pathParts, source, seenPaths) {
    let content = readFileSync(filePath, "utf-8");
    let { frontmatter, content: body } = parseFrontmatter(content, filePath);

    let name = frontmatter.name || basename(filePath).replace(/\.md$/, "");
    let agentType = [pluginName, ...pathParts, name].join(":");
    let description = frontmatter.description || frontmatter["when-to-use"] || `Agent from ${pluginName}`;
    let tools = parseTools(frontmatter.tools);
    let model = frontmatter.model;
    let forkContext = frontmatter.forkContext === "true";
    let background = frontmatter.background === "true" || frontmatter.background === true;
    let memory = frontmatter.memory;  // "user" | "project" | "local"
    let isolation = frontmatter.isolation === "worktree" ? "worktree" : undefined;

    // forkContext agents must use model: inherit
    if (forkContext && model !== "inherit") {
        model = "inherit"; // Override
    }

    return {
        agentType,
        whenToUse: description,
        tools,
        getSystemPrompt: () => body,  // Markdown body becomes the system prompt
        source: "plugin",
        model,
        ...forkContext ? { forkContext } : {},
        ...background ? { background } : {},
        ...memory ? { memory } : {},
        ...isolation ? { isolation } : {},
    };
}
```

## How the Main Agent Chooses

The Task tool's description is dynamically built by iterating all available agents and listing their `whenToUse` strings and tool lists. The main agent reads this and picks the right `subagent_type`. No routing logic — entirely prompt-driven.

## Source Location

Extracted from `/tmp/claude-code-npm/package/cli.js` (minified, v2.1.50):
- Bash agent: offset ~5500384
- general-purpose: offset ~5500690
- Explore: offset ~5512055
- Plan: offset ~5514832
- statusline-setup: offset ~5502476
- claude-code-guide: offset ~5519070
- Custom agent loader (N54): offset ~5495776
- Task tool schema (MKz): offset ~5547000
