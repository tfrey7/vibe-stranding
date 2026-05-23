plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
}

group = "dev.tfrey"
version = "0.2.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1.2")

        bundledPlugin("Git4Idea")
        bundledPlugin("org.jetbrains.plugins.terminal")

        // 2026.x split com.intellij.openapi.ui.InputValidator into its own
        // module; without this it doesn't resolve even though Messages
        // (which references it in its signatures) does.
        bundledModule("intellij.platform.util.ui")

        // Used by the MCP endpoint to read/write JSON-RPC payloads.
        bundledModule("intellij.libraries.kotlinx.serialization.json")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
        }
    }
}

kotlin {
    jvmToolchain(21)
}

ktlint {
    version.set("1.5.0")
}
