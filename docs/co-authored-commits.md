# Co-Authored Commits: Agent Change Attribution

## Problem

When the Copilot agent edits files via tools (`create_file`, `insert_edit_into_file`, `replace_string_in_file`, `multi_replace_string`, `apply_patch`), those changes are written directly to disk with no distinction from developer-made edits. This creates several issues:

- **No attribution** — `git log` shows the developer as the author of AI-generated code
- **No auditability** — impossible to tell which changes were human vs. machine after the fact
- **No traceability** — no record of which model or tools produced the code
- **Compliance risk** — organizations increasingly require disclosure of AI-generated code in version control

GitHub Copilot's own coding agent solves this by authoring commits itself, but that relies on a GitHub-hosted bot account (`copilot[bot]`) that isn't available for third-party integrations.

## Constraints

- No official GitHub Copilot bot account exists for local/plugin use
- `noreply@github.com` is GitHub's generic no-reply — it doesn't link to a Copilot profile
- Creating a dedicated GitHub machine user costs a seat and requires org admin setup
- GitHub Apps can author commits via API but not through local `git commit`

## Solution

Combine three mechanisms that work together at the git level, require no GitHub account setup, and are parseable by CI/tooling:

### 1. `--author` flag for git log attribution

Use git's built-in author/committer separation. The **author** is Copilot (who wrote the code), the **committer** is the developer (who approved it).

```bash
git commit --author="GitHub Copilot (gpt-4.1) <noreply@github.com>" -m "Add auth endpoint"
```

Result in `git log`:

```
commit a1b2c3d
Author:    GitHub Copilot (gpt-4.1) <noreply@github.com>
Commit:    John Doan <john.doan@company.com>

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

### 3. Model and tool trailers for audit

Additional trailers capture exactly what produced the code — which model, which tools were invoked. This is useful for compliance audits and debugging.

```
Add auth endpoint

Generated-by: github-copilot
Model: gpt-4.1
Tools: create_file, replace_string_in_file
```

## Full commit example

```
Add authentication endpoint

Generated-by: github-copilot
Model: gpt-4.1
Tools: create_file, insert_edit_into_file
```

```
Author:    GitHub Copilot (gpt-4.1) <noreply@github.com>
Commit:    John Doan <john.doan@company.com>
Date:      Mon Feb 23 11:42:03 2026 -0500
```

## Implementation

The Working Set feature in the Copilot Chat plugin handles this end-to-end:

1. **Tracking** — `WorkingSetService` captures before/after snapshots around every file-modifying tool call
2. **Review** — The "Changes" tab shows a file list with diff viewer, revert, and accept controls
3. **Commit** — The "Commit" button in the Changes tab stages only Copilot-changed files and creates a commit with the `--author` flag and trailers
4. **Guard** — A `CheckinHandlerFactory` warns if Copilot-changed files are committed through IntelliJ's normal commit flow without attribution

### What gets committed where

| Change source | Commit author | Trailers |
|---|---|---|
| Developer edits | Developer (normal commit) | None |
| Copilot agent tools | `GitHub Copilot (model)` | `Generated-by`, `Model`, `Tools` |
| Mixed (both) | Developer should split into two commits | — |

### Querying AI-generated commits

```bash
# All Copilot commits
git log --author="GitHub Copilot"

# Copilot commits using a specific model
git log --grep="Model: gpt-4.1"

# Copilot commits that used create_file
git log --grep="Tools:.*create_file"

# Count of Copilot vs developer commits
echo "Copilot: $(git log --author='GitHub Copilot' --oneline | wc -l)"
echo "Developer: $(git log --author='GitHub Copilot' --invert-grep --oneline | wc -l)"
```

## Comparison with alternatives

| Approach | Attribution in git log | GitHub UI | Requires account | Parseable by CI |
|---|---|---|---|---|
| **`--author` + trailers** (this solution) | Author field + trailers | Trailers visible in commit view | No | Yes |
| `Co-authored-by` trailer | Trailer only | GitHub shows co-author avatar | No | Yes |
| GitHub machine user | Real author with avatar | Full profile link | Yes (1 seat) | Yes |
| GitHub App | `app[bot]` author | Bot badge | Yes (app setup) | Yes |
| Commit message prefix `[copilot]` | Message only | Visible but not structured | No | Fragile regex |

## Industry precedent

- **Claude Code** appends `Co-Authored-By: Claude <noreply@anthropic.com>` to commits
- **GitHub Copilot coding agent** commits as `copilot[bot]` (server-side only)
- **Cursor** relies on manual attribution via `.cursorrules` configuration
- **SSW Rules** recommends co-author trailers for all AI-assisted commits

The `--author` + trailers approach provides stronger attribution than co-author trailers (Copilot is the *author*, not a co-author) while remaining fully local with no account dependencies.
