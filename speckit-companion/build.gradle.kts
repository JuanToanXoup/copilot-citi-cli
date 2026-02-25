plugins {
    id("java")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intellij)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

dependencies {
    // Gson — matches Copilot plugin's serialization (ToolInvocationRequest.input is JsonObject)
    compileOnly(libs.gson)
}

intellij {
    pluginName.set(providers.gradleProperty("pluginName"))
    version.set(providers.gradleProperty("platformVersion"))
    type.set(providers.gradleProperty("platformType"))

    // Local path to the official GitHub Copilot plugin distribution
    val copilotPath = providers.gradleProperty("copilotPluginPath")
    plugins.set(copilotPath.map { listOf(it) })
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    patchPluginXml {
        version.set(providers.gradleProperty("pluginVersion"))
        sinceBuild.set(providers.gradleProperty("pluginSinceBuild"))
        untilBuild.set("")
    }

    buildSearchableOptions {
        enabled = false
    }
}
