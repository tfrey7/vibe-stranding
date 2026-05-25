package dev.tfrey.vibestranding

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On project open, reopen a terminal tab for every existing strand so each
 * worktree's claude session picks up where it left off (`claude --continue`,
 * matching the manual Resume Strand action). Gated by
 * [VibeStrandingSettings.resumeStrandsOnStartup], which defaults to true.
 *
 * Each [resumeStrand] call already wraps its git/symlink/hook work in a
 * background task, so this activity just kicks them off and returns; the
 * IDE startup path isn't blocked on N shell launches. The dispatcher hop to
 * EDT exists because [resumeStrand]'s early `focusTabForStrand` short-circuit
 * touches the Terminal tool window, which is EDT-only — every other caller
 * was already on EDT (action handlers).
 */
class ResumeAllStrandsActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!VibeStrandingSettings.get(project).resumeStrandsOnStartup) return
        val svc = project.getService(GitStrands::class.java)
        val strands = svc.listStrands()
        if (strands.isEmpty()) return
        withContext(Dispatchers.EDT) {
            strands.forEach { resumeStrand(project, svc, it) }
        }
    }
}
