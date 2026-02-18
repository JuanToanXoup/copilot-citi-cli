/**
 * Agent Builder subproject — web UI for visual agent composition.
 */

val venvDir = rootProject.layout.projectDirectory.dir(".venv")
val pip = if (System.getProperty("os.name").lowercase().contains("win"))
    venvDir.file("Scripts/pip.exe").asFile.absolutePath
else
    venvDir.file("bin/pip").asFile.absolutePath

tasks.register<Exec>("install") {
    description = "Install the agent-builder module in editable mode"
    group = "python"
    dependsOn(":upgradePip")
    commandLine(pip, "install", "-e", projectDir.absolutePath)
}
