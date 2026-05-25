package dev.tfrey.vibestranding.core

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import git4idea.config.GitExecutableManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.jetbrains.ide.BuiltInServerManager
import java.net.URLEncoder
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

        // Per-sub-step timing surfaces in idea.log so we can see whether the
        // create-strand wall-clock cost is the default-branch lookup, the
        // `worktree add` itself, or the symlink/exclude setup.
        val branchStart = System.currentTimeMillis()
        val baseBranch = defaultBranch()
        val branchMs = System.currentTimeMillis() - branchStart

        val addStart = System.currentTimeMillis()
        val add = git(main, "worktree", "add", "-b", branch, wt.toString(), baseBranch)
        val addMs = System.currentTimeMillis() - addStart
        if (!add.ok) {
            LOG.info("createStrand '$strand': defaultBranch=${branchMs}ms, worktreeAdd=${addMs}ms (failed)")
            return CreateResult.Failed("git worktree add failed:\n${add.stderr}")
        }

        // Symlink so npm and env-dependent tooling work in the strand immediately,
        // then hide those symlinks from `git status`. Without the exclude, every
        // strand starts out "dirty" (the symlinks show as untracked), which would
        // trip the finish-time dirty check and also block `git worktree remove`.
        val linksStart = System.currentTimeMillis()
        val issues = ensureManagedLinks(wt).toMutableList()
        addToInfoExclude(wt, PLUGIN_MANAGED_PATHS)
        val linksMs = System.currentTimeMillis() - linksStart

        // Must run before the terminal launches `claude` — Claude only reads
        // hooks at startup, so a post-launch install would miss the first turn.
        val hooksStart = System.currentTimeMillis()
        issues += ensureClaudeHooks(strand)
        val hooksMs = System.currentTimeMillis() - hooksStart

        val refreshStart = System.currentTimeMillis()
        refresh(wt)
        val refreshMs = System.currentTimeMillis() - refreshStart

        LOG.info(
            "createStrand '$strand': defaultBranch=${branchMs}ms, worktreeAdd=${addMs}ms, " +
                "linksAndExclude=${linksMs}ms, claudeHooks=${hooksMs}ms, refresh=${refreshMs}ms",
        )
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
     * `git rev-parse --git-path` because this path is read from the
     * Resume Strand action group during action-update, and IntelliJ holds
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

    /**
     * Idempotent: write `<wt>/.claude/settings.local.json` so the strand's
     * `claude` session signals busy/idle to the plugin via UserPromptSubmit +
     * Stop hooks. The hook URL bakes in the IDE's current built-in HTTP port
     * and the strand + project identity, so the plugin can light up the right
     * tab even when multiple IDE windows are open.
     *
     * Safe to re-run on every resume — the file is plugin-owned and the
     * `.local.json` suffix is the Claude convention for non-checked-in
     * overrides. We also append it to the worktree's `info/exclude` so it
     * never sneaks into a finish-time `git add -A`.
     *
     * Best-effort: any failure leaves the strand without busy animation but
     * doesn't otherwise affect its operation.
     */
    fun ensureClaudeHooks(strand: String): List<String> {
        val wt = strandPath(strand)
        if (!wt.exists()) return listOf("Strand path missing: $wt")
        val issues = mutableListOf<String>()
        try {
            val basePath = project.basePath
                ?: return listOf("project basePath is null; cannot wire busy hook")
            val port = BuiltInServerManager.getInstance().port
            val encStrand = URLEncoder.encode(strand, Charsets.UTF_8)
            val encProject = URLEncoder.encode(basePath, Charsets.UTF_8)
            val baseUrl = "http://127.0.0.1:$port/api/vibe-stranding"
            // `>/dev/null 2>&1 || true` keeps the hook from ever blocking
            // Claude — a closed IDE or unreachable port silently leaves the
            // tab static instead of erroring the user's turn.
            val busyCmd = "curl -sf -X POST '$baseUrl/busy?name=$encStrand&project=$encProject' >/dev/null 2>&1 || true"
            val idleCmd = "curl -sf -X POST '$baseUrl/idle?name=$encStrand&project=$encProject' >/dev/null 2>&1 || true"

            val hooksObject = buildJsonObject {
                putJsonArray("UserPromptSubmit") {
                    addJsonObject {
                        putJsonArray("hooks") {
                            addJsonObject {
                                put("type", "command")
                                put("command", busyCmd)
                            }
                        }
                    }
                }
                putJsonArray("Stop") {
                    addJsonObject {
                        putJsonArray("hooks") {
                            addJsonObject {
                                put("type", "command")
                                put("command", idleCmd)
                            }
                        }
                    }
                }
            }

            // Start from main's settings.local.json so the strand inherits
            // MCP approvals (enabledMcpjsonServers), permissions, etc. without
            // re-prompting. The plugin owns only the `hooks` key; everything
            // else passes through. Re-runs on resume, so later additions to
            // main propagate to existing strands automatically.
            val mainSettings = mainCheckout().resolve(".claude").resolve("settings.local.json")
            val baseObject: JsonObject = if (Files.exists(mainSettings)) {
                runCatching {
                    Json.parseToJsonElement(Files.readString(mainSettings)).jsonObject
                }.getOrElse { JsonObject(emptyMap()) }
            } else {
                JsonObject(emptyMap())
            }
            val merged = JsonObject(baseObject.toMutableMap().apply { put("hooks", hooksObject) })
            val text = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), merged) + "\n"

            val claudeDir = wt.resolve(".claude")
            Files.createDirectories(claudeDir)
            Files.writeString(claudeDir.resolve("settings.local.json"), text)
            addToInfoExclude(wt, listOf(".claude/settings.local.json"))
        } catch (t: Throwable) {
            LOG.warn("Could not install Claude hooks for strand '$strand'", t)
            issues.add(".claude/settings.local.json: ${t.message ?: t::class.simpleName}")
        }
        return issues
    }

    /**
     * True when the strand has any committed work ahead of the default branch
     * or any uncommitted change in the working tree. Used by the description
     * generator to skip strands that haven't accumulated anything worth
     * summarizing yet.
     */
    fun hasChangesSinceMain(strand: String): Boolean {
        val wt = strandPath(strand)
        if (!wt.exists()) return false
        val dirty = git(wt, "status", "--porcelain")
        if (dirty.ok && dirty.stdout.isNotEmpty()) return true
        val ahead = git(wt, "rev-list", "--count", "${defaultBranch()}..HEAD").stdout.toIntOrNull() ?: 0
        return ahead > 0
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

        // Cancel any pending description generation before the worktree
        // disappears — the job's own liveness check would otherwise let a
        // racing timer fire against a half-deleted strand.
        project.getService(StrandDescriber::class.java).cancel(strand)

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
