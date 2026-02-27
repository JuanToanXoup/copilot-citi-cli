---
name: Speckit Issues
description: Convert existing tasks into actionable, dependency-ordered issues (GitHub, Bitbucket, or GitLab) for the feature based on available design artifacts.
argument-hint: Create issues from feature tasks
tools: ['speckit_taskstoissues', 'speckit_read_spec', 'speckit_read_memory', 'speckit_create_issue', 'run_in_terminal']
---

## User Input

```text
$ARGUMENTS
```

You **MUST** consider the user input before proceeding (if not empty).

## Outline

1. Call the `speckit_analyze_project` tool. It returns the current branch, feature directory, and a list of available documents with their paths and existence status. All paths are absolute.
1. From the executed script, extract the path to **tasks**.
1. Read the tasks file and parse each task.
1. For each task in the list, call `speckit_create_issue` with `title`, `body`, and optional `labels`. The tool auto-detects the VCS provider (GitHub, Bitbucket, or GitLab) from the git remote.

> [!CAUTION]
> UNDER NO CIRCUMSTANCES EVER CREATE ISSUES IN REPOSITORIES THAT DO NOT MATCH THE REMOTE URL
