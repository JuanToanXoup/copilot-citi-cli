/**
 * Copilot Electron subproject — desktop UI shell (Electron + Vite + React).
 *
 * Wraps npm lifecycle commands as Gradle tasks via the node-gradle plugin.
 */

plugins {
    id("com.github.node-gradle.node") version "7.1.0"
}

node {
    download = true
    version = "20.18.1"
}

tasks.register<com.github.gradle.node.npm.task.NpmTask>("build") {
    description = "Build all Vite targets (renderer, main, preload)"
    group = "build"
    dependsOn("npmInstall")
    npmCommand = listOf("run", "build")
}

tasks.register<com.github.gradle.node.npm.task.NpmTask>("dev") {
    description = "Start Vite dev server with Electron"
    group = "application"
    dependsOn("npmInstall")
    npmCommand = listOf("run", "dev")
}

tasks.register<com.github.gradle.node.npm.task.NpmTask>("package") {
    description = "Package the app with electron-builder"
    group = "distribution"
    dependsOn("build")
    npmCommand = listOf("run", "package")
}

tasks.register<Delete>("clean") {
    description = "Remove dist/ and node_modules/"
    group = "build"
    delete("dist")
    delete("node_modules")
}

tasks.register<com.github.gradle.node.npm.task.NpmTask>("viteBuild") {
    description = "Build all Vite targets"
    group = "build"
    dependsOn("npmInstall")
    npmCommand = listOf("run", "build")
}

tasks.register<com.github.gradle.node.npm.task.NpxTask>("viteBuildAndRun") {
    description = "Build with Vite and run Electron"
    group = "application"
    dependsOn("viteBuild")
    command = "electron"
    args = listOf("dist/main/index.js")
}