package dev.tfrey.vibestranding.core

import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * The project's basePath is itself a strand worktree spawned from a sibling
 * IDE window's main checkout — detected by reading the worktree's `.git`
 * pointer file. Exposes everything the UI needs to act on the worktree-self
 * (resume its terminal tab, read its sidecar metadata) without going through
 * [GitStrands], whose path math assumes "this project IS the main checkout."
 */
data class StrandWorktreeContext(val strand: String, val mainCheckout: Path, val gitDir: Path, val sidecarPath: Path) {
    fun readMeta(): StrandMeta? = WorktreeSidecarStore { _ -> sidecarPath }.read(strand)
}

object StrandWorktree {
    /**
     * Returns a context when this project's `.git` is a worktree pointer
     * (`gitdir: <main>/.git/worktrees/<strand>`) and the slug matches the
     * plugin's [STRAND_NAME] shape; otherwise null. Cheap enough to call from
     * `AnAction.update` on BGT — one file read of a few bytes.
     */
    fun detect(project: Project): StrandWorktreeContext? {
        val base = project.basePath?.let { Path.of(it) } ?: return null
        val gitFile = base.resolve(".git")
        if (!gitFile.isRegularFile()) return null
        val text = runCatching { Files.readString(gitFile) }.getOrNull() ?: return null
        val line = text.lineSequence().firstOrNull { it.startsWith("gitdir:") } ?: return null
        val gitDir = runCatching { Path.of(line.removePrefix("gitdir:").trim()) }.getOrNull() ?: return null
        val worktreesDir = gitDir.parent ?: return null
        if (worktreesDir.fileName?.toString() != "worktrees") return null
        val dotGit = worktreesDir.parent ?: return null
        if (dotGit.fileName?.toString() != ".git") return null
        val mainCheckout = dotGit.parent ?: return null
        val strand = gitDir.fileName?.toString() ?: return null
        if (!STRAND_NAME.matches(strand)) return null
        return StrandWorktreeContext(strand, mainCheckout, gitDir, gitDir.resolve("vibe-stranding.json"))
    }
}
