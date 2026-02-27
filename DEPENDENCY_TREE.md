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

## Workflow Steps — Detailed Breakdown

### Step 1: `constitution` — Establish Governance

| Aspect | Details |
|--------|---------|
| **Command** | `/speckit.constitution` |
| **Purpose** | Create or update the project constitution — the non-negotiable governance principles that all downstream artifacts must comply with. |
| **Needs** | `.specify/templates/constitution-template.md` (if constitution doesn't exist yet). User-supplied principles/governance inputs via `$ARGUMENTS`. |
| **Executes** | 1. Loads `.specify/memory/constitution.md` (or copies from template if missing). 2. Identifies all `[PLACEHOLDER]` tokens. 3. Collects/derives values from user input, repo context (README, docs), or prior versions. 4. Increments `CONSTITUTION_VERSION` using semver (MAJOR = removals/redefinitions, MINOR = additions, PATCH = clarifications). 5. Propagates changes — reads `plan-template.md`, `spec-template.md`, `tasks-template.md`, and `commands/*.md` to verify alignment. 6. Validates: no unexplained brackets, ISO dates, declarative/testable principles. |
| **Creates** | `.specify/memory/constitution.md` (overwritten). Sync Impact Report (HTML comment at top): version change, modified/added/removed principles, templates requiring updates, follow-up TODOs. |
| **Scripts Used** | None (direct file operations) |
| **Templates Used** | `constitution-template.md` (initial creation). Reads `spec-template.md`, `plan-template.md`, `tasks-template.md`, `commands/*.md` for consistency propagation. |
| **Hands Off To** | → `speckit.specify` ("Build Specification based on updated constitution") |

---

### Step 2: `specify` — Write Feature Specification

| Aspect | Details |
|--------|---------|
| **Command** | `/speckit.specify <feature description>` |
| **Purpose** | Transform a natural language feature description into a structured, technology-agnostic specification with a quality validation checklist. |
| **Needs** | Feature description via `$ARGUMENTS` (required — errors if empty). `.specify/scripts/bash/create-new-feature.sh`. `.specify/templates/spec-template.md`. |
| **Executes** | 1. Generates a 2–4 word short name (e.g., "user-auth"). 2. Runs `git fetch --all --prune`, checks remote branches + local branches + `specs/` dirs for highest feature number. 3. Runs `create-new-feature.sh --json` which creates git branch `NNN-short-name`, `specs/NNN-short-name/` dir, copies spec template → `spec.md`. 4. Parses description — extracts actors, actions, data, constraints. 5. Fills all template sections: functional requirements, user scenarios, success criteria, key entities. 6. Creates quality checklist at `checklists/requirements.md`. 7. Validates spec against checklist (up to 3 iterations). 8. If `[NEEDS CLARIFICATION]` markers remain (max 3), presents multi-choice question tables to user. |
| **Creates** | Git branch `NNN-short-name`. `FEATURE_DIR/spec.md` — the feature specification. `FEATURE_DIR/checklists/requirements.md` — spec quality validation checklist. |
| **Scripts Used** | `create-new-feature.sh` (sources `common.sh`) |
| **Templates Used** | `spec-template.md` |
| **Hands Off To** | → `speckit.clarify` ("Clarify specification requirements", auto-send) or → `speckit.plan` ("Create a plan for the spec") |

---

### Step 3: `clarify` — Reduce Ambiguity (Optional)

| Aspect | Details |
|--------|---------|
| **Command** | `/speckit.clarify` |
| **Purpose** | Identify and resolve underspecified areas in the feature spec through targeted clarification questions (max 5). Should run BEFORE `/speckit.plan`. |
| **Needs** | `spec.md` must exist (errors if missing — tells user to run `/speckit.specify`). `.specify/scripts/bash/check-prerequisites.sh`. |
| **Executes** | 1. Runs `check-prerequisites.sh --json --paths-only` to get paths. 2. Loads `spec.md` and performs a 9-category ambiguity scan: Functional Scope, Domain & Data Model, Interaction & UX, Non-Functional Quality, Integration, Edge Cases, Constraints, Terminology, Completion Signals. 3. Each category is scored: Clear / Partial / Missing. 4. Generates max 5 prioritized questions (ranked by Impact × Uncertainty). 5. Presents questions one at a time with recommended answer + options table. 6. After each accepted answer: **atomic integration** — adds to `## Clarifications > ### Session YYYY-MM-DD`, updates the relevant spec section, replaces contradictory text. 7. Validates after each write: no duplicates, no lingering placeholders, consistent terminology. |
| **Creates** | Updated `spec.md` with `## Clarifications` section and integrated answers. Coverage summary table showing category statuses. |
| **Scripts Used** | `check-prerequisites.sh` (sources `common.sh`) |
| **Templates Used** | None |
| **Hands Off To** | → `speckit.plan` (implicit, no auto-send) |

---

### Step 4: `plan` — Technical Design

| Aspect | Details |
|--------|---------|
| **Command** | `/speckit.plan` |
| **Purpose** | Execute the implementation planning workflow — generate the technical design, resolve unknowns, define data models and contracts. |
| **Needs** | `spec.md` (required). `.specify/memory/constitution.md` (required). `.specify/scripts/bash/setup-plan.sh`. `.specify/scripts/bash/update-agent-context.sh`. `.specify/templates/plan-template.md`. `.specify/templates/agent-file-template.md` (for new agent files). |
| **Executes** | 1. Runs `setup-plan.sh --json` which validates branch naming, copies `plan-template.md` → `plan.md`, returns JSON paths. 2. Reads `spec.md` and `constitution.md`. 3. Fills Technical Context: Language/Version, Primary Dependencies, Storage, Project Type. 4. Performs Constitution Check — evaluates gates, ERRORs on violations. 5. **Phase 0 — Research**: For each `NEEDS CLARIFICATION` or unknown dependency, creates research tasks. Produces `research.md` (Decision / Rationale / Alternatives). 6. **Phase 1 — Design**: Extracts entities → `data-model.md`, defines interface contracts → `contracts/`, creates `quickstart.md`. 7. Runs `update-agent-context.sh copilot` which parses `plan.md` metadata and updates agent context files (supports 18 agent types). 8. Re-evaluates Constitution Check post-design. |
| **Creates** | `FEATURE_DIR/plan.md` — implementation plan. `FEATURE_DIR/research.md` — technology decisions and rationale. `FEATURE_DIR/data-model.md` — entities, fields, relationships, validations. `FEATURE_DIR/contracts/` — interface/API contracts. `FEATURE_DIR/quickstart.md` — test scenarios and integration guide. Updated agent context file (e.g., `copilot-instructions.md`). |
| **Scripts Used** | `setup-plan.sh` (sources `common.sh`). `update-agent-context.sh` (sources `common.sh`). |
| **Templates Used** | `plan-template.md`. `agent-file-template.md` (for new agent context files). |
| **Hands Off To** | → `speckit.tasks` ("Break the plan into tasks", auto-send). → `speckit.checklist` ("Create a checklist for the domain..."). |

---

### Step 5: `tasks` — Task Breakdown

| Aspect | Details |
|--------|---------|
| **Command** | `/speckit.tasks` |
| **Purpose** | Generate an actionable, dependency-ordered task list organized by user story phases. |
| **Needs** | `plan.md` (required — errors if missing). `spec.md` (required). `data-model.md`, `contracts/`, `research.md`, `quickstart.md` (optional, uses if present). `.specify/scripts/bash/check-prerequisites.sh`. `.specify/templates/tasks-template.md`. |
| **Executes** | 1. Runs `check-prerequisites.sh --json` — validates `plan.md` exists. 2. Loads all available design artifacts from `FEATURE_DIR`. 3. Extracts tech stack from plan, user stories (P1, P2, P3) from spec. 4. Maps entities from `data-model.md` and interface contracts to user stories. 5. Generates tasks in strict format: `- [ ] [TaskID] [P?] [Story?] Description with file path`. 6. Organizes into phases: Setup → Foundational → User Stories (by priority) → Polish. 7. Marks parallelizable tasks with `[P]` (different files, no dependencies). 8. Generates dependency graph and parallel execution examples. 9. Validates: every story independently testable, all tasks have file paths and sequential IDs (T001, T002...). |
| **Creates** | `FEATURE_DIR/tasks.md` — the complete task list with phases, IDs, parallel markers, and story labels. |
| **Scripts Used** | `check-prerequisites.sh` (sources `common.sh`) |
| **Templates Used** | `tasks-template.md` |
| **Hands Off To** | → `speckit.analyze` ("Run project analysis", auto-send). → `speckit.implement` ("Start implementation", auto-send). |

---

### Step 6: `checklist` — Requirement Quality Validation (Parallel with Tasks)

| Aspect | Details |
|--------|---------|
| **Command** | `/speckit.checklist <focus domain>` |
| **Purpose** | Generate "Unit Tests for English" — validate that requirements are complete, clear, consistent, and measurable. Does NOT test implementation behavior. |
| **Needs** | `spec.md` (required). `plan.md` (optional). `tasks.md` (optional). `.specify/scripts/bash/check-prerequisites.sh`. `.specify/templates/checklist-template.md`. User focus area via `$ARGUMENTS`. |
| **Executes** | 1. Runs `check-prerequisites.sh --json` to get paths. 2. Asks up to 3 dynamic clarifying questions derived from user phrasing + spec/plan/tasks signals (scope refinement, risk prioritization, depth calibration, audience framing, boundary exclusion). 3. May ask 2 more follow-ups (max 5 total) if scenario classes remain unclear. 4. Loads feature context progressively from `FEATURE_DIR` (summarizes long sections, avoids full dumps). 5. Generates checklist items as questions about requirement quality: `- [ ] CHK### <question> [Dimension] [Spec §X.Y or Gap]`. 6. Categorizes by: Completeness, Clarity, Consistency, Acceptance Criteria, Scenario Coverage, Edge Cases, Non-Functional, Dependencies, Ambiguities. 7. Enforces ≥80% traceability (items reference spec sections or use `[Gap]`/`[Ambiguity]` markers). 8. Soft cap of 40 items; merges near-duplicates, aggregates low-impact edge cases. |
| **Creates** | `FEATURE_DIR/checklists/[domain].md` — a new file per invocation (e.g., `ux.md`, `api.md`, `security.md`, `performance.md`). Never overwrites existing checklists. |
| **Scripts Used** | `check-prerequisites.sh` (sources `common.sh`) |
| **Templates Used** | `checklist-template.md` |
| **Hands Off To** | None (standalone, but checklists gate `implement`). |

---

### Step 7: `analyze` — Cross-Artifact Consistency Check (Read-Only)

| Aspect | Details |
|--------|---------|
| **Command** | `/speckit.analyze` |
| **Purpose** | Perform a non-destructive consistency and quality analysis across all core artifacts. Identify gaps, conflicts, and coverage issues before implementation. |
| **Needs** | `tasks.md` (required — enforced by `--require-tasks`). `spec.md` (required). `plan.md` (required). `.specify/memory/constitution.md` (required — constitution is non-negotiable). `.specify/scripts/bash/check-prerequisites.sh`. |
| **Executes** | 1. Runs `check-prerequisites.sh --json --require-tasks --include-tasks` — aborts if any file missing. 2. Loads relevant sections from `spec.md`, `plan.md`, `tasks.md`, `constitution.md` progressively. 3. Builds semantic models: requirements inventory (slug-based keys), user story inventory, task coverage mapping, constitution rule set (MUST/SHOULD). 4. Runs 6 detection passes (max 50 findings): **A. Duplication** (near-duplicate requirements). **B. Ambiguity** (vague adjectives without metrics, unresolved placeholders). **C. Underspecification** (missing outcomes, acceptance criteria). **D. Constitution Alignment** (conflicts with MUST = always CRITICAL). **E. Coverage Gaps** (requirements with zero tasks, orphan tasks). **F. Inconsistency** (terminology drift, cross-file entity mismatches, ordering contradictions). 5. Assigns severity: CRITICAL > HIGH > MEDIUM > LOW. 6. Produces findings table, coverage summary, metrics (coverage %, ambiguity count, critical issues). |
| **Creates** | Markdown analysis report **to stdout only** — strictly no file writes. Report includes: findings table (ID, Category, Severity, Location, Summary, Recommendation), coverage summary table, constitution alignment issues, unmapped tasks, metrics, next actions. |
| **Scripts Used** | `check-prerequisites.sh` (sources `common.sh`) |
| **Templates Used** | None |
| **Hands Off To** | None (optional manual loops: → `speckit.specify`, → `speckit.plan`, or manual edits based on findings). |

---

### Step 8: `implement` — Execute Implementation

| Aspect | Details |
|--------|---------|
| **Command** | `/speckit.implement` |
| **Purpose** | Execute the full implementation plan — process all tasks phase-by-phase with TDD approach, checklist gating, and progress tracking. |
| **Needs** | `tasks.md` (required — enforced by `--require-tasks`). `plan.md` (required). `FEATURE_DIR/checklists/` (optional — but gates implementation if present with incomplete items). `data-model.md`, `contracts/`, `research.md`, `quickstart.md` (optional, uses if present). `.specify/scripts/bash/check-prerequisites.sh`. |
| **Executes** | 1. Runs `check-prerequisites.sh --json --require-tasks --include-tasks`. 2. **Checklist gate**: Scans `checklists/` — counts `- [ ]` vs `- [X]` per file, displays status table. If any incomplete: **STOPS**, asks "proceed anyway?", waits for user. If all pass: auto-proceeds. 3. Loads `tasks.md`, `plan.md`, and all optional design artifacts. 4. **Project setup**: Detects tech stack, creates/verifies ignore files (`.gitignore`, `.dockerignore`, `.eslintignore`, `.prettierignore`, `.npmignore`, `.terraformignore`, `.helmignore`) with technology-specific patterns. 5. Parses task phases, dependencies, `[P]` markers, file paths. 6. Executes phase-by-phase: Setup → Tests → Core → Integration → Polish. Respects dependency order. Runs `[P]` tasks in parallel. TDD: test tasks before implementation. Same-file tasks run sequentially. 7. Marks completed tasks `[X]` in `tasks.md` after each task. 8. Reports progress after each task. Halts on non-parallel failure; continues successful `[P]` tasks, reports failed ones. 9. Final validation: features match spec, tests pass, coverage meets requirements. |
| **Creates** | Implemented source code files. Updated `tasks.md` (checkboxes marked `[X]`). Ignore files (`.gitignore`, `.dockerignore`, etc.) based on detected stack. Progress reports after each task. |
| **Scripts Used** | `check-prerequisites.sh` (sources `common.sh`) |
| **Templates Used** | None |
| **Hands Off To** | → `speckit.taskstoissues` (manual, post-implementation). |

---

### Step 9: `taskstoissues` — GitHub Issue Sync

| Aspect | Details |
|--------|---------|
| **Command** | `/speckit.taskstoissues` |
| **Purpose** | Convert all tasks from `tasks.md` into actionable GitHub issues, one issue per task. |
| **Needs** | `tasks.md` (required — enforced by `--require-tasks`). GitHub remote URL (validated — **refuses to proceed** if remote is not GitHub). `github/github-mcp-server/issue_write` MCP tool. `.specify/scripts/bash/check-prerequisites.sh`. |
| **Executes** | 1. Runs `check-prerequisites.sh --json --require-tasks --include-tasks`. 2. Extracts the task list from `tasks.md`. 3. Runs `git config --get remote.origin.url` — validates remote is a GitHub URL. **CRITICAL**: Will not create issues in non-GitHub repositories. 4. For each task: calls `github/github-mcp-server/issue_write` to create a GitHub issue with task ID, description, labels, and dependencies. |
| **Creates** | GitHub issues (one per task, numbered by task ID). |
| **Scripts Used** | `check-prerequisites.sh` (sources `common.sh`) |
| **Templates Used** | None |
| **Hands Off To** | None (terminal step). |

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
