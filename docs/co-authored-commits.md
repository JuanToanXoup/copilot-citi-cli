# Copilot-Authored Commits: Agent Change Attribution

## Problem

When the Copilot agent edits files via tools (`create_file`, `insert_edit_into_file`, `replace_string_in_file`, `multi_replace_string`, `apply_patch`), those changes are written directly to disk with no distinction from developer-made edits. This creates several issues:

- **No attribution** — `git log` shows the developer as the author of AI-generated code
- **No auditability** — impossible to tell which changes were human vs. machine after the fact
- **No traceability** — no record of which model or tools produced the code
- **Compliance risk** — organizations increasingly require disclosure of AI-generated code in version control

GitHub Copilot's own coding agent solves this by authoring commits itself, but that relies on a GitHub-hosted bot account (`copilot[bot]`) that isn't available for third-party integrations.

## Constraints

- **There is no official GitHub Copilot email address.** GitHub's coding agent (`copilot[bot]`) is a server-side bot with its own account, but no public email is available for third-party use in local `git commit --author`.
- Creating a dedicated GitHub machine user costs a seat and requires org admin setup
- GitHub Apps can author commits via API but not through local `git commit`
- Git's `--author` flag requires the `Name <email>` format — we use `copilot@copilot.example`, an RFC 6761 reserved domain guaranteed to never be a real address

## Solution

Combine two mechanisms that work together at the git level, require no GitHub account setup, and are parseable by CI/tooling:

### 1. `--author` flag for git log attribution

Use git's built-in author/committer separation. The **author** is Copilot (who wrote the code), the **committer** is the developer (who approved it).

There is no official GitHub Copilot email address. Git requires the `Name <email>` format for `--author`, so we use `copilot@copilot.example` — a reserved domain under RFC 6761 that is guaranteed to never be a real address.

```bash
git commit --author="GitHub Copilot (gpt-4.1) <copilot@copilot.example>" -m "Add auth endpoint"
```

Result in `git log`:

```
commit a1b2c3d
Author:    GitHub Copilot (gpt-4.1) <copilot@copilot.example>
Commit:    John Doan <john.doan@citi.com>

    Add auth endpoint
```

This gives immediate visual separation — `git log --author="GitHub Copilot"` returns only AI-generated commits. The committer field preserves accountability (who approved the change).

### 2. `Generated-by` trailer for tooling and search

Git trailers are structured key-value metadata at the end of a commit message. GitHub renders them in the commit detail view. CI pipelines and scripts can parse them.

```
Add auth endpoint

Generated-by: github-copilot
```

Search across the repo:

```bash
git log --grep="Generated-by: github-copilot"
```

## Full commit example

```
Add authentication endpoint

Generated-by: github-copilot
```

```
Author:    GitHub Copilot (gpt-4.1) <copilot@copilot.example>
Commit:    John Doan <john.doan@citi.com>
Date:      Mon Feb 23 11:42:03 2026 -0500
```

## Implementation

The Working Set feature in the Copilot Chat plugin handles this end-to-end:

1. **Tracking** — `WorkingSetService` (`workingset/WorkingSetService.kt`) captures before/after snapshots around every file-modifying tool call via interception in `ToolRouter.executeTool()`. After each tool, it refreshes the VFS and moves the file into a **"Copilot Changes"** changelist so agent-modified files are visually grouped in IntelliJ's Commit panel, separate from the developer's own edits.

2. **Review** — `WorkingSetPanel` (`workingset/WorkingSetPanel.kt`) renders the "Changes" tab with a file list showing A (added) / M (modified) badges. Double-click opens IntelliJ's built-in diff viewer. Right-click offers "Show Diff", "Open in Editor", and "Revert File".

3. **Commit** — The "Commit" button in the Changes tab opens a dialog with a pre-filled message and file list. On confirm, it stages only the Copilot-changed files via `git add`, then runs `git commit --author="GitHub Copilot (model) <copilot@copilot.example>"` with a `Generated-by` trailer appended to the message. The working set clears after a successful commit.

4. **Guard** — `CopilotCheckinHandlerFactory` (`workingset/CopilotCheckinHandler.kt`) hooks into IntelliJ's normal commit flow. If any files being committed are tracked by the working set, it shows a warning listing those files and offering two choices: "Commit Anyway" (proceeds under the developer's name) or "Cancel" (so the developer can use the Changes tab's Commit button instead).

### Copilot Changes changelist

Agent-modified files are automatically moved to a dedicated **"Copilot Changes"** changelist in IntelliJ's Commit panel. This provides at-a-glance separation before any commit is made:

```
▼ Copilot Changes (2 files)
    A  src/auth/AuthEndpoint.kt
    M  src/config/SecurityConfig.kt
▼ Default Changelist (1 file)
    M  README.md
```

The developer can commit the "Copilot Changes" group via the Changes tab (creating a Copilot-authored commit) and their own edits separately via the normal Commit panel.

### What gets committed where

| Change source | Commit author | Trailers |
|---|---|---|
| Developer edits | Developer (normal commit) | None |
| Copilot agent tools | `GitHub Copilot (model)` | `Generated-by` |
| Mixed (both) | Developer should split into two commits | — |

### Querying AI-generated commits

```bash
# All Copilot commits
git log --author="GitHub Copilot"

# All commits with the Generated-by trailer
git log --grep="Generated-by: github-copilot"

# Count of Copilot vs developer commits
echo "Copilot: $(git log --author='GitHub Copilot' --oneline | wc -l)"
echo "Developer: $(git log --author='GitHub Copilot' --invert-grep --oneline | wc -l)"
```

## Comparison with alternatives

| Approach | Attribution in git log | GitHub UI | Requires account | Parseable by CI |
|---|---|---|---|---|
| **`--author` + trailer** (this solution) | Author field + trailer | Trailer visible in commit view | No | Yes |
| `Co-authored-by` trailer | Trailer only | GitHub shows co-author avatar | No | Yes |
| GitHub machine user | Real author with avatar | Full profile link | Yes (1 seat) | Yes |
| GitHub App | `app[bot]` author | Bot badge | Yes (app setup) | Yes |
| Commit message prefix `[copilot]` | Message only | Visible but not structured | No | Fragile regex |

## Industry precedent

- **Claude Code** appends `Co-Authored-By: Claude <noreply@anthropic.com>` to commits
- **GitHub Copilot coding agent** commits as `copilot[bot]` (server-side only)
- **Cursor** relies on manual attribution via `.cursorrules` configuration
- **SSW Rules** recommends co-author trailers for all AI-assisted commits

The `--author` + trailer approach provides stronger attribution than co-author trailers (Copilot is the *author*, not a co-author) while remaining fully local with no account dependencies.
