---
name: Speckit Issues
description: Convert existing tasks into actionable, dependency-ordered GitHub issues for the feature based on available design artifacts.
argument-hint: Create GitHub issues from feature tasks
tools: ['speckit_taskstoissues', 'speckit_read_spec', 'speckit_read_memory', 'run_in_terminal']
---

## User Input

```text
$ARGUMENTS
```

You **MUST** consider the user input before proceeding (if not empty).

## Outline

1. Call the `speckit_analyze_project` tool. It returns the current branch, feature directory, and a list of available documents with their paths and existence status. All paths are absolute.
1. From the executed script, extract the path to **tasks**.
1. Get the Git remote by running:

```bash
git config --get remote.origin.url
```

> [!CAUTION]
> ONLY PROCEED TO NEXT STEPS IF THE REMOTE IS A GITHUB URL

1. For each task in the list, use the GitHub MCP server to create a new issue in the repository that is representative of the Git remote.

> [!CAUTION]
> UNDER NO CIRCUMSTANCES EVER CREATE ISSUES IN REPOSITORIES THAT DO NOT MATCH THE REMOTE URL
