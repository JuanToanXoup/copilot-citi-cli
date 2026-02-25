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

// Auto-detect the GitHub Copilot plugin from the local IDE installation.
// Override with -PcopilotPluginPath=... or in local.properties.
fun findCopilotPlugin(): String {
    // 1. Explicit property (gradle.properties, local.properties, or -P flag)
    val explicit = providers.gradleProperty("copilotPluginPath").orNull
    if (explicit != null && file(explicit).exists()) return explicit

    // 2. Search common IDE plugin directories
    val home = System.getProperty("user.home")
    val candidates = listOf(
        // macOS
        "$home/Library/Application Support/JetBrains",
        // Linux
        "$home/.local/share/JetBrains",
        // Windows
        "${System.getenv("APPDATA") ?: "$home/AppData/Roaming"}/JetBrains",
    )
    val idePatterns = listOf("IntelliJIdea*", "IdeaIC*")
    for (base in candidates) {
        val baseDir = file(base)
        if (!baseDir.isDirectory) continue
        for (pattern in idePatterns) {
            baseDir.listFiles { f -> f.isDirectory && f.name.matches(Regex(pattern.replace("*", ".*"))) }
                ?.sortedByDescending { it.name }
                ?.forEach { ideDir ->
                    val copilotDir = file("${ideDir.absolutePath}/plugins/github-copilot-intellij")
                    if (copilotDir.isDirectory) return copilotDir.absolutePath
                }
        }
    }

    error("""
        Cannot find GitHub Copilot plugin. Set copilotPluginPath in one of:
          - speckit-companion/local.properties  (copilotPluginPath=/path/to/github-copilot-intellij)
          - speckit-companion/gradle.properties
          - command line: ./gradlew build -PcopilotPluginPath=/path/to/github-copilot-intellij
    """.trimIndent())
}

intellij {
    pluginName.set(providers.gradleProperty("pluginName"))
    version.set(providers.gradleProperty("platformVersion"))
    type.set(providers.gradleProperty("platformType"))

    plugins.set(listOf(findCopilotPlugin()))
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
