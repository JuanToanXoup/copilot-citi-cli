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

The agent fills the constitution template with your principles, detects existing conventions in the repo, and writes `.specify/memory/constitution.md`. It also produces a sync impact report listing any templates that were updated for consistency.

### Checkpoint

- `.specify/memory/constitution.md` exists
- The file contains all five testing principles above (or your customized versions)
- No `[PLACEHOLDER]` tokens remain in the file
- The version number has been incremented

---

## Phase 1: Discovery and Baseline (Manual)

### Context

Before speckit can write specs or tasks, you need two pieces of data: the current coverage percentage and a file-by-file gap inventory. This phase uses plain Copilot Chat (no slash command) because it depends on your project's specific toolchain.

### Prompt 1a -- Discover the stack and run a baseline report

```
Discover this project's language, build system, test framework, and coverage
tool by reading the build files. Then run the test suite with coverage enabled
and produce a summary with:
- Overall line coverage %
- Overall branch coverage %
- The command you used (so I can re-run it later)

If coverage tooling is not yet configured, configure it first using the most
common tool for this stack, then run it.
```

### Expected output

Copilot identifies the tech stack, configures coverage if needed, runs the tests, and reports a baseline number (e.g., "62% line, 54% branch").

### Checkpoint

- You have a concrete baseline number (e.g., 62%)
- You know the command to re-run coverage (e.g., the build/test command with coverage flags)
- Coverage reports are being generated to a known location

### Prompt 1b -- Produce a gap inventory

```
Using the coverage report you just generated, produce a table of every source
file below 100% coverage with these columns:

| File | Lines Covered | Lines Missed | Line Coverage % | Uncovered Line Ranges |

Sort by coverage % ascending (worst files first). Exclude generated code,
DTOs with no logic, and framework configuration classes. Note any exclusions
and why.
```

### Expected output

A markdown table showing every file with gaps, sorted worst-first, with exclusions noted.

### Checkpoint

- You have a file-level gap table
- You know which files are below target and by how much
- Exclusions are documented and reasonable

---

## Phase 2: Specify the Coverage Feature

### Context

The speckit pipeline treats everything as a feature -- even "achieve 100% test coverage." This step frames the coverage work as a formal specification with user stories, requirements, and success criteria. This gives the downstream plan and task agents structured input to work from.

Per the Spec-Driven Development philosophy, specifications describe **what** users need and **why** -- not **how** to implement it. Technical decisions (mock libraries, DI approach, test architecture) belong in Clarify (Phase 3) and Plan (Phase 4). Keep this spec focused on outcomes.

### Prompt 2a -- Create the specification

```
/speckit.specify Achieve 100% unit test coverage for all non-generated source files.

User stories:
- As a developer, I want every source file to have a corresponding test file so that all business logic is verified and regressions are caught before merge.
- As a reviewer, I want every public method to have tests covering its normal behavior, edge cases, and error handling so that I can approve changes with confidence.
- As the CI pipeline, I want coverage enforced on every build so that merges cannot degrade test quality.

Requirements:
- Every non-excluded source file must have a corresponding test file.
- Every public method must be tested for normal behavior, boundary/edge-case inputs, and failure scenarios.
- Tests must be self-contained: they must pass without external services, network access, or filesystem side effects.
- Tests must be deterministic: the same inputs always produce the same results, with no flaky or order-dependent behavior.
- Tests must execute quickly: no individual test should take longer than 5 seconds.

Success criteria:
- 100% line coverage on all non-excluded source files
- 100% branch coverage on all non-excluded source files
- All tests pass in CI without external services running
- Test suite completes within a reasonable time bound for the CI environment

Current baseline: [PASTE YOUR BASELINE NUMBER HERE, e.g., "62% line coverage"]

Exclusion list: [PASTE YOUR EXCLUSIONS HERE, e.g., "generated DTOs, framework configuration classes"]
```

> **Before running**: Replace `[PASTE YOUR BASELINE NUMBER HERE]` and `[PASTE YOUR EXCLUSIONS HERE]` with the actual values from Phase 1.

### Expected output

The agent creates a feature branch (e.g., `1-unit-test-coverage`), writes `spec.md` in the feature directory under `specs/`, and runs a quality validation checklist. If any `[NEEDS CLARIFICATION]` items surface, it will ask you to resolve them (max 3 questions).

### Checkpoint

- A feature branch exists and is checked out
- `specs/<number>-<short-name>/spec.md` exists
- The spec contains your user stories, requirements, and success criteria
- The quality checklist in `checklists/requirements.md` passes
- **Review and iterate**: Read the generated spec. If requirements are vague, success criteria unmeasurable, or scope wrong, edit the spec or re-run `/speckit.specify` with refined input. Don't proceed with a spec you wouldn't approve in a PR review.

---

## Phase 3: Clarify Testing Ambiguities

### Context

The clarify agent scans your spec for underspecified areas and asks targeted questions. For coverage work, the most common ambiguities are: how to mock specific dependency types, what DI approach tests should use, how to handle async code, and what edge cases matter most. Resolving these before planning prevents the implementation agent from guessing wrong.

### Prompt 3a -- Run clarification

```
/speckit.clarify Focus on testing-specific ambiguities:
- How should each type of external dependency be mocked? (database, HTTP, messaging, cache)
- What dependency injection approach should tests use? (full framework context vs manual construction with mocks)
- How should asynchronous operations be tested synchronously?
- What boundary values and edge cases matter most for the service layer?
- Are there stateful components (caches, singletons, static registries) requiring setup/teardown?
```

### Expected output

The agent reads your spec, scans for ambiguities across its taxonomy (functional scope, domain model, edge cases, non-functional attributes, etc.), and asks up to 5 questions one at a time. Each question includes a recommendation. After you answer, it writes the resolution directly into the spec's Clarifications section.

### Checkpoint

- `spec.md` now has a `## Clarifications` section with a dated session
- Each clarification bullet has `Q: ... -> A: ...` format
- The relevant spec sections (requirements, edge cases) have been updated with the resolved answers
- No vague or ambiguous language remains in the spec

---

## Phase 4: Plan the Test Architecture

### Context

The plan agent reads your clarified spec and produces an execution plan: which files to test in what order, what shared fixtures to create, how to batch work for incremental coverage gains, and what research is needed before writing tests. This is your test architecture blueprint.

### Prompt 4a -- Generate the plan

```
/speckit.plan I am building unit tests to reach 100% coverage. The project uses:
[PASTE 2-3 LINES ABOUT YOUR STACK, e.g.:
- Language: Java 17
- Build: Gradle with Kotlin DSL
- Test framework: JUnit 5 + Mockito + AssertJ
- Coverage: JaCoCo]

Key context:
- The gap inventory from Phase 1 shows [N] files below 100%
- Service-layer files have the largest gaps
- All external dependencies should be mocked per the clarified spec
- Test execution order should start with leaf-node classes (fewest dependencies) and work up
```

> **Before running**: Replace the bracketed sections with your actual tech stack and gap summary from Phase 1.

### Expected output

The agent produces three artifacts in the feature directory:

- `plan.md` -- test architecture, execution order, batch groupings, fixture strategy, predicted coverage per batch
- `research.md` -- resolved technical decisions (e.g., which mock approach for each dependency type)
- `data-model.md` -- entities and relationships relevant to test data (if applicable)

### Checkpoint

- `plan.md` exists with a clear execution order and batch groupings
- `research.md` exists with resolved decisions (no `NEEDS CLARIFICATION` remaining)
- The plan references the constitution and confirms no principle violations
- Batch sizes are reasonable (3-5 files per batch for coupled code, 5-10 for independent utilities)

---

## Phase 5: Generate Tasks

### Context

The tasks agent converts the plan into an actionable checklist -- one task per file per test scenario. Each task specifies exactly what test file to create, what methods to test, what mocks are needed, and what assertions to write. This is the implementation queue.

### Prompt 5a -- Generate the task list

```
/speckit.tasks Use a TDD approach. For each source file below 100% coverage, generate tasks that include:
- The exact test file path (following the project's naming convention)
- Happy-path test scenarios for every public method
- Edge-case test scenarios (null, empty, boundary inputs)
- Error-path test scenarios (exceptions, validation failures, timeout handling)
- Required mock setup for each external dependency
- Expected assertions (return value checks, exception assertions, mock interaction verification)

Group tasks by batch from the plan. Within each batch, mark independent tasks with [P] for parallel execution. Include a coverage target per batch so progress is measurable.
```

### Expected output

The agent produces `tasks.md` with tasks in strict checklist format:

```
- [ ] [T001] [P] Write tests for UserService: happy path (create, find, delete), null input, not-found exception
- [ ] [T002] [P] Write tests for OrderService: happy path (place, cancel), empty cart edge case, payment timeout
- [ ] [T003] Write tests for PaymentGateway: happy path, connection refused, invalid response
```

Each task has a task ID, optional `[P]` marker, and enough detail for an LLM to implement it without additional context.

### Checkpoint

- `tasks.md` exists with all tasks in `- [ ] [TXXX]` checklist format
- Every source file below 100% coverage has at least one task
- Tasks include specific scenarios (not just "write tests for X")
- Tasks are grouped by batch with a coverage target per batch
- Parallel markers `[P]` are present where tasks are independent

---

## Phase 5.5: Analyze for Consistency

### Context

Before implementation, the analyze agent cross-checks your spec, plan, and tasks for inconsistencies, gaps, and constitution violations. This catch step prevents wasted effort from misaligned artifacts.

### Prompt 5.5a -- Run the consistency check

```
/speckit.analyze Verify that:
1. Every source file in the gap inventory has at least one task in tasks.md
2. Every public method in those files has happy-path, edge-case, and error-path coverage in the task list
3. Task ordering respects the dependency graph from plan.md
4. All tasks follow the constitution's testing conventions
5. No requirements from spec.md are missing task coverage
```

### Expected output

A read-only analysis report with:

- A findings table (ID, category, severity, location, summary, recommendation)
- A coverage summary (requirement -> task mapping)
- Constitution alignment status
- Unmapped tasks (tasks with no corresponding requirement)
- Metrics: total requirements, total tasks, coverage %, ambiguity count, critical issues count

### Checkpoint

- The analysis report has **zero CRITICAL issues**
- Coverage % (requirements with at least one task) is 100%
- No constitution violations are flagged
- If HIGH issues exist, resolve them before proceeding (the report includes recommendations)

> **If CRITICAL issues are found**: Go back to the agent that owns the problematic artifact (`/speckit.specify`, `/speckit.plan`, or `/speckit.tasks`) and fix the issue before re-running `/speckit.analyze`.

---

## Phase 6: Implement the Tests

### Context

The implement agent reads `tasks.md` and executes tasks batch by batch. For each file, it reads the source, generates the test file, runs the tests, self-heals failures (up to 3 retries per file), and measures coverage after each batch. It checks off completed tasks as it goes.

### Prompt 6a -- Start implementation

```
/speckit.implement Execute tasks in batch order from tasks.md. For each batch:
1. Write the test files for all tasks in the batch
2. Run the test suite to verify all new tests pass
3. Run coverage and report the new coverage % after the batch
4. Check off completed tasks as [X] in tasks.md
5. If a test fails, read the failure output, fix the issue, and retry (max 3 attempts per file)
6. Report batch summary: tests written, tests passing, coverage delta

Stop after each batch and report progress so I can review before continuing.
```

### Expected output

The agent processes one batch at a time:

1. Writes test files to the correct paths
2. Runs the test suite -- reports pass/fail
3. Self-heals any failures (fixes imports, mock setup, assertions)
4. Runs coverage and reports the delta (e.g., "Coverage: 74% -> 81% (+7%)")
5. Marks completed tasks as `[X]` in `tasks.md`
6. Stops and reports a batch summary

### Checkpoint (after each batch)

- All new tests pass (green test suite)
- Coverage has increased compared to the previous batch
- Completed tasks are checked off in `tasks.md`
- Test files follow the naming convention from the constitution
- No flaky or non-deterministic tests were introduced

### Prompt 6b -- Continue to next batch

After reviewing a batch, continue with:

```
/speckit.implement Continue with the next batch. The current coverage is [PASTE CURRENT %]. Keep the same approach: write tests, verify they pass, measure coverage, check off tasks.
```

Repeat until all batches are complete.

> **If coverage plateaus**: Some lines may be unreachable (dead code, defensive catches that can't be triggered). Identify these with the coverage report and either remove the dead code or add explicit exclusion annotations.

---

## Phase 7: Verify and Lock (Manual)

### Context

After the implement agent finishes all batches, do a final manual verification and configure CI to prevent regression. This phase uses plain Copilot Chat.

### Prompt 7a -- Final coverage verification

```
Run the full test suite with coverage enabled and produce a final report.
Compare the result against the baseline from Phase 1. Show:
- Final line coverage % (target: 100%)
- Final branch coverage % (target: 100%)
- Number of test files created
- Number of test methods written
- Any remaining files below 100% with uncovered line ranges

If any files are still below 100%, explain why and whether the gap is
acceptable (dead code, unreachable branches, framework-generated code).
```

### Expected output

A final coverage report showing 100% (or close to it) with any remaining gaps explained.

### Checkpoint

- Line and branch coverage are at 100% (or gaps are documented and justified)
- All tests pass
- No tests depend on external services, network, or filesystem

### Prompt 7b -- Configure CI coverage gate

```
Configure the CI pipeline to enforce the coverage threshold. Add a step that:
1. Runs the test suite with coverage
2. Fails the build if line coverage drops below 100% (or the agreed threshold)
3. Fails the build if branch coverage drops below 100% (or the agreed threshold)
4. Publishes the coverage report as a build artifact

Use the existing CI system (detect from .github/workflows, Jenkinsfile,
.gitlab-ci.yml, etc.). If no CI is configured, set up the coverage gate
as a local pre-push hook instead.
```

### Expected output

Updated CI configuration (or a git hook) that enforces the coverage threshold on every push or PR.

### Checkpoint

- CI pipeline (or hook) includes a coverage gate
- A test push or PR triggers the gate and passes
- Intentionally breaking coverage (commenting out a test) causes the gate to fail

---

## Quick Reference

| Phase | Command | What It Does |
|-------|---------|--------------|
| 0 | `/speckit.constitution` | Ratify testing principles |
| 1 | Plain chat | Discover stack, run baseline, build gap inventory |
| 2 | `/speckit.specify` | Frame coverage as a formal specification |
| 3 | `/speckit.clarify` | Resolve testing ambiguities (mocks, DI, async) |
| 4 | `/speckit.plan` | Design test architecture and batch groupings |
| 5 | `/speckit.tasks` | Generate per-file, per-method test tasks |
| 5.5 | `/speckit.analyze` | Cross-check spec, plan, and tasks for consistency |
| 6 | `/speckit.implement` | Write tests batch-by-batch with self-healing |
| 7 | Plain chat | Final verification and CI gate configuration |

---

## Tips

- **Review and iterate at every step**: The Spec-Driven Development philosophy requires human review after every agent output. If an artifact isn't right, re-run the command with refined input or edit the file directly. Agents are literal-minded -- your review catches what they miss.
- **Start small**: If coverage is very low (below 30%), consider setting an intermediate target (e.g., 80%) and running the pipeline twice -- once for the initial push, once for the final stretch.
- **Review each batch**: Don't let the implement agent run all batches unattended. Review after each batch to catch patterns you want to adjust (mock style, naming, assertion approach).
- **Dead code**: If coverage can't reach 100% because of unreachable code, that's a signal to remove the dead code rather than exclude it.
- **Exclusions**: Generated code (protobuf stubs, annotation processors, framework boilerplate) should be excluded from coverage targets. Document exclusions in the spec.
- **Re-run analyze**: If you modify the spec or plan mid-pipeline, re-run `/speckit.analyze` before continuing implementation.
