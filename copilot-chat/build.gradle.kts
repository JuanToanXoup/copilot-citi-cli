plugins {
    id("java")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinSerialization)
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
    // Kotlinx Serialization for JSON-RPC messages
    implementation(libs.kotlinx.serialization.json)

    // TOML config parsing
    implementation(libs.tomlj)
}

intellij {
    pluginName.set(providers.gradleProperty("pluginName"))
    version.set(providers.gradleProperty("platformVersion"))
    type.set(providers.gradleProperty("platformType"))

    val bundledPlugins = providers.gradleProperty("platformBundledPlugins").map {
        it.split(',').filter { s -> s.isNotBlank() }
    }
    val marketplacePlugins = providers.gradleProperty("platformPlugins").map {
        it.split(',').filter { s -> s.isNotBlank() }
    }
    plugins.set(bundledPlugins.zip(marketplacePlugins) { bundled, marketplace -> bundled + marketplace })
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

    register<JavaExec>("ragTest") {
        group = "verification"
        description = "Run RAG integration test (token exchange, embeddings, Qdrant lifecycle, round-trip)"
        mainClass.set("RagIntegrationTestKt")
        classpath = sourceSets["test"].runtimeClasspath
    }
}
