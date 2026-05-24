plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
}

group = "dev.tfrey"
version = "0.4.0"

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

        // Used by the strand summary tool window to render claude's markdown
        // output as styled HTML (via JBHtmlPane).
        bundledModule("intellij.libraries.markdown")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
        }
        // Latest section of CHANGELOG.md → bundled plugin.xml's
        // <change-notes>. Same section is fed to `gh release create
        // --notes-file` during publish, so the GH Pages workflow
        // forwards it into updatePlugins.xml too.
        changeNotes = provider { latestChangelogSection() }
    }
}

fun latestChangelogSection(): String {
    val text = file("CHANGELOG.md").readText()
    val firstHeading = text.indexOf("\n## ")
    if (firstHeading < 0) return ""
    val afterHeadingLine = text.indexOf('\n', firstHeading + 1)
    if (afterHeadingLine < 0) return ""
    val nextHeading = text.indexOf("\n## ", afterHeadingLine)
    val body = if (nextHeading < 0) text.substring(afterHeadingLine) else text.substring(afterHeadingLine, nextHeading)
    return body.trim()
}

kotlin {
    jvmToolchain(21)
}

ktlint {
    version.set("1.5.0")
}
