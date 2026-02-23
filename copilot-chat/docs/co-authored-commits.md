# Co-Authored Commits for Agent-Generated Changes

## Overview

When the Agent tab generates code changes (via the lead agent or subagents), the plugin should automatically attribute co-authorship in the resulting git commit. This gives visibility into which commits involved AI assistance and follows GitHub's established `Co-authored-by` trailer convention.

## Industry Comparison

Different AI coding tools handle commit attribution in fundamentally different ways. There are two main approaches: **AI as commit author** and **AI as co-author**.

### GitHub Copilot Coding Agent

**Approach: AI as primary commit author**

GitHub's coding agent (`copilot-swe-agent`) commits **as the author**, not a co-author. The human who assigned the task is listed in a `Co-authored-by` trailer — the inverse of what most other tools do.

```
Fix authentication bug

Co-authored-by: John Doe <john@example.com>
```

- **Author**: `GitHub Copilot <noreply@github.com>`
- **Co-author**: The human who requested the change
- **GitHub profile**: Yes — renders under the official Copilot identity
- **Contributor graph**: Yes — Copilot appears as a contributor
- **git blame**: Points to Copilot, not the human
- **User control**: No built-in way to flip author/co-author roles; users have raised this as a pain point when squash-merging PRs

> Source: [GitHub Community Discussion #179983](https://github.com/orgs/community/discussions/179983)

### Devin (Cognition)

**Approach: AI as primary commit author via dedicated org account**

Devin takes the strongest ownership approach. Organizations create a dedicated GitHub account (e.g. `devin@company.com`) that Devin uses as its commit identity. When Devin writes code, it is the commit author. When a human applies a Devin-suggested change, the human is the author.

- **Author**: Dedicated org account (e.g. `devin@company.com`)
- **GitHub profile**: Yes — real account with avatar and profile
- **Contributor graph**: Yes
- **git blame**: Points to Devin's account
- **GPG signing**: Optional — Devin can sign commits with a key tied to its account
- **User control**: Organization controls the account setup

> Source: [Devin GitHub Integration Docs](https://docs.devin.ai/integrations/gh)

### Claude Code (Anthropic)

**Approach: AI as co-author**

Claude Code appends a `Co-authored-by` trailer to commits it helps create. The human remains the commit author.

```
Fix authentication bug

Co-Authored-By: Claude <noreply@anthropic.com>
```

- **Author**: The human developer
- **Co-author**: `Claude <noreply@anthropic.com>`
- **GitHub profile**: No — `noreply@anthropic.com` is not a registered GitHub account
- **Contributor graph**: No
- **git blame**: Points to the human
- **User control**: Can be disabled via `includeCoAuthoredBy: false` in settings.json
- **Controversy**: Some developers find the automatic attribution annoying or conflicting with strict commit message conventions

> Sources: [Claude Code Settings Docs](https://code.claude.com/docs/en/settings), [GitHub Issue #5458](https://github.com/anthropics/claude-code/issues/5458)

### Cursor

**Approach: AI as co-author (automatic)**

Cursor automatically appends a co-author trailer when commits are made through its chat interface.

```
Fix authentication bug

Co-authored-by: Cursor <cursoragent@cursor.com>
```

- **Author**: The human developer
- **Co-author**: `Cursor <cursoragent@cursor.com>`
- **GitHub profile**: No — unregistered email
- **Contributor graph**: No
- **git blame**: Points to the human
- **User control**: Can be disabled via Settings > Agents > Attribution, but only in the IDE — CLI and cloud agents have no toggle. The setting can re-enable itself after auto-updates.
- **Controversy**: Users have reported the co-author being added without consent and difficulty fully disabling it. Some use git hooks to strip the trailer.

> Source: [Cursor Community Forum](https://forum.cursor.com/t/co-author-added-without-consent-and-cant-be-turned-off/150096)

### Aider

**Approach: AI as co-author (opt-in)**

Aider adds a co-author trailer only when explicitly enabled with the `--attribute-co-authored-by` flag. Disabled by default.

```
Fix authentication bug

Co-authored-by: aider (gpt-4) <noreply@aider.chat>
```

- **Author**: The human developer
- **Co-author**: `aider (model-name) <noreply@aider.chat>`
- **GitHub profile**: No — `noreply@aider.chat` is unregistered, so Aider does not appear in the Contributors section
- **Contributor graph**: No
- **git blame**: Points to the human
- **User control**: Opt-in via CLI flag or `.aider.conf.yml`
- **Notable**: Includes the model name (e.g. `gpt-4`) in the co-author name, providing traceability of which LLM was used. They are exploring creating a real GitHub bot account to fix the contributor visibility gap.

> Source: [Aider Issue #4226](https://github.com/aider-ai/aider/issues/4226)

### Amazon Q Developer

**Approach: No automatic attribution**

Amazon Q Developer (formerly CodeWhisperer) does not automatically add co-author trailers. Organizations can define commit message rules via `.amazonq/rules` to include attribution, but there is no built-in mechanism.

- **Author**: The human developer
- **Co-author**: None by default
- **User control**: Custom rules can template commit messages to include agent actions

> Source: [Mastering Amazon Q Developer with Rules](https://aws.amazon.com/blogs/devops/mastering-amazon-q-developer-with-rules/)

### Windsurf (Codeium)

**Approach: No documented attribution**

Windsurf/Cascade does not appear to have a documented co-author attribution mechanism for git commits. No public documentation or community discussion confirms automatic trailer injection.

### Summary Table

| Tool | Who is author? | Attribution method | GitHub profile link | Contributor graph | git blame | Default |
|------|---------------|-------------------|--------------------|--------------------|-----------|---------|
| **GitHub Copilot Agent** | Copilot (bot) | Human as co-author | Yes | Yes (Copilot) | Copilot | On |
| **Devin** | Devin (org account) | Owns the commit | Yes | Yes (Devin) | Devin | On |
| **Claude Code** | Human | AI as co-author | No | No | Human | On |
| **Cursor** | Human | AI as co-author | No | No | Human | On |
| **Aider** | Human | AI as co-author | No | No | Human | Off |
| **Amazon Q** | Human | None (custom rules) | N/A | No | Human | Off |
| **Windsurf** | Human | None documented | N/A | No | Human | N/A |

### Key Takeaway

GitHub requires the co-author email to match a registered account for the avatar, profile link, and contribution graph to work. Tools using unregistered emails (`noreply@anthropic.com`, `cursoragent@cursor.com`, `noreply@aider.chat`) only get plain-text attribution — visible in `git log` but invisible in GitHub's contributor UI.

The only tools with full GitHub integration are **GitHub Copilot** (first-party advantage) and **Devin** (dedicated org account). All others are text-only.

## How It Works

### The `Co-authored-by` Trailer

Git and GitHub recognize a standard trailer format in commit messages:

```
Fix authentication bug in login flow

Co-authored-by: Copilot Agent <copilot@github.com>
```

GitHub renders these as actual co-authors on the commit, showing the avatar and linking to the account (if it exists). GitLab and other hosts also support this convention.

### When to Add the Trailer

The trailer should be added when **all** of these are true:

- The commit includes file changes that were generated or modified by the agent
- The user is committing through IntelliJ's VCS commit flow (commit dialog or commit tool window)
- The agent session was active and produced file edits during the current working session

The trailer should **not** be added when:

- The user is committing files that were only manually edited
- The agent session only answered questions without making file changes
- The user explicitly opts out via a setting

### What the Trailer Contains

```
Co-authored-by: Copilot Agent <copilot@github.com>
```

Optionally, the trailer could include more detail about which agents contributed:

```
Co-authored-by: Copilot Agent (lead) <copilot@github.com>
Co-authored-by: Copilot Agent (code-reviewer) <copilot@github.com>
```

## Implementation Approach

### Tracking Agent-Modified Files

`AgentService` already knows which files the agent touches via tool calls (`writeFile`, `editFile`, etc.). The plugin would maintain a set of file paths modified by the agent during the current session.

```
AgentService.agentModifiedFiles: Set<String>
```

This set is populated when:
- The lead agent executes file-editing tools
- Subagents complete tasks that involved file modifications

The set is cleared when:
- A new conversation is started
- The user commits (after the trailer is applied)

### Injecting the Trailer

IntelliJ provides the `CheckinHandler` extension point (`com.intellij.vcs.checkinHandlerFactory`). A custom `CheckinHandler` can:

1. Compare the files being committed against `agentModifiedFiles`
2. If there's overlap, append the `Co-authored-by` trailer to the commit message
3. Do nothing if there's no overlap

Alternatively, a `CommitMessageProvider` could suggest the trailer in the commit message template.

### User Control

A setting in the plugin's configuration panel:

- **Auto-add co-author trailer**: on/off (default: on)
- Accessible via Settings > Tools > Copilot Chat > Co-authored commits

## Edge Cases

| Scenario | Behavior |
|----------|----------|
| Agent edits file, user further modifies it | Still add trailer (agent contributed) |
| Agent edits file, user reverts all agent changes | Don't add trailer (no agent changes in diff) |
| User commits only a subset of agent-modified files | Add trailer (at least one agent file included) |
| Multiple agents contributed | Single trailer is sufficient, optionally list agent types |
| User amends a commit that already has the trailer | Don't duplicate the trailer |
| Command-line `git commit` (outside IntelliJ) | Plugin cannot intercept — user must add manually |

## Open Questions

1. **Granularity**: One generic `Copilot Agent` identity, or per-agent-type attribution?
2. **Email address**: Use `copilot@github.com`, `noreply@github.com`, or a custom address? If using GitHub Enterprise, consider creating a real service account for full contributor graph integration (like Devin's approach).
3. **Opt-out scope**: Global setting only, or per-commit toggle in the commit dialog?
4. **File-level accuracy**: Track at the file level or also verify the agent's changes survive in the final diff?
5. **Author vs co-author**: Should the AI be the author (like Copilot/Devin) or co-author (like Claude Code/Cursor)? Co-author preserves human ownership in git blame; author gives clearer AI traceability.
6. **Model name in trailer**: Include the model name like Aider does (e.g. `Copilot Agent (gpt-4.1)`) for LLM traceability?
