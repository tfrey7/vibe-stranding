package dev.tfrey.vibestranding.ui

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import dev.tfrey.vibestranding.core.Settings

/**
 * Settings → Tools → Vibe Stranding. Landing spot for per-project toggles.
 */
class SettingsConfigurable(private val project: Project) : BoundConfigurable("Vibe Stranding") {
    override fun createPanel(): DialogPanel {
        val settings = Settings.get(project)
        return panel {
            row {
                checkBox("Resume all strands when this project opens")
                    .comment(
                        "When enabled, every strand worktree gets its terminal tab restored " +
                            "(running <code>claude --continue</code>) on project open.",
                    )
                    .bindSelected(settings::resumeStrandsOnStartup)
            }
            row("Generate strand description after:") {
                intTextField(range = 1..1440)
                    .columns(4)
                    .bindIntText(settings::descriptionDelayMinutes)
                @Suppress("DialogTitleCapitalization")
                label("minutes")
            }.rowComment(
                "How long to wait after a strand is created before asking the LM to write a " +
                    "one-sentence description of what it's about. Strands with no diff at that " +
                    "point reschedule for the same interval.",
            )
        }
    }
}
