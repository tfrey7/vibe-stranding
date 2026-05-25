package dev.tfrey.vibestranding.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.tfrey.vibestranding.core.RESUME_COMMAND
import dev.tfrey.vibestranding.core.StrandHandoff
import dev.tfrey.vibestranding.core.tabLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Consume any [StrandHandoff] entry stashed for this project's basePath and
 * open a `claude --continue` terminal tab in the project. Pairs with
 * [ViewThisStrandsCodeAction], which stashes the entry and closes the source
 * tab in the main window before opening the worktree as its own project.
 *
 * No-op when no entry is pending — projects opened directly by the user
 * (not via View Code) are left alone.
 */
class StrandHandoffActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val basePath = project.basePath ?: return
        val pending = StrandHandoff.consume(basePath) ?: return
        withContext(Dispatchers.EDT) {
            TerminalTabs.openTerminalTab(
                project,
                basePath,
                tabLabel(pending.emoji, pending.strand),
                RESUME_COMMAND,
                pending.strand,
                pending.background,
            )
        }
    }
}
