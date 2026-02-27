# Dependency Tree: `.github/` and `.specify/`

## Overview

The `.github/` and `.specify/` folders form a **SpecKit** system — a 9-stage software specification and implementation pipeline driven by AI agents, shell scripts, and markdown templates.

---

## Agent Pipeline (`.github/agents/`)

Each agent has a corresponding prompt file in `.github/prompts/` that maps the `/speckit.<name>` command to the agent definition.

### Pipeline Flow

```
                    ┌──────────────────┐
                    │  1. constitution  │  Governance principles
                    └────────┬─────────┘
                             │ establishes rules
                             ▼
                    ┌──────────────────┐
                    │   2. specify     │  Feature spec + quality checklist
                    └────────┬─────────┘
                             │ creates spec.md
                    ┌────────┴─────────┐
                    ▼                  ▼
           ┌──────────────┐   ┌──────────────┐
           │ 3. clarify   │   │  (skip to 4) │
           │  (optional)  │   │              │
           └──────┬───────┘   └──────┬───────┘
                  │ updated spec.md  │
                  └────────┬─────────┘
                           ▼
                  ┌──────────────────┐
                  │    4. plan       │  Design artifacts
                  └────────┬─────────┘
                           │ auto-handoff (send=true)
                  ┌────────┴─────────┐
                  ▼                  ▼
         ┌──────────────┐   ┌──────────────┐
         │  5. tasks    │   │ 6. checklist │  (parallel)
         └───────┬──────┘   └──────────────┘
                 │ auto-handoff (send=true)
        ┌────────┴─────────┐
        ▼                  ▼
┌──────────────┐   ┌──────────────┐
│ 7. analyze   │   │ 8. implement │
│ (read-only)  │   │              │
└──────────────┘   └──────┬───────┘
                          │
                          ▼
                 ┌──────────────────┐
                 │ 9. taskstoissues │  GitHub issue sync
                 └──────────────────┘
```

### Agent Details

| # | Agent | Purpose | Key Input | Key Output | Auto-Handoff |
|---|-------|---------|-----------|------------|--------------|
| 1 | `constitution` | Project governance & principles | User principles | `constitution.md` | → `specify` |
| 2 | `specify` | Feature specification | Feature description | `spec.md`, `checklists/requirements.md` | → `clarify` or `plan` |
| 3 | `clarify` | Ambiguity reduction (≤5 questions) | Existing `spec.md` | Updated `spec.md` with Clarifications | → `plan` (implicit) |
| 4 | `plan` | Technical design | `spec.md`, `constitution.md` | `plan.md`, `research.md`, `data-model.md`, `contracts/`, `quickstart.md` | → `tasks` + `checklist` (auto) |
| 5 | `tasks` | Task breakdown by user story | `plan.md`, `spec.md` | `tasks.md` | → `analyze` + `implement` (auto) |
| 6 | `checklist` | Requirement quality validation | `spec.md`, `plan.md` | `checklists/[domain].md` | — |
| 7 | `analyze` | Cross-artifact consistency check | `tasks.md`, `spec.md`, `plan.md`, `constitution.md` | Analysis report (no file writes) | — (optional loops) |
| 8 | `implement` | Code execution (TDD, phase-by-phase) | `tasks.md`, `plan.md` | Implemented features, updated `tasks.md` | → `taskstoissues` (manual) |
| 9 | `taskstoissues` | GitHub issue sync | `tasks.md`, git remote | GitHub issues | — |

### Hard Prerequisites Chain

```
specify (creates spec.md)
  → clarify (needs spec.md, optional but recommended)
    → plan (needs spec.md, constitution.md)
      → tasks (needs plan.md, spec.md)
        → analyze (needs tasks.md)
        → implement (needs tasks.md, checks checklists/)
        → taskstoissues (needs tasks.md + GitHub remote)
```

### Prompt Files

All 9 prompt files in `.github/prompts/` are minimal YAML frontmatter that maps commands to agents:

```yaml
---
agent: speckit.<name>
---
```

---

## Scripts & Templates (`.specify/`)

### Script Dependency Tree

```
common.sh  ← Foundation: path resolution, branch validation, env vars
 │
 ├── check-prerequisites.sh
 │   └── Validates: feature dir, plan.md, tasks.md (optional)
 │   └── Flags: --json, --require-tasks, --include-tasks, --paths-only
 │
 ├── create-new-feature.sh
 │   └── Creates: git branch, feature dir, spec.md (from template)
 │   └── Flags: --json, --short-name, --number
 │
 ├── setup-plan.sh
 │   └── Creates: plan.md (from template) in feature dir
 │   └── Validates: branch naming, feature dir existence
 │
 └── update-agent-context.sh
     └── Parses: plan.md metadata (Language, Framework, DB, Project Type)
     └── Creates/Updates: agent context files for 18 agent types
     └── Supports: claude, gemini, copilot, cursor, qwen, opencode,
                   codex, windsurf, kilocode, auggie, roo, codebuddy,
                   qodercli, amp, shai, q, agy, bob
```

### `common.sh` Exported Variables

```
REPO_ROOT, CURRENT_BRANCH, HAS_GIT, FEATURE_DIR, FEATURE_SPEC,
IMPL_PLAN, TASKS, RESEARCH, DATA_MODEL, QUICKSTART, CONTRACTS_DIR
```

### Template Usage Map

| Template | Consumer (Script) | Consumer (Agent) | Output Artifact |
|----------|-------------------|-------------------|-----------------|
| `spec-template.md` | `create-new-feature.sh` | `specify` | `spec.md` |
| `plan-template.md` | `setup-plan.sh` | `plan` | `plan.md` |
| `tasks-template.md` | — | `tasks` | `tasks.md` |
| `checklist-template.md` | — | `checklist` | `checklists/[domain].md` |
| `constitution-template.md` | — | `constitution` | `constitution.md` |
| `agent-file-template.md` | `update-agent-context.sh` | — | Agent context files |

---

## Cross-Directory Dependencies

### Agents → Scripts

| Agent | Script Invoked |
|-------|---------------|
| `specify` | `create-new-feature.sh` |
| `clarify` | `check-prerequisites.sh` |
| `plan` | `setup-plan.sh`, `update-agent-context.sh` |
| `tasks` | `check-prerequisites.sh` |
| `checklist` | `check-prerequisites.sh` |
| `analyze` | `check-prerequisites.sh --require-tasks` |
| `implement` | `check-prerequisites.sh --require-tasks` |
| `taskstoissues` | `check-prerequisites.sh --require-tasks` |

### Agents → Templates

| Agent | Template Used |
|-------|--------------|
| `specify` | `spec-template.md` (via `create-new-feature.sh`) |
| `plan` | `plan-template.md` (via `setup-plan.sh`) |
| `tasks` | `tasks-template.md` (direct reference) |
| `checklist` | `checklist-template.md` (direct reference) |
| `constitution` | `constitution-template.md` (referenced, not via script) |

---

## Feature Artifact Flow

Artifacts produced during the pipeline and their relationships:

```
constitution.md (governance, referenced by plan & analyze)
    │
    ▼
spec.md ────────────────────────────┐
    │                               │
    ▼                               ▼
plan.md ──────────────┐    checklists/requirements.md
    │                 │    checklists/[domain].md
    ├── research.md   │
    ├── data-model.md │
    ├── contracts/    │
    └── quickstart.md │
                      ▼
                  tasks.md
                      │
                      ├── → Analysis report (read-only)
                      ├── → Implemented code (tasks marked [X])
                      └── → GitHub issues
```

### Artifact Producers & Consumers

| Artifact | Produced By | Consumed By |
|----------|------------|-------------|
| `constitution.md` | `constitution` agent | `plan`, `analyze` |
| `spec.md` | `specify` agent | `clarify`, `plan`, `tasks`, `analyze`, `checklist`, `implement` |
| `plan.md` | `plan` agent | `tasks`, `analyze`, `implement`, `checklist` |
| `research.md` | `plan` agent (Phase 0) | `tasks`, `implement` |
| `data-model.md` | `plan` agent (Phase 1) | `tasks`, `implement` |
| `contracts/` | `plan` agent (Phase 1) | `tasks`, `implement` |
| `quickstart.md` | `plan` agent (Phase 1) | `tasks`, `implement` |
| `tasks.md` | `tasks` agent | `analyze`, `implement`, `taskstoissues`, `checklist` |
| `checklists/*.md` | `specify` + `checklist` agents | `implement` (pre-check gate) |

---

## Workflow Entry Points

| Entry Point | Command | Prerequisites | Creates |
|-------------|---------|---------------|---------|
| New feature | `/speckit.specify` | None | Branch, `spec.md`, `checklists/requirements.md` |
| Update governance | `/speckit.constitution` | None | `constitution.md` |
| Clarify spec | `/speckit.clarify` | `spec.md` | Updated `spec.md` |
| Create plan | `/speckit.plan` | `spec.md` | `plan.md` + design artifacts |
| Generate checklist | `/speckit.checklist` | `spec.md`, `plan.md` | `checklists/[domain].md` |
| Break into tasks | `/speckit.tasks` | `plan.md`, `spec.md` | `tasks.md` |
| Analyze consistency | `/speckit.analyze` | `tasks.md` | Report (stdout only) |
| Implement | `/speckit.implement` | `tasks.md` | Code + updated `tasks.md` |
| Sync to GitHub | `/speckit.taskstoissues` | `tasks.md` + GitHub remote | GitHub issues |
