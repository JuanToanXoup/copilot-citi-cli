# Discovery Report: [PROJECT_NAME]

**Path**: [PROJECT_PATH]
**Discovered**: [DATE]
**Status**: [COMPLETE | PARTIAL]

## Build System

- **Build tool**: [e.g., Gradle (Kotlin DSL), Maven, npm, Go modules]
- **Build file**: [e.g., build.gradle.kts, pom.xml, package.json]
- **Language**: [e.g., Java 17, Kotlin 1.9, TypeScript 5.x, Python 3.11, Go 1.22]
- **Framework**: [e.g., Spring Boot 3.2, Express, FastAPI, NestJS, or NONE]
- **Packaging**: [e.g., jar, war, docker, binary, or N/A]
- **Modules**: [list submodules if multi-module, or SINGLE_MODULE]

## Test Configuration

<!--
  CRITICAL: These fields drive speckit_run_tests and speckit_parse_coverage.
  Read the actual build file to determine the correct commands — do NOT guess.
  For Gradle: check available tasks with ./gradlew tasks | grep -i test
  For Maven: check plugins in pom.xml
  For npm: check scripts.test in package.json
  For Python: check pyproject.toml [tool.pytest] section
-->

- **Test framework**: [e.g., JUnit 5, TestNG, Jest, Mocha, pytest, go test]
- **Test command**: [e.g., ./gradlew test, mvn test, npm test, pytest, go test ./...]
- **Coverage tool**: [e.g., JaCoCo, Kover, Istanbul/nyc, c8, pytest-cov, go cover]
- **Coverage command**: [e.g., ./gradlew test jacocoTestReport, npm test -- --coverage]
- **Coverage report path**: [e.g., build/reports/jacoco/test/jacocoTestReport.xml]
- **Coverage report format**: [e.g., JaCoCo XML, LCOV, Istanbul JSON, Go coverage profile]

## Test Conventions

<!--
  Read 2-3 existing test files to extract these patterns.
  If no tests exist, write NONE and note framework defaults.
-->

- **Naming pattern**: [e.g., *Test.java, *Spec.kt, *.test.ts, test_*.py]
- **Assertion style**: [e.g., AssertJ assertThat, JUnit assertEquals, Jest expect, pytest assert]
- **Mock library**: [e.g., Mockito, MockK, Jest mocks, unittest.mock, WireMock]
- **Mock pattern**: [e.g., @Mock + @InjectMocks, @MockBean + @SpringBootTest, jest.mock()]
- **DI approach**: [e.g., Spring @Autowired, constructor injection, Guice @Inject, manual]
- **Test data**: [e.g., builders, fixtures, hardcoded, factory methods, @ParameterizedTest]
- **Test organization**: [e.g., mirrors src/ package structure, flat, by feature]

## Source Structure

- **Source roots**: [e.g., src/main/java, src/main/kotlin, src/, lib/]
- **Test roots**: [e.g., src/test/java, src/test/kotlin, test/, __tests__/]
- **Source file count**: [number]
- **Test file count**: [number]
- **Key packages**: [list top-level packages/directories in source root]

## Async & Config Patterns

<!--
  Scan source files for these patterns. Write NONE if not found.
-->

- **Async patterns**: [e.g., CompletableFuture, @Async, coroutines, Mono/Flux, Promises, callbacks, or NONE]
- **Config reads**: [e.g., @Value, @ConfigurationProperties, process.env, os.environ, viper, or NONE]
- **Config override strategy**: [e.g., @TestPropertySource, .env.test, test fixtures, or UNKNOWN]

## Coverage State

- **Existing report**: [YES path/to/report | NO]
- **Last report date**: [date or N/A]
- **Current coverage**: [percentage if parseable, or UNKNOWN]

## CI Configuration

- **CI system**: [e.g., GitHub Actions, GitLab CI, Jenkins, or NONE detected]
- **CI test step**: [e.g., runs ./gradlew test, or UNKNOWN]
