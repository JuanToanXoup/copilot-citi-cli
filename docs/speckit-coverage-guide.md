# Speckit Coverage Guide

Step-by-step prompts to take a microservice from moderate test coverage to 100% using the [Spec Kit](https://github.com/github/spec-kit) pipeline. Each prompt is copy-paste ready. No framework or language knowledge required.

---

## How This Guide Works

Each section gives you:

- **Context** -- why this step matters
- **Prompt** -- the exact text to type into Copilot Chat
- **Expected output** -- what the agent produces
- **Checkpoint** -- how to verify before moving on

The `/speckit.<command>` prompts are slash commands. Everything you type after the command becomes the argument text that steers the agent. The agents are autonomous; your argument text focuses them on the right problem.

### Spec-Driven Development in brief

Spec Kit implements [Spec-Driven Development](https://github.com/github/spec-kit/blob/main/spec-driven.md) -- a methodology where specifications are the source of truth and code serves them, not the other way around. The core workflow is: **specify** (what and why) -> **clarify** (resolve ambiguities) -> **plan** (how, with tech decisions) -> **tasks** (actionable checklist) -> **analyze** (consistency check) -> **implement** (code generation). At every step, **you must review the output and iterate** before moving on. The agents are powerful but literal-minded; your review catches what they miss.

### Why this works for brownfield projects

Spec Kit was designed for greenfield work, but the pipeline adapts well to coverage gaps in existing codebases. The key difference: instead of inventing requirements from scratch, you derive them from the gap inventory (Phase 1). The spec describes *what coverage outcomes you need*, the plan describes *how to achieve them in your specific stack*, and the tasks give the implement agent a precise work queue. The constitution ensures generated tests match your project's existing conventions rather than inventing new ones.

### Data you'll carry forward

Each phase produces data the next phase consumes. Keep these handy -- you'll paste them into prompts:

| Phase produces | Used by |
|----------------|---------|
| Baseline coverage % | Specify, Plan, Implement |
| Gap inventory table (file, coverage %, uncovered lines) | Specify, Plan, Tasks |
| Exclusion list | Specify, Plan |
| Clarified mock/DI/async decisions | Plan, Tasks |
| Batch groupings from plan | Tasks, Implement |

---

## Phase 0: Ratify a Constitution

### Context

The constitution is the project's non-negotiable governance document. It governs all development -- architecture, quality, security, testing -- and every downstream agent validates its work against it. For the coverage use case, you need testing principles in the constitution so the pipeline knows what conventions to follow when generating tests. If your project already has a constitution, this step *adds* testing principles to it rather than replacing what's there.

### Prompt 0a -- Create or update the constitution

```
/speckit.constitution Add or strengthen the following testing principles:
1. Test-First (NON-NEGOTIABLE): TDD mandatory -- tests written before implementation, red-green-refactor enforced
2. Unit Test Isolation: Every unit test must mock external dependencies (databases, HTTP clients, message brokers). No test may depend on network or filesystem state.
3. Coverage Gate: 100% line and branch coverage required for all non-generated source files. CI must fail on regression.
4. Test Naming: Test file names and method names must follow the conventions already present in the codebase. If none exist, adopt the dominant community convention for the language.
5. Determinism: No flaky tests. All randomness must use fixed seeds. All time-dependent logic must use injectable clocks.
```

### Expected output

The agent reads your existing constitution (or copies the template if none exists), merges your five testing principles into it, detects existing conventions in the repo, and writes `.specify/memory/constitution.md`. It produces a sync impact report listing the version bump and any templates updated for consistency.

### Checkpoint

- `.specify/memory/constitution.md` exists
- The file contains your testing principles (or your customized versions)
- No `[PLACEHOLDER]` tokens remain
- The version number incremented
- If the project already had a constitution, the existing non-testing principles are preserved

---

## Phase 1: Discovery and Baseline (Manual)

### Context

Before speckit can write specs or tasks, you need two pieces of data: the current coverage percentage and a file-by-file gap inventory. This phase uses plain Copilot Chat (no slash command) because it depends on your project's specific toolchain. Save the outputs -- you'll paste them into the prompts in Phases 2-6.

### Prompt 1a -- Discover the stack and run a baseline report

```
Discover this project's language, build system, test framework, and coverage
tool by reading the build files. Then run the test suite with coverage enabled
and produce a summary with:
- Overall line coverage %
- Overall branch coverage %
- The exact command you used (so I can re-run it later)
- Where the coverage report was written (file path)

If coverage tooling is not yet configured, configure it first using the most
common tool for this stack, then run it.
```

### Expected output

Copilot identifies the tech stack, configures coverage if needed, runs the tests, and reports a baseline number (e.g., "62% line, 54% branch").

**Save this output.** You need the baseline number, the coverage command, and the report path for later phases.

### Checkpoint

- You have a concrete baseline number (e.g., 62%)
- You know the exact command to re-run coverage
- You know the coverage report file path

### Prompt 1b -- Produce a gap inventory

```
Using the coverage report at [PASTE REPORT PATH], produce a table of every
source file below 100% coverage with these columns:

| File | Line Coverage % | Lines Missed | Uncovered Line Ranges |

Sort by coverage % ascending (worst files first). Group the table by impact
tier:
- CRITICAL: service layer, business logic, domain models
- HIGH: controllers, API handlers, validation logic
- MEDIUM: utilities, helpers, configuration
- LOW: DTOs, constants, mappers

Exclude generated code, plain data holders with no logic, and framework
boilerplate. List exclusions separately with rationale.

At the bottom, show summary counts:
- Total files below 100%
- Files per tier
- Estimated total uncovered lines
```

### Expected output

A tiered gap inventory table with per-file detail and a summary. This becomes the input data for the specify and plan phases.

**Save this output.** You'll paste the summary counts and tier breakdown into the specify prompt.

### Checkpoint

- Every file below 100% appears in the table with its tier
- Exclusions are listed with rationale
- Summary counts are present at the bottom
- Tiers make sense for your project (adjust if the agent misclassified)

---

## Phase 2: Specify the Coverage Feature

### Context

The speckit pipeline treats everything as a feature -- even "close all coverage gaps." This step frames the coverage work as a formal specification. Per the Spec-Driven Development philosophy, the spec describes **what** outcomes you need and **why** -- not how to achieve them. Technical decisions (mock libraries, DI approach, test architecture) belong in Clarify (Phase 3) and Plan (Phase 4).

The key to a useful spec here is feeding in the real data from Phase 1. A spec that says "achieve 100% coverage" is vague. A spec that says "close 47 files across 4 tiers from 62% to 100%" gives the downstream agents concrete scope.

### Prompt 2a -- Create the specification

```
/speckit.specify Close all unit test coverage gaps to reach 100% line and branch coverage.

Current state (from baseline analysis):
- Baseline: [PASTE BASELINE, e.g., "62% line / 54% branch"]
- [PASTE SUMMARY COUNTS, e.g., "47 files below 100%: 12 CRITICAL, 15 HIGH, 11 MEDIUM, 9 LOW"]
- [PASTE ESTIMATED UNCOVERED LINES, e.g., "~2,400 uncovered lines total"]

User stories:
- As a developer, I want every non-excluded source file to have a corresponding test file so that all business logic is verified before merge.
- As a reviewer, I want every public method covered by normal-behavior, edge-case, and error-handling tests so that I can approve changes with confidence.
- As the CI pipeline, I want coverage enforced on every build so that merges cannot degrade quality.

Requirements:
- Every non-excluded source file must have a corresponding test file.
- Every public method must be tested for normal behavior, boundary/edge-case inputs, and failure scenarios.
- Tests must be self-contained: they must pass without external services, network access, or filesystem side effects.
- Tests must be deterministic: same inputs always produce same results, no flaky or order-dependent behavior.

Success criteria:
- 100% line coverage on all non-excluded source files
- 100% branch coverage on all non-excluded source files
- All tests pass in CI without external services running

Exclusions: [PASTE YOUR EXCLUSION LIST FROM PHASE 1]
```

> **Before running**: Replace every `[PASTE ...]` placeholder with the actual data from Phase 1.

### Expected output

The agent creates a feature branch (e.g., `1-unit-test-coverage`), writes `spec.md` in the feature directory under `specs/`, and runs a quality validation checklist. The spec should reflect your real baseline numbers and file counts, not generic language.

### Checkpoint

- A feature branch exists and is checked out
- `specs/<number>-<short-name>/spec.md` exists
- The spec contains your actual baseline numbers and tier counts from Phase 1
- Success criteria are measurable (not "improve coverage" but "100% line and branch")
- The quality checklist in `checklists/requirements.md` passes
- **Review and iterate**: If the agent added requirements you didn't ask for or lost your baseline data, edit the spec directly or re-run with refined input.

---

## Phase 3: Clarify Testing Decisions from the Codebase

### Context

The clarify agent scans the spec for underspecified areas and resolves them. For general features, it asks the *user* questions because the answers live in the user's head. For coverage work, the answers live in the *code* -- the existing test files, imports, constructor signatures, and framework patterns already tell you how dependencies are mocked, what DI approach is used, and how async code is handled.

The prompt below steers the agent to read the in-scope source files from the spec and derive testing decisions from what it finds. It will still present each decision as a question with a recommendation (that's how the agent works), but the recommendations will be grounded in your actual codebase. Your job is to confirm or override -- you shouldn't need to research anything yourself.

### Prompt 3a -- Derive testing decisions from the code

```
/speckit.clarify Read the source files listed in the spec's gap inventory and
the existing test files in the project. For each ambiguity, derive the answer
from the codebase rather than asking me to decide from scratch. Focus on:

1. Dependency isolation: For each type of external dependency found in the
   in-scope files (database clients, HTTP clients, message producers, caches),
   determine how existing tests already mock them. If no existing pattern, recommend
   the standard approach for this stack.

2. DI approach: Read constructor signatures and existing test setup methods.
   Determine whether tests use framework-managed context or manual construction
   with mocks. Recommend whichever pattern the codebase already uses.

3. Async handling: Scan in-scope files for async patterns (futures, coroutines,
   callbacks, event listeners). For each, check how existing tests handle them.
   Recommend the established pattern.

4. Stateful components: Identify caches, singletons, static registries, or
   thread-local state in the in-scope files. Recommend setup/teardown strategy
   based on existing test patterns.

5. Edge cases and failure modes: For each in-scope file, read the method bodies
   and list the boundary values (null, empty, max/min) and failure modes (catch
   blocks, validation checks, thrown exceptions) that need test coverage.

For each decision, ground your recommendation in what the codebase already does.
Cite the specific files and patterns you found.
```

### Expected output

The agent reads the spec, loads in-scope source files and existing test files, and presents up to 5 decisions one at a time. Each decision includes:

- What it found in the code (e.g., "3 of 4 existing tests use `@Mock`/`@InjectMocks` with Mockito")
- A recommendation grounded in those findings
- Options table if alternatives exist

After you confirm each decision (say "yes" to accept the recommendation, or override), the agent writes the resolution into spec.md's Clarifications section and updates the relevant requirement sections.

### Checkpoint

- `spec.md` has a `## Clarifications` section with a dated session
- Each clarification cites specific files/patterns found in the codebase
- Mock strategy, DI approach, and async handling are resolved with concrete decisions (not "TBD" or "depends")
- Edge cases and failure modes are listed per in-scope file
- No decision was left as a generic placeholder -- each one names the actual library/pattern to use

---

## Phase 4: Plan the Test Architecture

### Context

The plan agent reads the clarified spec and produces an execution blueprint. For coverage work, the plan's job is to turn the gap inventory into a dependency-ordered execution sequence with concrete batches. The clarified mock/DI/async decisions from Phase 3 go into the Technical Context so the implement agent doesn't have to re-derive them.

### Prompt 4a -- Generate the plan

```
/speckit.plan The spec describes closing coverage gaps from [PASTE BASELINE]
to 100%. The clarified spec contains resolved testing decisions (mock strategy,
DI approach, async handling, edge cases per file).

Technical context to include in the plan:
- Stack: [PASTE 2-3 LINES, e.g., "Java 17, Gradle Kotlin DSL, JUnit 5 + Mockito + AssertJ, JaCoCo"]
- Coverage command: [PASTE FROM PHASE 1, e.g., "./gradlew test jacocoTestReport"]
- Report path: [PASTE FROM PHASE 1]
- Gap summary: [PASTE TIER COUNTS, e.g., "47 files: 12 CRITICAL, 15 HIGH, 11 MEDIUM, 9 LOW"]

Plan requirements:
- Build a dependency graph of the in-scope source files. Execution order must
  start with leaf nodes (fewest dependencies) and work up to composite classes.
- Group files into batches of 3-5 (coupled code) or 5-10 (independent utilities).
  Each batch should be independently testable.
- For each batch, estimate the coverage delta based on uncovered line counts.
- Identify shared test fixtures needed (common mock factories, test data builders,
  base test classes) and schedule them before the batches that need them.
- Carry forward the mock strategy, DI approach, and async patterns from the
  clarified spec into the plan's Technical Context section.
```

> **Before running**: Replace every `[PASTE ...]` with actual data from Phases 1 and 3.

### Expected output

Three artifacts in the feature directory:

- `plan.md` -- dependency-ordered execution sequence, batch groupings with predicted coverage delta per batch, shared fixture strategy, Technical Context section carrying forward the clarified decisions
- `research.md` -- any resolved technical decisions the agent needed to research (e.g., version-specific test API differences)
- `data-model.md` -- entities and relationships relevant to test data setup (if applicable)

### Checkpoint

- `plan.md` has a concrete batch list (not "group files logically" but "Batch 1: UserService, OrderService, PaymentService -- predicted +8%")
- Execution order follows the dependency graph (leaf nodes first)
- Technical Context section contains the mock/DI/async decisions from Phase 3
- Shared fixtures are identified and scheduled before the batches that need them
- `research.md` has no unresolved `NEEDS CLARIFICATION` items
- **Review and iterate**: If batches are too large or the dependency order is wrong, edit `plan.md` directly or re-run `/speckit.plan` with corrections.

---

## Phase 5: Generate Tasks

### Context

The tasks agent reads the plan and spec, then produces a checklist with one task per test file. For coverage work, each task must be specific enough that the implement agent can write the test file without re-reading the source -- it needs the exact file path, the methods to cover, the scenarios per method, and the mock setup.

### Prompt 5a -- Generate the task list

```
/speckit.tasks Generate test-writing tasks from plan.md. Use TDD ordering
(test file created before any source changes).

For each source file in the plan's execution order:
- Read the source file and list its public methods
- For each method, derive: normal-behavior scenario, edge-case scenarios
  (from the clarified spec's edge case list), and error-path scenarios
  (from the clarified spec's failure mode list)
- Specify the test file path using the project's naming convention
- Specify the mock setup needed (from the clarified DI/mock decisions)
- Specify assertions: return value checks, exception assertions, mock
  interaction verification as appropriate

Group tasks by batch from plan.md. Within each batch:
- Mark independent tasks (different files, no shared state) with [P]
- Include the predicted coverage delta from the plan

At the end, add a validation task per batch: "Run coverage and verify
batch delta matches prediction (+/- 2%)."
```

### Expected output

`tasks.md` with tasks in strict checklist format, grouped by batch:

```
### Batch 1: Core Services (predicted: 62% -> 70%)

- [ ] [T001] [P] Create shared mock factory for repository interfaces in [test path]
- [ ] [T002] [P] [US1] Write UserService tests in [test path]: createUser (happy, null-name, duplicate-email), findById (found, not-found), deleteUser (exists, already-deleted). Mock: UserRepository. Assertions: return value, NotFoundException, verify save() called.
- [ ] [T003] [P] [US1] Write OrderService tests in [test path]: placeOrder (happy, empty-cart), cancelOrder (exists, already-cancelled, past-deadline). Mock: OrderRepository, PaymentClient. Assertions: return value, IllegalStateException, verify refund() called.
- [ ] [T004] [US1] Run coverage, verify batch delta: 62% -> ~70% (+/- 2%)
```

### Checkpoint

- `tasks.md` has all tasks in `- [ ] [TXXX]` checklist format
- Tasks are grouped by batch matching plan.md's batch structure
- Each test task names: the test file path, the methods under test, the scenarios per method, the mocks needed, and the assertion types
- Each batch ends with a coverage verification task
- Parallel markers `[P]` are on tasks that touch different files with no shared state
- **Review and iterate**: If a task is too vague ("write tests for X" with no scenario detail), re-run `/speckit.tasks` or edit `tasks.md` directly to add the missing specifics.

---

## Phase 5.5: Analyze for Consistency

### Context

Before implementation, the analyze agent cross-checks spec, plan, and tasks for gaps and contradictions. For coverage work, the critical check is: does every file in the gap inventory have tasks, and do those tasks cover every uncovered method?

### Prompt 5.5a -- Run the consistency check

```
/speckit.analyze Focus the analysis on coverage completeness:
- Cross-reference the gap inventory in spec.md with tasks.md. Flag any source
  file that appears in the gap inventory but has no corresponding test task.
- Cross-reference plan.md's batch groupings with tasks.md. Flag any batch
  mismatch (files in the plan but missing from tasks, or tasks not in any batch).
- Verify task ordering: no task should depend on a later task's output.
  Shared fixtures must be scheduled before the batches that use them.
- Verify constitution alignment: test naming, assertion style, and mock approach
  in the tasks match the constitution's testing principles.
- Flag any requirement in spec.md that has no mapped task.
```

### Expected output

A read-only analysis report with:

- Findings table (ID, category, severity, location, summary, recommendation)
- Coverage map: each gap-inventory file -> task IDs that cover it
- Constitution alignment status
- Unmapped files or requirements
- Metrics: total files in gap inventory, total test tasks, coverage map %, critical issue count

### Checkpoint

- **Zero CRITICAL issues** (every gap-inventory file has tasks, no constitution violations)
- Coverage map shows 100% of gap-inventory files have at least one task
- Batch ordering matches between plan.md and tasks.md
- If issues found, the report tells you which artifact to fix and how

> **If CRITICAL issues are found**: Fix the artifact the report points to (`/speckit.specify`, `/speckit.plan`, or `/speckit.tasks`), then re-run `/speckit.analyze`.

---

## Phase 6: Implement the Tests

### Context

The implement agent reads `tasks.md` and executes tasks in order. For coverage work, batch boundaries matter -- you want to measure coverage after each batch to confirm progress and catch problems early. The agent self-heals test failures (fixes imports, mock setup, assertions) up to 3 times per file.

### Prompt 6a -- Start implementation (first batch)

```
/speckit.implement Start with Batch 1 from tasks.md. For each task in the batch:
1. Read the source file under test
2. Read the task's scenario list, mock setup, and assertion requirements
3. Write the test file to the specified path
4. Follow the constitution's testing conventions (naming, assertions, mock style)

After writing all test files in the batch:
5. Run the full test suite -- if any new test fails, read the failure output,
   diagnose the root cause (missing mock stub, wrong expected value, import error),
   fix it, and retry (max 3 attempts per file)
6. Run coverage using: [PASTE COVERAGE COMMAND FROM PHASE 1]
7. Report: tests written, tests passing/failing, coverage before -> after, delta
   vs the predicted delta from tasks.md
8. Mark completed tasks as [X] in tasks.md

Stop after this batch so I can review before continuing.
```

> **Before running**: Replace `[PASTE COVERAGE COMMAND FROM PHASE 1]` with the actual command.

### Expected output

The agent processes Batch 1:

1. Writes test files to the paths specified in tasks.md
2. Runs tests -- reports pass/fail per file
3. Self-heals any failures (up to 3 retries)
4. Runs coverage and reports the delta (e.g., "62% -> 69%, predicted was 70%")
5. Marks completed tasks as `[X]` in tasks.md
6. Stops and reports a batch summary

### Checkpoint (after each batch)

- All new tests pass (green suite)
- Coverage delta is within 2% of the batch prediction from tasks.md
- Completed tasks are checked off in tasks.md
- Test files follow the naming convention from the constitution
- No flaky tests introduced (re-run the suite to confirm)

### Prompt 6b -- Continue to next batch

After reviewing a batch, continue with:

```
/speckit.implement Continue with the next batch from tasks.md. Previous batch
brought coverage to [PASTE CURRENT %]. Same approach: write tests per task
specs, run suite, self-heal failures, measure coverage, report delta vs
prediction, mark tasks complete. Stop after the batch.
```

Repeat until all batches are complete.

> **If coverage delta misses the prediction by more than 5%**: The task scenarios may be incomplete. Check which uncovered lines remain, compare against the task's scenario list, and add missing scenarios to tasks.md before continuing.

> **If coverage plateaus**: Some lines may be unreachable (dead code, defensive catches). Identify them in the coverage report and either remove the dead code or add explicit exclusion annotations.

---

## Phase 7: Verify and Lock (Manual)

### Context

After the implement agent finishes all batches, do a final manual verification and configure CI to prevent regression. This phase uses plain Copilot Chat.

### Prompt 7a -- Final coverage verification

```
Run coverage using: [PASTE COVERAGE COMMAND FROM PHASE 1]

Compare the result against the Phase 1 baseline of [PASTE BASELINE]. Show:
- Final line coverage % (target: 100%)
- Final branch coverage % (target: 100%)
- Total test files created during this pipeline
- Total test methods written
- Any files still below 100% with their uncovered line ranges

For any remaining gaps, classify each as:
- Dead code (unreachable branches) -- recommend removal
- Framework-generated (annotation processors, config classes) -- recommend exclusion
- Legitimately hard to test -- explain why and suggest approach
```

### Expected output

A final delta report: baseline -> final, with any remaining gaps classified and actionable.

### Checkpoint

- Line and branch coverage are at 100% (or every gap is classified and justified)
- All tests pass
- No tests depend on external services, network, or filesystem

### Prompt 7b -- Configure CI coverage gate

```
Configure the CI pipeline to enforce the coverage threshold so it cannot
regress. Add a step that:
1. Runs the test suite with coverage using: [PASTE COVERAGE COMMAND]
2. Fails the build if line coverage drops below [PASTE FINAL LINE %]
3. Fails the build if branch coverage drops below [PASTE FINAL BRANCH %]
4. Publishes the coverage report as a build artifact

Detect the existing CI system from the repo (.github/workflows, Jenkinsfile,
.gitlab-ci.yml, etc.). If no CI is configured, set up the coverage gate
as a local pre-push git hook instead.
```

> **Before running**: Replace the coverage command and threshold placeholders with your actual final numbers.

### Expected output

Updated CI configuration (or a git hook) that enforces the exact coverage threshold reached during this pipeline.

### Checkpoint

- CI pipeline (or hook) includes a coverage gate with your threshold numbers
- A test push or PR triggers the gate and passes
- Intentionally breaking coverage (commenting out a test) causes the gate to fail

---

## Quick Reference

| Phase | Command | What It Does |
|-------|---------|--------------|
| 0 | `/speckit.constitution` | Add testing principles to the project constitution |
| 1 | Plain chat | Discover stack, run baseline, build tiered gap inventory |
| 2 | `/speckit.specify` | Frame coverage gaps as a data-driven specification |
| 3 | `/speckit.clarify` | Derive mock/DI/async/edge-case decisions from the codebase |
| 4 | `/speckit.plan` | Build dependency-ordered batch execution plan |
| 5 | `/speckit.tasks` | Generate per-file test tasks with scenarios, mocks, assertions |
| 5.5 | `/speckit.analyze` | Cross-check gap inventory coverage and constitution alignment |
| 6 | `/speckit.implement` | Write tests batch-by-batch, measure coverage per batch |
| 7 | Plain chat | Final verification and CI gate configuration |

---

## Tips

- **Review and iterate at every step**: Agents are literal-minded. If an artifact isn't right, re-run the command with refined input or edit the file directly. Don't proceed with output you wouldn't approve in a PR review.
- **Feed data forward**: The prompts have `[PASTE ...]` placeholders for a reason. Each phase builds on concrete data from the previous one. Generic prompts produce generic output.
- **Start small**: If coverage is very low (below 30%), set an intermediate target (e.g., 80%) and run the pipeline twice.
- **Dead code**: If coverage can't reach 100% because of unreachable code, remove the dead code rather than excluding it.
- **Exclusions**: Generated code (protobuf stubs, annotation processors, framework boilerplate) should be excluded. Document exclusions in the spec so they're tracked.
- **Re-run analyze**: If you modify the spec, plan, or tasks mid-pipeline, re-run `/speckit.analyze` before continuing implementation.
