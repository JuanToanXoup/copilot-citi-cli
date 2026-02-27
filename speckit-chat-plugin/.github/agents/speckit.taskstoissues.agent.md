---
description: Convert existing tasks into actionable, dependency-ordered issues (GitHub, Bitbucket, or GitLab) for the feature based on available design artifacts.
tools: ['speckit_create_issue']
---

## User Input

```text
$ARGUMENTS
```

You **MUST** consider the user input before proceeding (if not empty).

## Outline

1. Run `.specify/scripts/bash/check-prerequisites.sh --json --require-tasks --include-tasks` from repo root and parse FEATURE_DIR and AVAILABLE_DOCS list. All paths must be absolute. For single quotes in args like "I'm Groot", use escape syntax: e.g 'I'\''m Groot' (or double-quote if possible: "I'm Groot").
1. From the executed script, extract the path to **tasks**.
1. Read the tasks file and parse each task.
1. For each task in the list, call `speckit_create_issue` with `title`, `body`, and optional `labels`. The tool auto-detects the VCS provider (GitHub, Bitbucket, or GitLab) from the git remote.

> [!CAUTION]
> UNDER NO CIRCUMSTANCES EVER CREATE ISSUES IN REPOSITORIES THAT DO NOT MATCH THE REMOTE URL
