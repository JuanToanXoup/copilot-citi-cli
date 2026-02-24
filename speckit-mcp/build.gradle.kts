/**
 * SpecKit MCP subproject — MCP server for spec-driven development tools.
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

tasks.register<Exec>("install") {
    description = "Install the speckit-mcp module in editable mode"
    group = "python"
    dependsOn(":upgradePip")
    commandLine(pip, "install", "-e", projectDir.absolutePath)
}

tasks.register<Exec>("start") {
    description = "Launch the SpecKit MCP server (stdio)"
    group = "application"
    dependsOn(":installDeps")
    commandLine(python, "-m", "speckit_mcp")
}
