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
    compileOnly(libs.gson)
}

fun findCopilotPlugin(): String {
    val explicit = providers.gradleProperty("copilotPluginPath").orNull
    if (explicit != null && file(explicit).exists()) return explicit

    val home = System.getProperty("user.home")
    val candidates = listOf(
        "$home/Library/Application Support/JetBrains",
        "$home/.local/share/JetBrains",
        "${System.getenv("APPDATA") ?: "$home/AppData/Roaming"}/JetBrains",
    )
    val idePatterns = listOf("IntelliJIdea*", "IdeaIC*")
    val versionRegex = Regex("""github-copilot-intellij-(\d+\.\d+\.\d+)-\d+\.jar""")

    // Collect all Copilot plugin dirs with their version
    data class CopilotCandidate(val dir: String, val version: List<Int>)
    val found = mutableListOf<CopilotCandidate>()

    for (base in candidates) {
        val baseDir = file(base)
        if (!baseDir.isDirectory) continue
        for (pattern in idePatterns) {
            baseDir.listFiles { f -> f.isDirectory && f.name.matches(Regex(pattern.replace("*", ".*"))) }
                ?.forEach { ideDir ->
                    val copilotDir = file("${ideDir.absolutePath}/plugins/github-copilot-intellij")
                    if (!copilotDir.isDirectory) return@forEach
                    val libDir = file("${copilotDir.absolutePath}/lib")
                    if (!libDir.isDirectory) return@forEach
                    val versionParts = libDir.listFiles()
                        ?.mapNotNull { jar -> versionRegex.find(jar.name)?.groupValues?.get(1) }
                        ?.firstOrNull()
                        ?.split(".")
                        ?.map { it.toIntOrNull() ?: 0 }
                        ?: return@forEach
                    found.add(CopilotCandidate(copilotDir.absolutePath, versionParts))
                }
        }
    }

    if (found.isNotEmpty()) {
        val best = found.sortedWith(compareByDescending<CopilotCandidate> { it.version.getOrElse(0) { 0 } }
            .thenByDescending { it.version.getOrElse(1) { 0 } }
            .thenByDescending { it.version.getOrElse(2) { 0 } }).first()
        logger.lifecycle("Using Copilot plugin ${best.version.joinToString(".")} from ${best.dir}")
        return best.dir
    }

    error("""
        Cannot find GitHub Copilot plugin. Set copilotPluginPath in one of:
          - speckit-plugin/local.properties  (copilotPluginPath=/path/to/github-copilot-intellij)
          - speckit-plugin/gradle.properties
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
