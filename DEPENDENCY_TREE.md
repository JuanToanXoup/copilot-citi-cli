# Dependency Tree: `.github/` and `.specify/`

## Overview

The `.github/` and `.specify/` folders form a **SpecKit** system — a 9-stage software specification and implementation pipeline driven by AI agents, shell scripts, and markdown templates.

---

## Pipeline Flow

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

---

## Workflow Steps

| Step | Command | Purpose | Needs (Prerequisites) | Executes | Creates (Outputs) | Hands Off To |
|------|---------|---------|----------------------|----------|-------------------|--------------|
| **1. constitution** | `/speckit.constitution` | Create or update the project constitution — non-negotiable governance principles that all downstream artifacts must comply with. | `.specify/templates/constitution-template.md` (if constitution doesn't exist yet). User-supplied principles via `$ARGUMENTS`. | 1. Loads `.specify/memory/constitution.md` (or copies from template if missing). 2. Identifies all `[PLACEHOLDER]` tokens. 3. Collects/derives values from user input, repo context, or prior versions. 4. Increments `CONSTITUTION_VERSION` using semver (MAJOR = removals, MINOR = additions, PATCH = clarifications). 5. Reads `plan-template.md`, `spec-template.md`, `tasks-template.md`, `commands/*.md` to verify alignment. 6. Validates: no unexplained brackets, ISO dates, declarative/testable principles. | `.specify/memory/constitution.md` (overwritten). Sync Impact Report (HTML comment at top): version change, modified/added/removed principles, templates requiring updates. | → `speckit.specify` |
| **2. specify** | `/speckit.specify <description>` | Transform a natural language feature description into a structured, technology-agnostic specification with a quality validation checklist. | Feature description via `$ARGUMENTS` (required). `create-new-feature.sh`. `spec-template.md`. | 1. Generates 2–4 word short name (e.g., "user-auth"). 2. Fetches all branches, checks remote + local + `specs/` for highest feature number. 3. Runs `create-new-feature.sh --json` → creates branch `NNN-short-name`, feature dir, copies spec template → `spec.md`. 4. Parses description — extracts actors, actions, data, constraints. 5. Fills all template sections. 6. Creates quality checklist at `checklists/requirements.md`. 7. Validates spec against checklist (up to 3 iterations). 8. If `[NEEDS CLARIFICATION]` markers remain (max 3), presents multi-choice questions to user. | Git branch `NNN-short-name`. `FEATURE_DIR/spec.md`. `FEATURE_DIR/checklists/requirements.md`. | → `speckit.clarify` (auto-send) or → `speckit.plan` |
| **3. clarify** *(optional)* | `/speckit.clarify` | Identify and resolve underspecified areas in the spec through targeted clarification questions (max 5). Should run before `plan`. | `spec.md` must exist. `check-prerequisites.sh`. | 1. Runs `check-prerequisites.sh --json --paths-only`. 2. Loads `spec.md`, performs 9-category ambiguity scan (Functional Scope, Domain & Data Model, Interaction & UX, Non-Functional, Integration, Edge Cases, Constraints, Terminology, Completion Signals). 3. Scores each: Clear / Partial / Missing. 4. Generates max 5 prioritized questions (ranked by Impact × Uncertainty). 5. Presents questions one at a time with options table. 6. After each answer: atomic integration into spec — adds `## Clarifications` section, updates relevant spec section, replaces contradictory text. 7. Validates after each write. | Updated `spec.md` with `## Clarifications` section and integrated answers. Coverage summary table. | → `speckit.plan` (implicit) |
| **4. plan** | `/speckit.plan` | Generate the technical design — resolve unknowns, define data models and interface contracts. | `spec.md` (required). `constitution.md` (required). `setup-plan.sh`. `update-agent-context.sh`. `plan-template.md`. `agent-file-template.md`. | 1. Runs `setup-plan.sh --json` → validates branch, copies plan template → `plan.md`, returns JSON paths. 2. Reads `spec.md` and `constitution.md`. 3. Fills Technical Context (Language, Framework, Storage, Project Type). 4. Performs Constitution Check — errors on violations. 5. **Phase 0 — Research**: resolves all `NEEDS CLARIFICATION` → `research.md`. 6. **Phase 1 — Design**: extracts entities → `data-model.md`, defines contracts → `contracts/`, creates `quickstart.md`. 7. Runs `update-agent-context.sh copilot` → updates agent context files (18 agent types). 8. Re-evaluates Constitution Check post-design. | `FEATURE_DIR/plan.md`. `FEATURE_DIR/research.md`. `FEATURE_DIR/data-model.md`. `FEATURE_DIR/contracts/`. `FEATURE_DIR/quickstart.md`. Updated agent context file. | → `speckit.tasks` (auto-send). → `speckit.checklist`. |
| **5. tasks** | `/speckit.tasks` | Generate an actionable, dependency-ordered task list organized by user story phases. | `plan.md` (required). `spec.md` (required). `data-model.md`, `contracts/`, `research.md`, `quickstart.md` (optional). `check-prerequisites.sh`. `tasks-template.md`. | 1. Runs `check-prerequisites.sh --json` — validates `plan.md` exists. 2. Loads all design artifacts. 3. Extracts tech stack from plan, user stories (P1, P2, P3) from spec. 4. Maps entities and contracts to stories. 5. Generates tasks: `- [ ] [TaskID] [P?] [Story?] Description file_path`. 6. Organizes into phases: Setup → Foundational → User Stories (by priority) → Polish. 7. Marks parallelizable tasks `[P]`. 8. Generates dependency graph. 9. Validates: every story independently testable, sequential IDs (T001, T002...). | `FEATURE_DIR/tasks.md` — full task list with phases, IDs, parallel markers, story labels. | → `speckit.analyze` (auto-send). → `speckit.implement` (auto-send). |
| **6. checklist** *(parallel with tasks)* | `/speckit.checklist <domain>` | Generate "Unit Tests for English" — validate requirement quality (completeness, clarity, consistency, measurability). Does NOT test implementation. | `spec.md` (required). `plan.md` (optional). `tasks.md` (optional). `check-prerequisites.sh`. `checklist-template.md`. User focus area via `$ARGUMENTS`. | 1. Runs `check-prerequisites.sh --json`. 2. Asks up to 3 dynamic clarifying questions from spec/plan/tasks signals (scope, risk, depth, audience, boundaries). May ask 2 more (max 5 total). 3. Loads feature context progressively. 4. Generates items: `- [ ] CHK### <question> [Dimension] [Spec §X.Y or Gap]`. 5. Categories: Completeness, Clarity, Consistency, Acceptance Criteria, Scenario Coverage, Edge Cases, Non-Functional, Dependencies, Ambiguities. 6. Enforces ≥80% traceability. 7. Soft cap 40 items; merges duplicates. | `FEATURE_DIR/checklists/[domain].md` — new file per run (e.g., `ux.md`, `api.md`, `security.md`). Never overwrites. | None (standalone, but checklists gate `implement`). |
| **7. analyze** *(read-only)* | `/speckit.analyze` | Non-destructive cross-artifact consistency and quality analysis. Identify gaps, conflicts, and coverage issues before implementation. | `tasks.md` (required). `spec.md` (required). `plan.md` (required). `constitution.md` (required). `check-prerequisites.sh`. | 1. Runs `check-prerequisites.sh --json --require-tasks --include-tasks` — aborts if files missing. 2. Loads sections from all artifacts progressively. 3. Builds semantic models: requirements inventory, story inventory, task coverage mapping, constitution rules. 4. Runs 6 detection passes (max 50 findings): Duplication, Ambiguity, Underspecification, Constitution Alignment (always CRITICAL), Coverage Gaps, Inconsistency. 5. Assigns severity: CRITICAL > HIGH > MEDIUM > LOW. 6. Produces findings table, coverage summary, metrics. | Markdown analysis report **to stdout only** — no file writes. Includes: findings table (ID, Category, Severity, Location, Summary, Recommendation), coverage %, metrics, next actions. | None (optional loops to `specify`, `plan`, or manual edits). |
| **8. implement** | `/speckit.implement` | Execute the full implementation plan — phase-by-phase with TDD, checklist gating, and progress tracking. | `tasks.md` (required). `plan.md` (required). `checklists/` (optional — gates if present). `data-model.md`, `contracts/`, `research.md`, `quickstart.md` (optional). `check-prerequisites.sh`. | 1. Runs `check-prerequisites.sh --json --require-tasks --include-tasks`. 2. **Checklist gate**: scans `checklists/`, counts incomplete vs complete per file, displays status table. If incomplete: STOPS, asks "proceed anyway?". If all pass: auto-proceeds. 3. Loads all design artifacts. 4. **Project setup**: detects tech stack, creates/verifies ignore files (`.gitignore`, `.dockerignore`, etc.). 5. Executes phase-by-phase: Setup → Tests → Core → Integration → Polish. `[P]` tasks run in parallel. TDD: tests before code. Same-file tasks sequential. 6. Marks completed tasks `[X]` in `tasks.md`. 7. Halts on non-parallel failure; continues successful `[P]` tasks. 8. Final validation: features match spec, tests pass. | Implemented source code. Updated `tasks.md` (marked `[X]`). Ignore files per tech stack. Progress reports. | → `speckit.taskstoissues` (manual). |
| **9. taskstoissues** | `/speckit.taskstoissues` | Convert all tasks into actionable GitHub issues, one per task. | `tasks.md` (required). GitHub remote URL (validated — refuses non-GitHub). `github/github-mcp-server/issue_write` MCP tool. `check-prerequisites.sh`. | 1. Runs `check-prerequisites.sh --json --require-tasks --include-tasks`. 2. Extracts task list from `tasks.md`. 3. Runs `git config --get remote.origin.url` — validates GitHub URL. **CRITICAL**: will not create issues in non-GitHub repos. 4. For each task: calls MCP `issue_write` to create GitHub issue with task ID, description, labels, dependencies. | GitHub issues (one per task, numbered by task ID). | None (terminal step). |

---

## Script Infrastructure

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

| Variable | Description |
|----------|-------------|
| `REPO_ROOT` | Repository root path (git or fallback to `.specify` parent) |
| `CURRENT_BRANCH` | Current git branch or `$SPECIFY_FEATURE` env var |
| `HAS_GIT` | Whether a git repository is detected (`true`/`false`) |
| `FEATURE_DIR` | `specs/NNN-feature-name/` directory path |
| `FEATURE_SPEC` | `FEATURE_DIR/spec.md` |
| `IMPL_PLAN` | `FEATURE_DIR/plan.md` |
| `TASKS` | `FEATURE_DIR/tasks.md` |
| `RESEARCH` | `FEATURE_DIR/research.md` |
| `DATA_MODEL` | `FEATURE_DIR/data-model.md` |
| `QUICKSTART` | `FEATURE_DIR/quickstart.md` |
| `CONTRACTS_DIR` | `FEATURE_DIR/contracts/` |

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

## Feature Artifact Flow

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

## Prompt Files

All 9 prompt files in `.github/prompts/` are minimal YAML frontmatter that maps `/speckit.<name>` commands to their agent definitions:

```yaml
---
agent: speckit.<name>
---
```

| Prompt File | Agent File |
|-------------|-----------|
| `speckit.analyze.prompt.md` | `speckit.analyze.agent.md` |
| `speckit.checklist.prompt.md` | `speckit.checklist.agent.md` |
| `speckit.clarify.prompt.md` | `speckit.clarify.agent.md` |
| `speckit.constitution.prompt.md` | `speckit.constitution.agent.md` |
| `speckit.implement.prompt.md` | `speckit.implement.agent.md` |
| `speckit.plan.prompt.md` | `speckit.plan.agent.md` |
| `speckit.specify.prompt.md` | `speckit.specify.agent.md` |
| `speckit.tasks.prompt.md` | `speckit.tasks.agent.md` |
| `speckit.taskstoissues.prompt.md` | `speckit.taskstoissues.agent.md` |
