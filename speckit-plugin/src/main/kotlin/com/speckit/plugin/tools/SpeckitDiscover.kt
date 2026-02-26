package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import java.io.File

class SpeckitDiscover(private val basePath: String) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_discover",
        "Project discovery and analysis. Scans the project to detect language, framework, build system, test framework, mock patterns, DI approach, existing test conventions, source structure, and coverage state. Must be run before any test generation.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string", "description" to "Service directory — absolute path or relative to project root (default: '.')")
            ),
            "required" to listOf<String>()
        ),
        null,
        "function",
        "enabled"
    )

    override suspend fun handleInvocation(
        request: ToolInvocationRequest
    ): LanguageModelToolResult {
        val path = request.input?.get("path")?.asString ?: "."
        val workDir = when {
            path == "." -> basePath
            path.startsWith("/") -> path
            else -> "$basePath/$path"
        }
        val d = File(workDir)

        if (!d.isDirectory) {
            return LanguageModelToolResult.Companion.error(
                "Directory not found: $workDir (path='$path', basePath='$basePath')"
            )
        }

        val report = buildString {
            appendLine("# Project Discovery Report")
            appendLine("**Path**: $path")
            appendLine()

            // 1. Build system and language
            appendLine("## Build System & Language")
            appendLine(detectBuildSystem(d))
            appendLine()

            // 2. Dependencies and frameworks
            appendLine("## Dependencies & Frameworks")
            appendLine(extractDependencies(d))
            appendLine()

            // 3. Source structure
            appendLine("## Source Structure")
            appendLine(analyzeSourceStructure(d))
            appendLine()

            // 4. Existing test analysis
            appendLine("## Existing Tests")
            appendLine(analyzeExistingTests(d))
            appendLine()

            // 5. Coverage state
            appendLine("## Coverage State")
            appendLine(detectCoverageState(d))
            appendLine()

            // 6. CI configuration
            appendLine("## CI Configuration")
            appendLine(detectCIConfig(d))
            appendLine()

            // 7. Items to resolve from source code (not from the user)
            appendLine("## To Resolve (read project files, do NOT ask user)")
            appendLine(generateOpenQuestions(d))

            // 8. Save instructions
            appendLine("## Next Step — Save Discovery")
            appendLine("1. Call `speckit_read_template` with name `discovery-template.md` to get the template")
            appendLine("2. Fill in ALL fields using the data above + what you resolve from source files")
            appendLine("3. For **Test command** and **Coverage command**: read the actual build file to determine the correct commands — do NOT guess")
            appendLine("4. Call `speckit_write_memory` with name `discovery-report.md` and the filled-in template")
            appendLine("5. This saved report will be used by `speckit_run_tests` and `speckit_parse_coverage`")
        }

        return LanguageModelToolResult.Companion.success(report)
    }

    private fun detectBuildSystem(d: File): String {
        return buildString {
            when {
                d.resolve("pom.xml").exists() -> {
                    appendLine("- **Build system**: Maven")
                    appendLine("- **Build file**: pom.xml")
                    val pom = d.resolve("pom.xml").readText()
                    // Extract language from pom
                    val javaVersion = Regex("<java.version>(.*?)</java.version>").find(pom)?.groupValues?.get(1)
                        ?: Regex("<maven.compiler.source>(.*?)</maven.compiler.source>").find(pom)?.groupValues?.get(1)
                    if (javaVersion != null) appendLine("- **Java version**: $javaVersion")
                    val kotlinPlugin = pom.contains("kotlin-maven-plugin") || pom.contains("org.jetbrains.kotlin")
                    if (kotlinPlugin) appendLine("- **Language**: Kotlin (with Maven)")
                    else appendLine("- **Language**: Java")
                    // Extract packaging
                    val packaging = Regex("<packaging>(.*?)</packaging>").find(pom)?.groupValues?.get(1)
                    if (packaging != null) appendLine("- **Packaging**: $packaging")
                    // Spring Boot parent
                    if (pom.contains("spring-boot-starter-parent") || pom.contains("spring-boot-starter")) {
                        appendLine("- **Framework**: Spring Boot")
                        val sbVersion = Regex("<version>(\\d+\\.\\d+\\.\\d+[^<]*)</version>").findAll(pom)
                            .firstOrNull { it.range.first > pom.indexOf("spring-boot") - 200 && it.range.first < pom.indexOf("spring-boot") + 200 }
                        if (sbVersion != null) appendLine("- **Spring Boot version**: ${sbVersion.groupValues[1]}")
                    }
                    // JaCoCo plugin
                    if (pom.contains("jacoco-maven-plugin")) {
                        appendLine("- **Coverage tool**: JaCoCo (configured in pom.xml)")
                    }
                }
                d.resolve("build.gradle.kts").exists() -> {
                    appendLine("- **Build system**: Gradle (Kotlin DSL)")
                    appendLine("- **Build file**: build.gradle.kts")
                    val gradle = d.resolve("build.gradle.kts").readText()
                    if (gradle.contains("org.jetbrains.kotlin")) appendLine("- **Language**: Kotlin")
                    else appendLine("- **Language**: Java")
                    if (gradle.contains("spring-boot")) appendLine("- **Framework**: Spring Boot")
                    if (gradle.contains("jacoco")) appendLine("- **Coverage tool**: JaCoCo")
                }
                d.resolve("build.gradle").exists() -> {
                    appendLine("- **Build system**: Gradle (Groovy DSL)")
                    appendLine("- **Build file**: build.gradle")
                    val gradle = d.resolve("build.gradle").readText()
                    if (gradle.contains("org.jetbrains.kotlin")) appendLine("- **Language**: Kotlin")
                    else appendLine("- **Language**: Java")
                    if (gradle.contains("spring-boot")) appendLine("- **Framework**: Spring Boot")
                    if (gradle.contains("jacoco")) appendLine("- **Coverage tool**: JaCoCo")
                }
                d.resolve("package.json").exists() -> {
                    appendLine("- **Build system**: npm")
                    appendLine("- **Build file**: package.json")
                    val pkg = d.resolve("package.json").readText()
                    if (pkg.contains("typescript")) appendLine("- **Language**: TypeScript")
                    else appendLine("- **Language**: JavaScript")
                    if (pkg.contains("express")) appendLine("- **Framework**: Express")
                    if (pkg.contains("nestjs") || pkg.contains("@nestjs")) appendLine("- **Framework**: NestJS")
                    if (pkg.contains("jest")) appendLine("- **Test framework**: Jest")
                    if (pkg.contains("mocha")) appendLine("- **Test framework**: Mocha")
                    if (pkg.contains("istanbul") || pkg.contains("nyc") || pkg.contains("c8")) appendLine("- **Coverage tool**: Istanbul/nyc")
                }
                d.resolve("pyproject.toml").exists() -> {
                    appendLine("- **Build system**: Python (pyproject.toml)")
                    appendLine("- **Language**: Python")
                    val toml = d.resolve("pyproject.toml").readText()
                    if (toml.contains("pytest")) appendLine("- **Test framework**: pytest")
                    if (toml.contains("pytest-cov") || toml.contains("coverage")) appendLine("- **Coverage tool**: pytest-cov")
                    if (toml.contains("fastapi")) appendLine("- **Framework**: FastAPI")
                    if (toml.contains("flask")) appendLine("- **Framework**: Flask")
                    if (toml.contains("django")) appendLine("- **Framework**: Django")
                }
                d.resolve("go.mod").exists() -> {
                    appendLine("- **Build system**: Go modules")
                    appendLine("- **Build file**: go.mod")
                    appendLine("- **Language**: Go")
                    appendLine("- **Test framework**: go test (built-in)")
                    appendLine("- **Coverage tool**: go cover (built-in)")
                }
                else -> {
                    appendLine("- **Build system**: NOT DETECTED")
                    appendLine("- Looked for: pom.xml, build.gradle.kts, build.gradle, package.json, pyproject.toml, go.mod")
                    // List what IS in the directory
                    val files = d.listFiles()?.filter { it.isFile }?.map { it.name }?.take(20) ?: emptyList()
                    appendLine("- Root files: ${files.joinToString(", ")}")
                }
            }
        }
    }

    private fun extractDependencies(d: File): String {
        return buildString {
            when {
                d.resolve("pom.xml").exists() -> {
                    val pom = d.resolve("pom.xml").readText()
                    // Test dependencies
                    val testDeps = mutableListOf<String>()
                    if (pom.contains("junit-jupiter") || pom.contains("junit-vintage")) testDeps.add("JUnit 5")
                    if (pom.contains("junit</artifactId>") && !pom.contains("junit-jupiter")) testDeps.add("JUnit 4")
                    if (pom.contains("testng")) testDeps.add("TestNG")
                    if (pom.contains("mockito")) testDeps.add("Mockito")
                    if (pom.contains("mockk")) testDeps.add("MockK")
                    if (pom.contains("wiremock")) testDeps.add("WireMock")
                    if (pom.contains("spring-boot-starter-test")) testDeps.add("Spring Boot Test")
                    if (pom.contains("assertj")) testDeps.add("AssertJ")
                    if (pom.contains("hamcrest")) testDeps.add("Hamcrest")
                    if (pom.contains("rest-assured")) testDeps.add("REST Assured")
                    if (pom.contains("testcontainers")) testDeps.add("Testcontainers")
                    if (pom.contains("cucumber") || pom.contains("io.cucumber")) testDeps.add("Cucumber (BDD)")
                    if (pom.contains("karate")) testDeps.add("Karate")

                    if (testDeps.isNotEmpty()) {
                        appendLine("### Test Dependencies")
                        testDeps.forEach { appendLine("- $it") }
                    } else {
                        appendLine("- No test dependencies detected in pom.xml")
                    }

                    // DI framework
                    appendLine()
                    appendLine("### Dependency Injection")
                    when {
                        pom.contains("spring") -> appendLine("- Spring (annotation-based DI: @Autowired, @Component, etc.)")
                        pom.contains("guice") -> appendLine("- Google Guice")
                        pom.contains("dagger") -> appendLine("- Dagger")
                        pom.contains("cdi") || pom.contains("jakarta.inject") -> appendLine("- CDI / Jakarta Inject")
                        else -> appendLine("- No DI framework detected")
                    }

                    // Modules (multi-module project)
                    val modules = Regex("<module>(.*?)</module>").findAll(pom).map { it.groupValues[1] }.toList()
                    if (modules.isNotEmpty()) {
                        appendLine()
                        appendLine("### Modules")
                        modules.forEach { appendLine("- $it") }
                    }
                }
                d.resolve("build.gradle.kts").exists() || d.resolve("build.gradle").exists() -> {
                    val gradle = (d.resolve("build.gradle.kts").takeIf { it.exists() } ?: d.resolve("build.gradle")).readText()
                    val testDeps = mutableListOf<String>()
                    if (gradle.contains("junit-jupiter") || gradle.contains("useJUnitPlatform")) testDeps.add("JUnit 5")
                    if (gradle.contains("mockito")) testDeps.add("Mockito")
                    if (gradle.contains("mockk")) testDeps.add("MockK")
                    if (gradle.contains("spring-boot-starter-test")) testDeps.add("Spring Boot Test")
                    if (gradle.contains("kotest")) testDeps.add("Kotest")
                    if (testDeps.isNotEmpty()) {
                        appendLine("### Test Dependencies")
                        testDeps.forEach { appendLine("- $it") }
                    }
                }
                d.resolve("package.json").exists() -> {
                    val pkg = d.resolve("package.json").readText()
                    val testDeps = mutableListOf<String>()
                    if (pkg.contains("\"jest\"")) testDeps.add("Jest")
                    if (pkg.contains("\"mocha\"")) testDeps.add("Mocha")
                    if (pkg.contains("\"chai\"")) testDeps.add("Chai")
                    if (pkg.contains("\"sinon\"")) testDeps.add("Sinon")
                    if (pkg.contains("\"supertest\"")) testDeps.add("Supertest")
                    if (testDeps.isNotEmpty()) {
                        appendLine("### Test Dependencies")
                        testDeps.forEach { appendLine("- $it") }
                    }
                }
                else -> appendLine("- Cannot extract dependencies (build file not recognized)")
            }
        }
    }

    private fun analyzeSourceStructure(d: File): String {
        return buildString {
            // List top-level directories
            val dirs = d.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "node_modules" && it.name != "target" && it.name != "build" }
                ?.map { it.name }?.sorted() ?: emptyList()
            appendLine("### Top-Level Directories")
            dirs.forEach { appendLine("- $it/") }
            appendLine()

            // Detect source roots
            appendLine("### Source Roots")
            val sourceRoots = listOf(
                "src/main/java", "src/main/kotlin", "src/main/scala",
                "src/main/groovy", "src", "lib", "app"
            )
            for (root in sourceRoots) {
                val rootDir = d.resolve(root)
                if (rootDir.isDirectory) {
                    val count = countSourceFiles(rootDir)
                    appendLine("- **$root/**: $count source files")
                }
            }
            appendLine()

            // Detect test roots
            appendLine("### Test Roots")
            val testRoots = listOf(
                "src/test/java", "src/test/kotlin", "src/test/scala",
                "src/test/groovy", "test", "tests", "__tests__",
                "spec", "api-tests"
            )
            for (root in testRoots) {
                val rootDir = d.resolve(root)
                if (rootDir.isDirectory) {
                    val count = countSourceFiles(rootDir)
                    appendLine("- **$root/**: $count test files")
                    // Show subdirectory structure
                    val subdirs = rootDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
                    if (subdirs.isNotEmpty()) {
                        appendLine("  Subdirectories: ${subdirs.joinToString(", ")}")
                    }
                }
            }
        }
    }

    private fun countSourceFiles(dir: File): Int {
        val extensions = setOf("java", "kt", "scala", "groovy", "ts", "js", "py", "go", "rs", "cs")
        return dir.walkTopDown()
            .filter { it.isFile && it.extension in extensions }
            .count()
    }

    private fun analyzeExistingTests(d: File): String {
        return buildString {
            // Find test files
            val testDirs = listOf("src/test", "test", "tests", "__tests__", "spec", "api-tests")
                .map { d.resolve(it) }
                .filter { it.isDirectory }

            if (testDirs.isEmpty()) {
                appendLine("- **No test directories found**")
                return@buildString
            }

            // Sample test files to detect patterns
            val testFiles = testDirs.flatMap { dir ->
                dir.walkTopDown()
                    .filter { it.isFile && it.extension in setOf("java", "kt", "ts", "js", "py", "go") }
                    .toList()
            }

            appendLine("- **Test file count**: ${testFiles.size}")

            if (testFiles.isEmpty()) {
                appendLine("- No test source files found in test directories")
                return@buildString
            }

            // Naming patterns
            appendLine()
            appendLine("### Naming Patterns")
            val nameSuffixes = mutableMapOf<String, Int>()
            for (f in testFiles) {
                val name = f.nameWithoutExtension
                when {
                    name.endsWith("Test") -> nameSuffixes.merge("*Test", 1) { a, b -> a + b }
                    name.endsWith("Tests") -> nameSuffixes.merge("*Tests", 1) { a, b -> a + b }
                    name.endsWith("Spec") -> nameSuffixes.merge("*Spec", 1) { a, b -> a + b }
                    name.startsWith("test_") -> nameSuffixes.merge("test_*", 1) { a, b -> a + b }
                    name.endsWith("_test") -> nameSuffixes.merge("*_test", 1) { a, b -> a + b }
                    name.endsWith(".test") -> nameSuffixes.merge("*.test", 1) { a, b -> a + b }
                    name.endsWith(".spec") -> nameSuffixes.merge("*.spec", 1) { a, b -> a + b }
                    name.contains("IT") -> nameSuffixes.merge("*IT (integration)", 1) { a, b -> a + b }
                }
            }
            nameSuffixes.entries.sortedByDescending { it.value }.forEach {
                appendLine("- ${it.key}: ${it.value} files")
            }

            // Sample a few test files to detect patterns
            appendLine()
            appendLine("### Convention Samples (first 3 test files)")
            val samples = testFiles.take(3)
            for (sample in samples) {
                val relativePath = sample.relativeTo(d).path
                val content = sample.readText()
                val lines = content.lines()
                val annotations = mutableSetOf<String>()
                val imports = mutableListOf<String>()

                for (line in lines.take(50)) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("import ")) imports.add(trimmed)
                    if (trimmed.startsWith("@Test")) annotations.add("@Test")
                    if (trimmed.startsWith("@ParameterizedTest")) annotations.add("@ParameterizedTest")
                    if (trimmed.startsWith("@MockBean") || trimmed.startsWith("@Mock")) annotations.add("@Mock/@MockBean")
                    if (trimmed.startsWith("@InjectMocks")) annotations.add("@InjectMocks")
                    if (trimmed.startsWith("@SpringBootTest")) annotations.add("@SpringBootTest")
                    if (trimmed.startsWith("@WebMvcTest")) annotations.add("@WebMvcTest")
                    if (trimmed.startsWith("@DataJpaTest")) annotations.add("@DataJpaTest")
                    if (trimmed.contains("Mockito.")) annotations.add("Mockito")
                    if (trimmed.contains("mockk") || trimmed.contains("MockK")) annotations.add("MockK")
                    if (trimmed.contains("assertThat")) annotations.add("assertThat (AssertJ/Hamcrest)")
                    if (trimmed.contains("assertEquals")) annotations.add("assertEquals (JUnit)")
                    if (trimmed.contains("expect(")) annotations.add("expect() (Jest/Chai)")
                    if (trimmed.contains("describe(") || trimmed.contains("it(")) annotations.add("describe/it (BDD style)")
                }

                appendLine()
                appendLine("**$relativePath** (${lines.size} lines)")
                if (annotations.isNotEmpty()) {
                    appendLine("  Patterns: ${annotations.joinToString(", ")}")
                }
            }
        }
    }

    private fun detectCoverageState(d: File): String {
        return buildString {
            val candidates = listOf(
                "build/reports/jacoco/test/jacocoTestReport.xml" to "JaCoCo XML",
                "build/reports/jacoco/test/html/index.html" to "JaCoCo HTML",
                "target/site/jacoco/jacoco.xml" to "JaCoCo XML (Maven)",
                "target/site/jacoco/index.html" to "JaCoCo HTML (Maven)",
                "coverage/lcov.info" to "LCOV",
                "coverage/coverage-final.json" to "Istanbul JSON",
                "coverage/clover.xml" to "Clover XML",
                "htmlcov/coverage.json" to "Python coverage JSON",
                "coverage.out" to "Go coverage profile",
            )

            var found = false
            for ((path, format) in candidates) {
                val f = d.resolve(path)
                if (f.exists()) {
                    appendLine("- **Report found**: $path ($format)")
                    appendLine("- **Last modified**: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(f.lastModified()))}")
                    appendLine("- **Size**: ${f.length()} bytes")
                    found = true
                }
            }

            if (!found) {
                appendLine("- **No existing coverage reports found**")
                appendLine("- Tests may need to be run with coverage enabled first")
            }
        }
    }

    private fun detectCIConfig(d: File): String {
        return buildString {
            val ciFiles = listOf(
                ".github/workflows" to "GitHub Actions",
                ".gitlab-ci.yml" to "GitLab CI",
                "Jenkinsfile" to "Jenkins",
                "pipeline.yaml" to "Pipeline YAML",
                "pipeline.yml" to "Pipeline YAML",
                ".circleci/config.yml" to "CircleCI",
                "azure-pipelines.yml" to "Azure DevOps",
                "bitbucket-pipelines.yml" to "Bitbucket Pipelines",
                ".travis.yml" to "Travis CI",
                "Dockerfile" to "Docker",
            )

            var found = false
            for ((path, name) in ciFiles) {
                val f = d.resolve(path)
                if (f.exists()) {
                    appendLine("- **$name**: $path")
                    found = true
                }
            }

            // List pipeline files specifically
            val pipelineFiles = d.listFiles()?.filter {
                it.isFile && (it.name.contains("pipeline") || it.name.contains("Pipeline")) &&
                (it.name.endsWith(".yml") || it.name.endsWith(".yaml"))
            }?.map { it.name } ?: emptyList()

            if (pipelineFiles.isNotEmpty()) {
                appendLine("- **Pipeline files**: ${pipelineFiles.joinToString(", ")}")
            }

            if (!found && pipelineFiles.isEmpty()) {
                appendLine("- No CI configuration detected")
            }
        }
    }

    private fun generateOpenQuestions(d: File): String {
        return buildString {
            appendLine("Resolve the following by reading project files (do NOT ask the user):")
            appendLine()

            val hasTests = listOf("src/test", "test", "tests").any { d.resolve(it).isDirectory }

            appendLine("### Resolve from build file and source code")
            appendLine("- Scope: Read source directories to identify service layer, domain logic, and utility packages")
            appendLine("- Mock strategy: Check existing test imports for mock libraries and patterns already in use")
            appendLine("- Test data: Check existing tests for builder patterns, fixture files, or hardcoded values")
            appendLine("- Async: Scan source files for CompletableFuture, @Async, coroutines, callbacks")
            appendLine("- Config overrides: Scan for @Value, @ConfigurationProperties, environment variable reads")
            appendLine()

            if (!hasTests) {
                appendLine("### Note")
                appendLine("- No existing tests found — tests will be created from scratch using framework defaults")
            }
        }
    }
}
