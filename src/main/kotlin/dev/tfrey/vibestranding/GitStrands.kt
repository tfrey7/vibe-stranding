package dev.tfrey.vibestranding

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import git4idea.config.GitExecutableManager
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name

private val LOG = logger<GitStrands>()

/**
 * All git/worktree logic. Methods that shell out to git MUST be called off the
 * EDT (the actions wrap them in background tasks).
 *
 * Layout:
 *   main checkout = project.basePath
 *   strands root  = <parent>/<projectName>-strands
 *   strand branch = strand/<name>
 *   strand dir    = <strands root>/<name>
 */
@Service(Service.Level.PROJECT)
class GitStrands(private val project: Project) {

    companion object {
        private const val GIT_TIMEOUT_MS = 120_000

        // Paths the plugin symlinks into every new strand from the main checkout.
        // Listed once so the symlink and the info/exclude entries stay in sync.
        private val PLUGIN_MANAGED_PATHS = listOf("node_modules", ".env")

        // Sidecar filename for per-strand metadata. Lives inside the strand's
        // per-worktree gitdir, resolved via `git rev-parse --git-path`.
        private const val SIDECAR_FILE = "vibe-stranding.json"
    }

    /**
     * Per-strand metadata store. Swap the right-hand side to change where
     * metadata lives (sidecar file today; plugin state, remote service, etc.
     * in the future) without touching callers.
     */
    val metadata: StrandMetadataStore = WorktreeSidecarStore { strand -> sidecarPath(strand) }

    data class GitResult(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0
    }

    sealed interface CreateResult {
        data class Ok(val path: Path, val linkIssues: List<String> = emptyList()) : CreateResult
        data class Failed(val message: String) : CreateResult
    }

    sealed interface FinishResult {
        data class Finished(val commits: Int) : FinishResult
        data object NothingToFinish : FinishResult
        data class Conflict(val worktree: Path) : FinishResult
        data class Failed(val message: String) : FinishResult
    }

    // --- path helpers -------------------------------------------------------

    private fun mainCheckout(): Path = Path.of(project.basePath ?: error("Project has no base path"))

    private fun strandsRoot(): Path {
        val main = mainCheckout()
        return main.parent.resolve("${main.name}-strands")
    }

    fun strandPath(strand: String): Path = strandsRoot().resolve(strand)
    private fun branchName(strand: String): String = "strand/$strand"

    fun listStrands(): List<String> {
        val root = strandsRoot().toFile()
        if (!root.isDirectory) return emptyList()
        return root.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
    }

    /**
     * Resolve the repo's default branch. Tries the canonical answer
     * (`origin/HEAD`) first, then falls back to common local branch names so
     * the plugin works in repos without a remote configured. Returns "main"
     * as a last resort; subsequent git operations will fail loudly if that
     * branch doesn't exist either.
     */
    private fun defaultBranch(): String {
        val main = mainCheckout()
        val originHead = git(main, "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD")
        if (originHead.ok && originHead.stdout.startsWith("origin/")) {
            return originHead.stdout.removePrefix("origin/")
        }
        for (name in listOf("main", "master", "trunk", "develop")) {
            val v = git(main, "rev-parse", "--verify", "--quiet", "refs/heads/$name")
            if (v.ok) return name
        }
        return "main"
    }

    // --- git runner ---------------------------------------------------------

    /** Resolve the git executable the IDE is configured to use, falling back to PATH. */
    private fun gitPath(): String = runCatching { GitExecutableManager.getInstance().getPathToGit(project) }
        .getOrNull()?.takeIf { it.isNotBlank() } ?: "git"

    private fun git(workDir: Path, vararg args: String): GitResult = try {
        val cmd = GeneralCommandLine(gitPath())
            .withWorkDirectory(workDir.toFile())
            .withParameters(*args)
            .withCharset(Charsets.UTF_8)
        val out = CapturingProcessHandler(cmd).runProcess(GIT_TIMEOUT_MS)
        GitResult(out.exitCode, out.stdout.trim(), out.stderr.trim())
    } catch (t: Throwable) {
        LOG.warn("git ${args.joinToString(" ")} failed to launch", t)
        GitResult(-1, "", t.message ?: "git failed to launch")
    }

    private fun refresh(path: Path) {
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
    }

    // --- operations ---------------------------------------------------------

    fun createStrand(strand: String): CreateResult {
        val main = mainCheckout()
        val wt = strandPath(strand)
        val branch = branchName(strand)

        if (wt.exists()) return CreateResult.Failed("Strand already exists: $wt")

        Files.createDirectories(strandsRoot())

        val add = git(main, "worktree", "add", "-b", branch, wt.toString(), defaultBranch())
        if (!add.ok) return CreateResult.Failed("git worktree add failed:\n${add.stderr}")

        // Symlink so npm and env-dependent tooling work in the strand immediately,
        // then hide those symlinks from `git status`. Without the exclude, every
        // strand starts out "dirty" (the symlinks show as untracked), which would
        // trip the finish-time dirty check and also block `git worktree remove`.
        val issues = ensureManagedLinks(wt)
        addToInfoExclude(wt, PLUGIN_MANAGED_PATHS)

        refresh(wt)
        return CreateResult.Ok(wt, issues)
    }

    /**
     * Idempotent + self-healing: for each managed path, make sure the strand
     * has a symlink to the corresponding entry in the main checkout. Repairs
     * dangling links and links pointing at the wrong target. Refuses to
     * clobber a real file/dir sitting at the link path. Safe to call on every
     * resume / create.
     */
    fun ensureLinks(strand: String): List<String> {
        val wt = strandPath(strand)
        if (!wt.exists()) return listOf("Strand path missing: $wt")
        return ensureManagedLinks(wt)
    }

    private fun ensureManagedLinks(wt: Path): List<String> {
        val main = mainCheckout()
        val issues = mutableListOf<String>()
        PLUGIN_MANAGED_PATHS.forEach { name ->
            val target = main.resolve(name)
            val link = wt.resolve(name)
            if (!target.exists()) return@forEach // nothing in main to link to — silent
            try {
                val linkPresent = Files.exists(link, LinkOption.NOFOLLOW_LINKS)
                if (linkPresent) {
                    if (!Files.isSymbolicLink(link)) {
                        issues.add("$name: a real file/dir is already present in the strand; not replacing")
                        return@forEach
                    }
                    val current = runCatching { Files.readSymbolicLink(link) }.getOrNull()
                    if (current == target) return@forEach // already correct
                    Files.delete(link) // wrong/dangling symlink — replace
                }
                Files.createSymbolicLink(link, target)
            } catch (t: Throwable) {
                LOG.warn("Could not link $link -> $target", t)
                issues.add("$name: ${t.message ?: t::class.simpleName}")
            }
        }
        return issues
    }

    /**
     * Resolve the sidecar metadata file's path inside the strand's per-worktree
     * gitdir. Returns null if the strand worktree doesn't exist. Used by
     * [WorktreeSidecarStore] via the lambda passed at construction.
     *
     * Computed directly from the known worktree layout rather than via
     * `git rev-parse --git-path` because this path is read from
     * [ResumeStrandsGroup.getChildren] during action-update, and IntelliJ holds
     * a ReadAction across that callback — under which `OSProcessHandler`
     * refuses synchronous execution.
     */
    private fun sidecarPath(strand: String): Path? {
        val wt = strandPath(strand)
        if (!wt.exists()) return null
        return mainCheckout().resolve(".git").resolve("worktrees").resolve(strand).resolve(SIDECAR_FILE)
    }

    private fun addToInfoExclude(wt: Path, names: List<String>) {
        val r = git(wt, "rev-parse", "--git-path", "info/exclude")
        if (!r.ok || r.stdout.isBlank()) return
        val raw = Path.of(r.stdout)
        val excludeFile = if (raw.isAbsolute) raw else wt.resolve(raw)
        try {
            Files.createDirectories(excludeFile.parent)
            val existing = if (Files.exists(excludeFile)) Files.readString(excludeFile) else ""
            val present = existing.lines().map { it.trim() }.toSet()
            val toAdd = names.filterNot { it in present }
            if (toAdd.isEmpty()) return
            val sep = if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n"
            Files.writeString(excludeFile, existing + sep + toAdd.joinToString("\n") + "\n")
        } catch (t: Throwable) {
            LOG.warn("Could not append to info/exclude at $excludeFile", t)
        }
    }

    fun finishStrand(strand: String): FinishResult {
        val main = mainCheckout()
        val wt = strandPath(strand)
        val branch = branchName(strand)

        if (!wt.exists()) return FinishResult.Failed("No strand at $wt")

        // Auto-commit any outstanding work so finish is a single click. Pre-commit
        // hooks still run (we don't pass --no-verify); a hook failure surfaces as
        // a FinishResult.Failed and stops the finish before any rebase happens.
        val dirty = git(wt, "status", "--porcelain")
        if (dirty.ok && dirty.stdout.isNotEmpty()) {
            val stage = git(wt, "add", "-A")
            if (!stage.ok) return FinishResult.Failed("Could not stage outstanding changes:\n${stage.stderr}")
            val commit = git(wt, "commit", "-m", "Wrap up $strand")
            if (!commit.ok) return FinishResult.Failed("Could not auto-commit outstanding changes:\n${commit.stderr}")
        }

        val baseBranch = defaultBranch()

        // Rebase the strand branch onto the default branch, inside its own worktree.
        val rebase = git(wt, "rebase", baseBranch)
        if (!rebase.ok) {
            refresh(wt) // surface conflict markers in the editor
            return FinishResult.Conflict(wt)
        }

        val ahead = git(main, "rev-list", "--count", "$baseBranch..$branch").stdout.toIntOrNull() ?: 0
        if (ahead == 0) return FinishResult.NothingToFinish

        // Fast-forward the default branch from the main checkout.
        val ff = git(main, "merge", "--ff-only", branch)
        if (!ff.ok) return FinishResult.Failed("Fast-forward of $baseBranch failed:\n${ff.stderr}")

        refresh(main)
        return FinishResult.Finished(ahead)
    }

    fun deleteStrand(strand: String, force: Boolean): GitResult {
        val main = mainCheckout()
        val wt = strandPath(strand)
        val branch = branchName(strand)

        // Give the metadata store a chance to clean up before the worktree
        // (and thus the sidecar's gitdir, for the file backend) disappears.
        // Backends that ride on git's lifecycle can no-op; others need it.
        metadata.delete(strand)

        val removeArgs = buildList {
            add("worktree")
            add("remove")
            if (force) add("--force")
            add(wt.toString())
        }
        val remove = git(main, *removeArgs.toTypedArray())
        if (!remove.ok) return remove

        val result = git(main, "branch", if (force) "-D" else "-d", branch)
        refresh(strandsRoot())
        return result
    }
}
