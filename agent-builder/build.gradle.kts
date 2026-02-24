/**
 * Agent Builder subproject — web UI for visual agent composition.
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
    description = "Install the agent-builder module in editable mode"
    group = "python"
    dependsOn(":upgradePip")
    commandLine(pip, "install", "-e", projectDir.absolutePath)
}

tasks.register<Exec>("start") {
    description = "Launch the Agent Builder web UI"
    group = "application"
    dependsOn(":installDeps")
    val port = (project.findProperty("port") as? String) ?: "8420"
    commandLine(python, "-m", "agent_builder", "--port", port)
}
