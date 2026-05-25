package dev.tfrey.vibestranding

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

/**
 * Settings → Tools → Vibe Stranding. A single checkbox today; gives us a
 * landing spot for future per-project toggles without re-doing the wiring.
 */
class VibeStrandingConfigurable(private val project: Project) : BoundConfigurable("Vibe Stranding") {
    override fun createPanel(): DialogPanel {
        val settings = VibeStrandingSettings.get(project)
        return panel {
            row {
                checkBox("Resume all strands when this project opens")
                    .comment(
                        "When enabled, every strand worktree gets its terminal tab restored " +
                            "(running <code>claude --continue</code>) on project open.",
                    )
                    .bindSelected(settings::resumeStrandsOnStartup)
            }
        }
    }
}
