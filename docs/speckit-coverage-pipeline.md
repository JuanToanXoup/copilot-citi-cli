# Spec-Kit Coverage Pipeline

## Overview

Single-command tool that autonomously brings unit test coverage to a target percentage.
Each phase produces artifacts consumed by the next. The pipeline stops as soon as the target is met.

Every phase below lists its **questions** (what the phase must answer before it can proceed) and **actions** (the atomic steps it executes). Questions are answered by reading project files, not by prompting the user.

---

## Phase 0: speckit_discover

**Receives:** project path

### Questions

| # | Question | How It Gets Answered | Likely Answers |
|---|----------|----------------------|----------------|
| D1 | What language is the project written in? | Read build file (pom.xml, build.gradle, package.json, pyproject.toml, go.mod) | Java, Kotlin, TypeScript, JavaScript, Python, Go, Scala, C# |
| D2 | What build system is used? | Check which build file exists in the project root | Maven, Gradle (Kotlin DSL), Gradle (Groovy), npm, yarn, pnpm, pip/poetry, Go modules, sbt, dotnet |
| D3 | What framework does the project use? | Parse build file for framework dependencies (Spring Boot, Express, FastAPI, etc.) | Spring Boot, Micronaut, Quarkus, Express, NestJS, FastAPI, Flask, Django, Gin, Fiber, none |
| D4 | What test framework is available? | Parse build file for test dependencies (JUnit, Mockito, Jest, pytest, etc.) | JUnit 5, JUnit 4, TestNG, Kotest, Jest, Mocha, Vitest, pytest, unittest, go test |
| D5 | What mock libraries are available? | Parse build file for mock dependencies (Mockito, MockK, WireMock, Sinon, etc.) | Mockito, MockK, WireMock, Sinon, jest.mock, unittest.mock, testify/mock, none |
| D6 | Does the project use dependency injection? | Parse build file for DI frameworks (Spring, Guice, Dagger, NestJS) | Spring (annotation), Guice, Dagger, NestJS (decorators), manual constructor injection, none |
| D7 | Where do source files live? | Scan for standard source roots (src/main/java, src/, lib/, app/) | src/main/java, src/main/kotlin, src/, lib/, app/, pkg/, internal/, cmd/ |
| D8 | Where do test files live? | Scan for standard test roots (src/test/java, test/, tests/, __tests__/) | src/test/java, src/test/kotlin, test/, tests/, __tests__/, *_test.go (colocated) |
| D9 | How many source files exist vs test files? | Count files by extension in source and test roots | e.g., "142 source, 37 test (ratio 3.8:1)" |
| D10 | What naming convention do existing tests follow? | Read test file names and classify suffixes (*Test, *Spec, test_*, *.test) | *Test.java, *Spec.kt, *.test.ts, *.spec.js, test_*.py, *_test.go |
| D11 | What assertion style do existing tests use? | Read first 3 test files and scan imports/calls (assertThat, assertEquals, expect) | assertThat (AssertJ), assertEquals (JUnit), expect (Jest/Chai), assert (pytest/Go), shouldBe (Kotest) |
| D12 | What mock patterns do existing tests use? | Read first 3 test files and scan for @Mock, @MockBean, mockk, jest.mock | @Mock/@InjectMocks, @MockBean, mockk/every, jest.mock/jest.fn, unittest.mock.patch, manual stubs |
| D13 | Is coverage tooling already configured? | Check build file for JaCoCo, Istanbul, pytest-cov, go cover configuration | JaCoCo plugin present, Istanbul/c8 in devDeps, pytest-cov in deps, built-in (Go), not configured |
| D14 | Do existing coverage reports exist? | Check standard report paths (build/reports/jacoco, coverage/, htmlcov/) | JaCoCo XML found (2 days old), lcov.info found, no reports found |
| D15 | What CI system is configured? | Check for .github/workflows, Jenkinsfile, .gitlab-ci.yml, etc. | GitHub Actions, Jenkins, GitLab CI, Azure DevOps, CircleCI, none |
| D16 | Is this a multi-module project? | Parse build file for module declarations | Single module, multi-module (3 modules), monorepo with workspaces |

### Actions

1. Read the build file (pom.xml, build.gradle.kts, build.gradle, package.json, pyproject.toml, go.mod)
2. Extract language, build system, and framework from build file contents
3. Extract all test-scoped dependencies from build file
4. Extract DI framework from build file
5. Walk source directories and count files by extension
6. Walk test directories and count files by extension
7. Read first 3 test files and extract: imports, annotations, assertion patterns, mock patterns
8. Check standard coverage report paths for existing reports
9. Check for CI configuration files
10. Check for module declarations (Maven modules, Gradle subprojects, workspaces)
11. Compile open questions for anything that could not be auto-detected

### Decision

- Build system detected? **Yes** -> proceed. **No** -> list root directory files, flag for manual command override.

### Produces

`discovery-report.md` saved to memory:
- Build system and language
- Framework and version
- Test dependencies (framework, mocks, assertions)
- DI approach
- Source structure (roots, file counts)
- Test conventions (naming, assertion style, mock patterns)
- Coverage state (configured, existing reports)
- CI configuration
- Open questions

---

## Phase 1: speckit_run_tests + speckit_parse_coverage

**Receives:** discovery report

### Questions

| # | Question | How It Gets Answered | Likely Answers |
|---|----------|----------------------|----------------|
| B1 | What command runs the test suite with coverage? | Derive from discovery report: Maven -> `mvn test jacoco:report`, Gradle -> `./gradlew test jacocoTestReport`, npm -> `npm test -- --coverage`, etc. | `mvn test jacoco:report`, `./gradlew test jacocoTestReport`, `npm test -- --coverage`, `pytest --cov`, `go test -coverprofile=coverage.out ./...` |
| B2 | Does the build system need coverage tooling added first? | Check if coverage tool was detected in discovery. If not, flag it. | Already configured, needs JaCoCo plugin added to pom.xml, needs c8/istanbul added to devDependencies, needs pytest-cov installed |
| B3 | Where will the coverage report be written? | Derive from build system: Maven -> `target/site/jacoco/jacoco.xml`, Gradle -> `build/reports/jacoco/test/jacocoTestReport.xml`, etc. | `target/site/jacoco/jacoco.xml`, `build/reports/jacoco/test/jacocoTestReport.xml`, `coverage/lcov.info`, `coverage/coverage-final.json`, `htmlcov/`, `coverage.out` |
| B4 | What format is the coverage report? | Detect from file extension and content (JaCoCo XML, lcov, Istanbul JSON, Go coverage profile) | JaCoCo XML, Clover XML, LCOV, Istanbul JSON, Cobertura XML, Go coverage profile |
| B5 | What is the current line coverage percentage per file? | Parse the coverage report | e.g., "Overall: 38%. UserService: 12%, OrderController: 45%, Utils: 72%" |

### Actions

1. Determine the test command from the discovery report (or use explicit override if build system was not detected)
2. Execute the test command with coverage enabled
3. Locate the coverage report at the expected path
4. If no report found, check if coverage tooling needs to be configured and report the gap
5. Parse the coverage report and extract per-file line coverage
6. Calculate the aggregate coverage percentage

### Decision

- Coverage >= target? **Yes** -> STOP, report success. **No** -> record baseline number, continue.

### Produces

- Baseline coverage percentage
- Per-file coverage data (file path, lines covered, lines missed, coverage %)
- Coverage report location and format

---

## Phase 2: speckit_constitution

**Receives:** discovery report, existing test samples from discovery, constitution template

### Questions

| # | Question | How It Gets Answered | Likely Answers |
|---|----------|----------------------|----------------|
| C1 | What test file naming convention should generated tests follow? | Read naming patterns from discovery report (e.g., 80% of files use `*Test.java`) | `*Test.java`, `*Spec.kt`, `*.test.ts`, `*.spec.js`, `test_*.py`, `*_test.go` |
| C2 | What assertion library should generated tests use? | Read assertion patterns from discovery (e.g., existing tests use `assertThat` from AssertJ) | AssertJ, Hamcrest, JUnit Assertions, Kotest matchers, Jest expect, Chai, pytest assert, Go testify |
| C3 | Should tests use one assertion per test or multiple? | Read existing test samples: count assertions per test method | Single assertion per test, multiple related assertions, soft assertions (AssertJ SoftAssertions) |
| C4 | What mock approach should generated tests use? | Read mock patterns from discovery (e.g., Mockito with @Mock/@InjectMocks) | Mockito @Mock/@InjectMocks, MockK every/verify, jest.mock auto-mock, unittest.mock.patch, manual stubs, WireMock for HTTP |
| C5 | How should test data be managed? | Read existing test samples: check for builder patterns, fixture files, hardcoded values | Hardcoded inline values, builder pattern (TestDataBuilder), fixture files (JSON/YAML), factory methods, Faker/random generators |
| C6 | How should tests be organized? | Read test directory structure from discovery: mirrors source tree, flat, or by feature | Mirror source tree, flat by feature, grouped by layer (unit/integration), colocated with source |
| C7 | What test method naming convention is used? | Read existing test methods: `shouldDoX`, `testX`, `given_when_then`, `descriptive sentence` | `shouldVerb_whenCondition`, `testMethodName_condition_expected`, `given_when_then`, backtick descriptive (Kotlin), `it("does X")` |
| C8 | Are there non-negotiable project rules already documented? | Check `.specify/memory/constitution.md` for existing constitution | Existing constitution found, no constitution (create from scratch), partial constitution (needs update) |
| C9 | How will flaky or non-deterministic behavior be handled? | Check for retry annotations, test ordering, or random seed handling in existing tests | @RepeatedTest with retry, fixed random seeds, deterministic clocks/timers, @DirtiesContext for Spring, test isolation via fresh instances |
| C10 | How will sensitive or PII data be handled in test inputs? | Check for anonymized data patterns, test data generators, or compliance constraints in discovery | Anonymized placeholder data, Faker-generated values, constants file with synthetic data, no PII in codebase, compliance rules in constitution |

### Actions

1. Load `.specify/templates/constitution-template.md`
2. Load existing constitution at `.specify/memory/constitution.md` if it exists
3. For each question (C1-C10): extract the answer from discovery report and test samples
4. Fill the constitution template with concrete conventions derived from the project
5. For conventions with no existing pattern (new project, no tests), choose sensible defaults matching the framework
6. Write the completed constitution to `.specify/memory/constitution.md`
7. Write a conventions summary to `.specify/memory/test-conventions.md`

### Produces

- `constitution.md` (non-negotiable testing standards)
- `test-conventions.md` (naming, assertions, mocks, data strategy, organization)

---

## Phase 3: speckit_specify

**Receives:** per-file coverage data (from baseline), discovery report, constitution, source file list

### Questions

| # | Question | How It Gets Answered | Likely Answers |
|---|----------|----------------------|----------------|
| S1 | Which files are below the coverage target? | Filter per-file coverage data for files below target % | e.g., "47 of 142 source files below 80% target" |
| S2 | Which of those files contain service layer or business logic? | Cross-reference file paths with source structure (services/, domain/, core/) | Files in service/, domain/, core/, business/, logic/, engine/ directories |
| S3 | Which files contain controllers or API handlers? | Cross-reference file paths with controller/handler directories | Files in controller/, handler/, api/, resource/, endpoint/, routes/ directories |
| S4 | Which files are utilities or helpers? | Cross-reference file paths with util/, helper/, common/ directories | Files in util/, helper/, common/, shared/, support/, lib/ directories |
| S5 | Which files are DTOs, constants, or generated code? | Cross-reference file paths with model/, dto/, generated/ directories or naming patterns | Files in model/, dto/, entity/, generated/, *DTO.java, *Request.java, *Response.java, *Constants.java |
| S6 | For each file, what is the coverage gap in absolute lines? | Calculate (total lines - covered lines) from coverage data | e.g., "OrderService: 120 lines uncovered of 200 total (60% gap)" |
| S7 | What is the estimated effort to cover each file? | Read file complexity: line count, number of methods, number of dependencies | Low (< 5 methods, 0-1 deps), Medium (5-15 methods, 2-4 deps), High (15+ methods, 5+ deps) |
| S8 | Will test cases be derived from requirements, existing code, or both? | Default: existing code (coverage-driven). Override if spec or requirements docs exist. | Existing code only, code + requirements docs, code + API contract specs, code + acceptance criteria |
| S9 | Are there files that should be explicitly excluded from coverage? | Check for generated code markers, annotation processors, configuration classes | @Generated classes, Lombok-generated, protobuf stubs, Spring config classes, migration scripts, *Config.java, *Application.java |

### Actions

1. Load per-file coverage data from Phase 1
2. Load source file list from discovery report
3. For each file below target: read the file and classify its layer (service, controller, utility, DTO)
4. For each file below target: count methods, lines, and external dependencies
5. Rank files by impact tier:
   - CRITICAL: service layer, business logic, domain models
   - HIGH: controllers, API handlers, validation logic
   - MEDIUM: utilities, helpers, configuration
   - LOW: DTOs, constants, generated code
6. Within each tier, sort by coverage gap (largest gap first)
7. Identify files to exclude (generated code, framework boilerplate)
8. Write the gap inventory as `spec.md`

### Produces

`spec.md` containing:
- Coverage target and current baseline
- Files below target, grouped by impact tier
- Per-file: path, current coverage %, gap in lines, method count, dependency count
- Exclusion list with rationale
- Estimated total files to cover

---

## Phase 4: speckit_clarify

**Receives:** spec.md, discovery report, architecture info

### Questions

| # | Question | How It Gets Answered | Likely Answers |
|---|----------|----------------------|----------------|
| CL1 | How will each external dependency be isolated? (DB, HTTP, messaging) | Read each in-scope file's imports; for each external dependency type, determine mock strategy from conventions | DB: mock Repository interface, H2 in-memory, Testcontainers. HTTP: WireMock, mock RestTemplate/WebClient, mock Feign client. Messaging: mock KafkaTemplate, embedded Kafka, mock SQS client |
| CL2 | How will DI be handled in tests? (Spring context, manual wiring, constructor injection) | Read discovery report DI approach; check existing test samples for @SpringBootTest vs @ExtendWith(MockitoExtension) vs manual construction | @ExtendWith(MockitoExtension) + @Mock/@InjectMocks, @SpringBootTest (full context), @WebMvcTest (slice), manual `new Service(mockDep)`, MockK + constructor |
| CL3 | Are there stateful components that require setup and teardown? | Read in-scope files for static state, caches, connection pools, singletons | In-memory caches (clear in @AfterEach), connection pools (mock or embedded), static registries (reset in teardown), thread-local state, singleton instances |
| CL4 | How will async operations be tested synchronously? | Read in-scope files for CompletableFuture, @Async, coroutines, callbacks, event listeners | CompletableFuture.join() in test, @Async disabled in test profile, runBlocking for coroutines, CountDownLatch, Awaitility, direct method call bypassing async wrapper |
| CL5 | How will serialization/deserialization be tested independently? | Read in-scope files for ObjectMapper, JSON annotations, Protobuf, custom serializers | ObjectMapper round-trip test, @JsonTest slice, assertThat(json).isEqualTo(expected), schema validation, Protobuf serialize/parse cycle |
| CL6 | How will service configuration be overridden per test? (feature flags, URLs, timeouts) | Read in-scope files for @Value, ConfigurationProperties, environment variable reads | @TestPropertySource, application-test.yml, constructor parameter injection, ReflectionTestUtils.setField, @MockBean for config class, env var override |
| CL7 | How is the service layer isolated from the transport layer? | Read controller/handler files to check if business logic is in controllers or delegated to services | Thin controllers delegating to services (mock service in controller test), business logic in controllers (test controller directly), mixed (refactor recommended) |
| CL8 | Are there shared domain models or DTOs that need independent testing? | Read model/DTO files for validation annotations, custom equals/hashCode, transformation logic | DTOs with @Valid annotations, entities with custom equals/hashCode, mappers (MapStruct/ModelMapper), value objects with business rules, no testing needed (plain data holders) |
| CL9 | What boundary values and edge cases exist for each method? | Read method signatures: null parameters, empty collections, max integer, empty strings | null inputs, empty string/list/map, Integer.MAX_VALUE, negative numbers, zero, single-element collections, Unicode/special characters, very long strings |
| CL10 | What failure modes should each unit handle? | Read method bodies: catch blocks, error returns, validation checks, thrown exceptions | IllegalArgumentException, EntityNotFoundException, timeout/connection refused, validation constraint violation, null pointer, permission denied, concurrent modification, data integrity violation |

### Actions

1. Load spec.md (gap inventory)
2. For each in-scope file from spec.md:
   a. Read the source file
   b. List all external dependencies (imports of DB clients, HTTP clients, message producers, etc.)
   c. List all injected dependencies (constructor parameters, @Autowired fields)
   d. List all async patterns (futures, coroutines, callbacks)
   e. List all configuration reads (@Value, env vars, properties)
   f. List all serialization points (JSON mapping, protobuf, custom serializers)
3. For each dependency type found, record the resolution:
   - DB -> mock repository interface or use in-memory DB (H2, SQLite)
   - HTTP client -> mock client or use WireMock
   - Message broker -> mock producer/consumer
   - Cache -> mock or use in-memory implementation
4. For each async pattern found, record the synchronous test approach
5. For each config read, record how to override in tests
6. Update spec.md with all resolved decisions in a Clarifications section
7. Validate: are there still ambiguities that could cause wrong test generation?

### Decision

- Ambiguities remain? **Yes** -> loop back, read more source files to resolve. **No** -> proceed.

### Produces

Updated `spec.md` with a Clarifications section containing:
- Per-dependency-type: mock strategy and library to use
- DI test approach (unit test with mocks vs slice test vs full context)
- Async test patterns
- Config override approach
- Boundary values and edge cases per file
- Failure modes per file

---

## Phase 5: speckit_plan

**Receives:** clarified spec.md, constitution, plan template

### Questions

| # | Question | How It Gets Answered | Likely Answers |
|---|----------|----------------------|----------------|
| P1 | What is the test execution order? | Derive from dependency graph: foundational classes first, then services that depend on them | Entities/models first, then repositories, then services, then controllers. Or: leaf nodes first, composite classes last |
| P2 | Are there shared test fixtures or utilities needed? | Read clarified spec for repeated mock patterns or test data across multiple files | Shared mock factory for Repository, common test data builders, base test class with Spring context, shared WireMock setup, none (each test self-contained) |
| P3 | What test base classes or helper methods are needed? | Identify common setup patterns from the clarification (e.g., every service test needs the same mock config) | AbstractServiceTest with common mocks, TestDataFactory with builder methods, MockConfigurationBase, custom assertion helpers, none needed |
| P4 | What is the predicted coverage after all planned tests? | Estimate from gap inventory: sum of uncovered lines that will be addressed | e.g., "Predicted: 83% (from 38% baseline, covering 724 of 800 uncovered lines)" |
| P5 | Which files can be tested in parallel (no shared state)? | Read dependency graph: files with no shared mutable state can be batched together | Independent services (no shared singletons), utility classes, DTOs, stateless validators. Not parallel: classes sharing static state or DB connections |
| P6 | What batch size balances speed vs feedback? | Default to configured batch_size; adjust if files are highly interdependent | 3-5 files (default), 1-2 files (highly coupled), 5-10 files (independent utilities) |
| P7 | Are there integration-level dependencies between test files? | Check if any test file's setup depends on another test file's output or side effects | None (fully isolated unit tests), shared test DB state, shared Spring context (@DirtiesContext needed), shared mock server port |
| P8 | What research is needed before writing tests? | Identify any NEEDS CLARIFICATION from the plan template that wasn't resolved | Third-party API behavior to mock, undocumented business rules, framework-specific test patterns, version-specific API differences |

### Actions

1. Load clarified spec.md, constitution, and `.specify/templates/plan-template.md`
2. Build a dependency graph of in-scope source files (which classes depend on which)
3. Determine execution order: leaves of the dependency graph first (fewest dependencies), then up
4. Identify shared fixtures:
   - Common mock configurations (e.g., mocked repository that multiple services use)
   - Common test data builders
   - Base test class with shared setup
5. Group files into batches of `batch_size`, respecting dependency order
6. For each batch, estimate coverage gain (uncovered lines in those files / total uncovered lines)
7. Resolve any remaining NEEDS CLARIFICATION by reading source code and writing to `research.md`
8. Write the test architecture to `plan.md`
9. Write entity/data relationships to `data-model.md` if relevant
10. Write research findings to `research.md`

### Produces

- `plan.md`: test architecture, execution order, batch groupings, fixture strategy, coverage prediction per batch
- `data-model.md`: entities and relationships relevant to test data
- `research.md`: resolved technical decisions

---

## Phase 6: speckit_tasks + speckit_analyze

**Receives:** plan.md, spec.md, data-model.md

### Questions (speckit_tasks)

| # | Question | How It Gets Answered | Likely Answers |
|---|----------|----------------------|----------------|
| T1 | For each source file, what test file should be created? | Apply naming convention from constitution (e.g., `UserService.java` -> `UserServiceTest.java` in mirrored test directory) | `src/test/java/.../UserServiceTest.java`, `src/test/kotlin/.../UserServiceSpec.kt`, `__tests__/userService.test.ts`, `tests/test_user_service.py` |
| T2 | For each source file, what are the happy path scenarios? | Read each public method: identify normal input -> expected output | Valid input returns expected result, successful save returns entity, valid request returns 200, correct transformation output |
| T3 | For each source file, what are the edge case scenarios? | Read each method: null inputs, empty collections, boundary values, max/min integers | null input, empty list, empty string, zero quantity, max page size, single-character name, duplicate entries, concurrent requests |
| T4 | For each source file, what are the error handling scenarios? | Read each method: catch blocks, thrown exceptions, validation failures, error returns | Missing entity throws NotFoundException, invalid input throws ValidationException, timeout returns fallback, duplicate key throws ConflictException |
| T5 | For each source file, what mocks are needed? | Read constructor parameters and injected fields; each external dependency gets a mock | Mock UserRepository, mock HttpClient, mock KafkaProducer, mock CacheManager, mock ConfigService, mock AuthProvider |
| T6 | For each scenario, what assertions verify correctness? | Derive from method return type and side effects (return value check, mock interaction verification, exception assertion) | assertEquals on return value, verify(mock).save(entity), assertThrows(Exception.class), assertThat(result).hasSize(3), verify no interactions |
| T7 | How will equivalence partitioning reduce redundant cases? | Group inputs that exercise the same code path; pick one representative per partition | All valid names test same path (pick one), all invalid lengths test same validation (pick shortest and longest), all permission levels test same check |
| T8 | How will negative test cases be systematically identified? | For each validation check in the method, create a test that triggers the negative branch | One test per if-condition false branch, one test per catch block, one test per guard clause, one test per validation annotation |
| T9 | Will code paths be traced to ensure every branch has a test? | Read method body; for each if/else, switch, try/catch, create at least one test per branch | if/else: 2 tests, switch with 4 cases: 4 tests + default, try/catch: 1 success + 1 per exception type, ternary: 2 tests |

### Actions (speckit_tasks)

1. Load plan.md, spec.md, data-model.md
2. For each source file in the plan's execution order:
   a. Read the source file
   b. List all public methods with their signatures
   c. For each method: identify happy path, edge cases, error paths, boundary values
   d. For each method: list required mocks (from constructor params and injected fields)
   e. For each scenario: define the assertion (return value, exception, mock interaction)
   f. Apply equivalence partitioning to reduce redundant scenarios
3. Generate task entries in checklist format:
   - `- [ ] [T001] [P] Write tests for UserService.processOrder: happy path, null input, invalid state, timeout`
4. Group tasks by batch from the plan
5. Write `tasks.md`

### Questions (speckit_analyze)

| # | Question | How It Gets Answered | Likely Answers |
|---|----------|----------------------|----------------|
| A1 | Does every in-scope source file have at least one test task? | Cross-reference spec.md file list with tasks.md | All covered, 3 files missing tasks, new files added since spec was written |
| A2 | Does every public method have at least one happy path test? | Cross-reference method list with task scenarios | All methods covered, getters/setters intentionally skipped, 5 methods missing happy path |
| A3 | Does every error handling path have a test? | Cross-reference catch blocks and validation checks with task scenarios | All error paths covered, generic catch blocks skipped, 8 exception paths missing tests |
| A4 | Are there tasks referencing files not in the spec? | Cross-reference task file paths with spec file list | No orphans, 2 tasks reference helper classes not in spec, test utility files not in spec (expected) |
| A5 | Do tasks follow the constitution conventions? | Validate naming, assertion style, mock approach against constitution | All compliant, 4 tasks use wrong naming pattern, mock approach inconsistent in 2 tasks |
| A6 | Are there dependency conflicts in the task ordering? | Validate that no task depends on a later task's output | No conflicts, ServiceTest scheduled before its mock dependency is created, circular dependency between 2 tasks |

### Actions (speckit_analyze)

1. Load spec.md, plan.md, tasks.md, constitution
2. Build a coverage map: each source file -> list of task IDs
3. Identify gaps: source files with zero tasks, methods with no scenarios
4. Identify orphans: tasks referencing files not in the spec
5. Validate task ordering against the dependency graph
6. Validate task format matches constitution conventions
7. Produce an analysis report with: coverage %, gap list, orphan list, ordering issues

### Decision

- Tasks complete (no gaps, no orphans, no ordering issues)? **Yes** -> proceed. **No** -> loop back to speckit_tasks with the gap list.

### Produces

- `tasks.md`: per-file test tasks with scenarios, mock setup, assertions, in checklist format
- Analysis report: coverage %, gaps, validation results

---

## Phase 7: speckit_implement

**Receives:** tasks.md (current batch), plan.md, conventions, source files

### Questions (per batch)

| # | Question | How It Gets Answered | Likely Answers |
|---|----------|----------------------|----------------|
| I1 | What does the source file under test do? | Read the source file | CRUD service, validation logic, data transformation, event handler, API controller, utility functions, state machine |
| I2 | Do existing tests already cover any scenarios? | Read existing test file if it exists; check which methods are already tested | No existing tests, 3 of 8 methods already tested, only happy paths tested (no error paths), integration tests exist but no unit tests |
| I3 | What imports are needed for the test file? | Derive from: test framework, mock library, assertion library, source class imports | JUnit 5 + Mockito + AssertJ, Kotest + MockK, Jest + supertest, pytest + unittest.mock, testing + testify |
| I4 | What mock setup is needed before each test? | Read task's mock list; generate @Mock fields and setup method | @Mock UserRepository + @InjectMocks UserService, mockk<HttpClient>() with every{} stubs, jest.mock('./db') with return values |
| I5 | What is the exact test method body for each scenario? | From the task: construct input, call method, assert output or exception | Arrange: build input object. Act: call service.process(input). Assert: assertEquals(expected, result) or assertThrows(Exception.class) |
| I6 | Does the test compile? | Run the compiler or build command | Compiles successfully, missing import, wrong method signature, type mismatch, unresolved dependency |
| I7 | Does the test pass? | Run the test suite | All pass, assertion failure (expected vs actual mismatch), NPE at line X, mock not configured for call Y, timeout |
| I8 | If it fails, what is the root cause? | Read the test failure output: compilation error, assertion failure, NPE, mock misconfiguration | Missing mock stub (Mockito UnnecessaryStubbingException), wrong expected value, null return from unstubbed mock, Spring context fails to load, wrong argument matcher |
| I9 | What is the new coverage after this batch? | Run tests with coverage and parse the report | e.g., "52% (was 38%, target 80%) -- +14% from batch 1" |
| I10 | Has coverage improved compared to the previous batch? | Compare current coverage % with previous batch's coverage % | Improved by 14%, improved by 3% (diminishing), no change (tests not covering new lines), decreased (test removed or broken) |

### Actions (per batch)

1. For each source file in the batch:
   a. Read the source file
   b. Read existing test file (if any)
   c. Load the task entry for this file from tasks.md
   d. Generate the test file content:
      - Package declaration and imports
      - Class declaration matching naming convention
      - Mock field declarations
      - @BeforeEach setup method with mock initialization
      - One test method per scenario from the task
      - Each test method: arrange (build input, configure mocks), act (call method), assert (verify output/exception/interaction)
   e. Write the test file to the correct path
2. Run the test suite (compile + execute)
3. If tests fail:
   a. Read the failure output
   b. Classify the error (compilation, assertion, mock, NPE, timeout)
   c. Fix the specific error:
      - Compilation: fix imports, types, method signatures
      - Assertion: fix expected values
      - Mock: fix mock setup (missing stub, wrong return type)
      - NPE: add null handling or fix mock initialization
   d. Re-run tests
   e. Repeat up to 3 times per file
4. After all files in the batch pass:
   a. Run full test suite with coverage
   b. Parse coverage report
   c. Record new coverage %
5. Mark completed tasks as `[X]` in tasks.md

### Decisions

1. Tests compile and pass? **No** -> self-heal (fix and retry, max 3 attempts). **Yes** -> measure coverage.
2. Coverage >= target? **Yes** -> STOP. Save learned patterns to memory. Report success.
3. More batches remaining? **Yes** -> loop back to step 1 with next batch.
4. No more batches. Coverage improving? **No** (stuck) -> return to speckit_specify with updated coverage data to reassess gaps. **Yes** (diminishing returns) -> report partial result.

### Produces

- Unit test files (one per source file in the batch)
- Updated tasks.md with completed tasks checked off
- Updated coverage data

---

## Data Flow Summary

```
project path
    |
    v
speckit_discover -------> discovery report
    |                     (language, build, framework, test deps,
    |                      DI, conventions, coverage state, CI)
    v
speckit_run_tests ------> baseline coverage %
speckit_parse_coverage    per-file coverage data
    |
    v
speckit_constitution ---> testing constitution
    |                     test conventions
    v
speckit_specify ---------> spec.md
    |                     (gap inventory by impact tier,
    |                      per-file: coverage %, gap, methods, deps)
    v
speckit_clarify ---------> updated spec.md
    |                     (mock strategies, DI approach, async patterns,
    |                      config overrides, edge cases, failure modes)
    v
speckit_plan ------------> plan.md, data-model.md, research.md
    |                     (execution order, batch groupings,
    |                      fixtures, coverage prediction)
    v
speckit_tasks -----------> tasks.md
speckit_analyze           (per-file: scenarios, mocks, assertions,
    |                      validated for completeness)
    v
speckit_implement -------> unit test files
    |                     (batched, self-healed, coverage-measured)
    v
[loop until target or exhausted]
```

## Decision Points

| After | Condition | Action |
|-------|-----------|--------|
| speckit_discover | Build system not detected | Flag for manual command override |
| speckit_parse_coverage | Coverage >= target | STOP — report success |
| speckit_clarify | Ambiguities remain | Loop back — read more source files |
| speckit_analyze | Tasks incomplete or have gaps | Loop back to speckit_tasks with gap list |
| speckit_implement | Tests fail | Self-heal — fix and retry (max 3 attempts) |
| speckit_implement | Coverage >= target | STOP — save patterns, report success |
| speckit_implement | More batches remaining | Process next batch |
| speckit_implement | No batches left, not improving | Reassess — return to speckit_specify |
| speckit_implement | No batches left, diminishing returns | Report partial result |

## Question Totals

| Phase | Questions | Focus |
|-------|-----------|-------|
| speckit_discover | 16 | Project structure, toolchain, existing patterns |
| speckit_run_tests | 5 | Test command, report location, baseline data |
| speckit_constitution | 10 | Conventions, standards, rules |
| speckit_specify | 9 | Gap identification, impact ranking, exclusions |
| speckit_clarify | 10 | Dependency isolation, DI, async, config, edge cases |
| speckit_plan | 8 | Execution order, batching, fixtures, predictions |
| speckit_tasks | 9 | Per-method scenarios, mocks, assertions, partitioning |
| speckit_analyze | 6 | Completeness, coverage map, ordering validation |
| speckit_implement | 10 | Per-file generation, compilation, self-heal, coverage |
| **Total** | **83** | |
