/**
 * Root build file for copilot-citi-cli.
 *
 * Orchestrates Python venv creation, dependency installation, testing,
 * linting, and running for the cli and agent-builder subprojects.
 */

val venvDir = rootProject.layout.projectDirectory.dir(".venv")
val isWindows = System.getProperty("os.name").lowercase().contains("win")
val python = if (isWindows)
    venvDir.file("Scripts/python.exe").asFile.absolutePath
else
    venvDir.file("bin/python").asFile.absolutePath

val pip = if (isWindows)
    venvDir.file("Scripts/pip.exe").asFile.absolutePath
else
    venvDir.file("bin/pip").asFile.absolutePath

// Read proxy URL from config.toml [proxy] section or HTTPS_PROXY env var.
// Corporate networks need this for pip to reach pypi.org.
val proxyUrl: String? by lazy {
    val configFile = rootProject.file("cli/src/copilot_cli/config.toml")
    if (configFile.exists()) {
        val lines = configFile.readLines()
        var inProxy = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed == "[proxy]") { inProxy = true; continue }
            if (trimmed.startsWith("[") && inProxy) break
            if (inProxy && trimmed.startsWith("url")) {
                val match = Regex("""url\s*=\s*"(.+?)"""").find(trimmed)
                if (match != null) return@lazy match.groupValues[1]
            }
        }
    }
    System.getenv("HTTPS_PROXY") ?: System.getenv("HTTP_PROXY")
}

// Find a Python >=3.10 interpreter for venv creation.
// Gradle inherits a minimal PATH that may not include /opt/local/bin, /usr/local/bin, etc.
val systemPython: String by lazy {
    val names = listOf("python3.13", "python3.12", "python3.11", "python3.10", "python3")
    val searchDirs = listOf("/opt/local/bin", "/usr/local/bin", "/opt/homebrew/bin", "")
    val candidates = searchDirs.flatMap { dir ->
        names.map { name -> if (dir.isEmpty()) name else "$dir/$name" }
    }
    candidates.firstOrNull { cmd ->
        try {
            val proc = ProcessBuilder(cmd, "--version")
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            val match = Regex("""Python 3\.(\d+)""").find(out)
            match != null && match.groupValues[1].toInt() >= 10
        } catch (_: Exception) { false }
    } ?: "python3"
}

tasks.register<Exec>("createVenv") {
    description = "Create a shared Python virtual environment"
    group = "python"
    commandLine(systemPython, "-m", "venv", venvDir.asFile.absolutePath)
    onlyIf { !venvDir.asFile.exists() }
}

tasks.register<Exec>("upgradePip") {
    description = "Upgrade pip and setuptools in the venv"
    group = "python"
    dependsOn("createVenv")
    val cmd = mutableListOf(python, "-m", "pip", "install", "--upgrade", "pip", "setuptools")
    proxyUrl?.let { cmd.addAll(listOf("--proxy", it)) }
    commandLine(cmd)
}

tasks.register<Exec>("installDeps") {
    description = "Install both modules in editable mode plus dev dependencies"
    group = "python"
    dependsOn("upgradePip")
    val cmd = mutableListOf(
        pip, "install",
        "-e", project(":cli").projectDir.absolutePath,
        "-e", project(":agent-builder").projectDir.absolutePath,
        "pytest", "ruff"
    )
    proxyUrl?.let { cmd.addAll(listOf("--proxy", it)) }
    commandLine(cmd)
}

tasks.register<Exec>("test") {
    description = "Run pytest on the tests/ directory"
    group = "verification"
    dependsOn("installDeps")
    commandLine(python, "-m", "pytest", "tests/", "-v")
}

tasks.register<Exec>("lint") {
    description = "Run ruff check on all source directories"
    group = "verification"
    dependsOn("installDeps")
    commandLine(
        python, "-m", "ruff", "check",
        "cli/src", "agent-builder/src", "tests/"
    )
}

tasks.register<Exec>("run") {
    description = "Run the CLI module (pass args with -Pargs=\"...\")"
    group = "application"
    dependsOn("installDeps")
    val cliArgs = (project.findProperty("args") as? String)
        ?.split("\\s+".toRegex()) ?: emptyList()
    commandLine(listOf(python, "-m", "copilot_cli") + cliArgs)
}

tasks.register<Exec>("runBuilder") {
    description = "Launch the Agent Builder web UI"
    group = "application"
    dependsOn("installDeps")
    commandLine(python, "-m", "agent_builder")
}

tasks.register<Delete>("clean") {
    description = "Remove venv, build artifacts, and caches"
    group = "build"
    delete(venvDir)
    delete(layout.buildDirectory)
    delete(fileTree(rootDir) { include("**/__pycache__") })
    delete(fileTree(rootDir) { include("**/*.egg-info") })
    delete(fileTree(rootDir) { include("**/.pytest_cache") })
}
