package dev.tfrey.vibestranding

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private val LOG = logger<StrandDescriber>()

// Subprocess wall-clock cap. Plenty for a one-sentence reply against a
// small-to-medium diff; longer than this and something's stuck.
private const val DESCRIBE_TIMEOUT_MS = 3 * 60 * 1000

/**
 * Background generator of one-sentence strand descriptions.
 *
 * The strand id alone ("oauth-cleanup") and the user's create-time prompt
 * are both thin signals — the real "what is this strand about" only becomes
 * legible once some code has been written. This service schedules a single
 * [LmClient] call per strand a few minutes after creation, feeds it the
 * worktree + git history, and writes the resulting blurb back to the
 * strand's [StrandMeta.generatedDescription].
 *
 * Cancellation: pending jobs are tracked per strand and cancelled when the
 * strand is deleted (see [GitStrands.deleteStrand]) and when the project
 * closes (this service is [Disposable]).
 *
 * Empty-diff retry: if the strand still has no committed or uncommitted
 * change when the timer fires, we reschedule for another delay rather than
 * burning a Claude call to summarize an empty worktree.
 */
@Service(Service.Level.PROJECT)
class StrandDescriber(private val project: Project) : Disposable {

    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()

    /** Safe to call from any thread. */
    fun schedule(strand: String) {
        val delayMinutes = VibeStrandingSettings.get(project).descriptionDelayMinutes.coerceAtLeast(1)
        pending.compute(strand) { _, existing ->
            existing?.cancel(false)
            AppExecutorUtil.getAppScheduledExecutorService().schedule(
                { generate(strand) },
                delayMinutes.toLong(),
                TimeUnit.MINUTES,
            )
        }
    }

    /** Safe to call from any thread. */
    fun cancel(strand: String) {
        pending.remove(strand)?.cancel(false)
    }

    override fun dispose() {
        pending.values.forEach { it.cancel(false) }
        pending.clear()
    }

    /** Runs off the EDT — invokes git + the LM client. */
    private fun generate(strand: String) {
        pending.remove(strand)
        if (project.isDisposed) return

        val svc = project.getService(GitStrands::class.java)
        if (strand !in svc.listStrands()) return

        val meta = svc.metadata.read(strand) ?: return
        if (meta.generatedDescription != null) return

        if (!svc.hasChangesSinceMain(strand)) {
            LOG.info("Strand '$strand' has no changes yet; rescheduling description generation.")
            schedule(strand)
            return
        }

        val description = describe(svc.strandPath(strand), strand, meta.description)
        if (description == null) return

        // Re-read in case another field changed (emoji upgrade, etc.) between
        // our read above and now.
        val current = svc.metadata.read(strand) ?: return
        svc.metadata.write(strand, current.copy(generatedDescription = description))
    }

    private fun describe(worktree: Path, strand: String, userDescription: String?): String? {
        val seed = userDescription?.takeIf { it.isNotBlank() }
            ?.let { " — the developer originally described it as: \"$it\"" }
            ?: ""
        val prompt = """This git worktree is an in-flight unit of work called "$strand"$seed.

Use `git log` and `git diff` against the default branch as your source of truth for what's actually been done. Then write one or two short sentences describing what this work is about — present tense, no more than ~30 words total.

Output the sentence(s) only — no quotes, no code fences, no labels, no prose around it.
"""
        return when (
            val r = LmClients.agentic().completeInWorktree(
                prompt,
                worktree,
                LmTier.Default,
                DESCRIBE_TIMEOUT_MS,
            )
        ) {
            is LmResult.Ok -> r.text.trim().trim('"', '\'', '`').takeIf { it.isNotBlank() }
            is LmResult.Timeout -> {
                LOG.warn("Description LM call for '$strand' timed out after ${r.afterMs}ms.")
                null
            }
            is LmResult.Error -> {
                LOG.warn("Description LM call for '$strand' failed: ${r.message}")
                null
            }
        }
    }
}

/**
 * On project open, queue a description job for every strand that doesn't
 * have one yet. Covers strands created in a previous IDE session as well as
 * any whose first job was cancelled by a crash before it could complete.
 * Timers themselves are not persisted — this is the only re-hydration path.
 */
class StrandDescriberStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val svc = project.getService(GitStrands::class.java)
        val describer = project.getService(StrandDescriber::class.java)
        svc.listStrands().forEach { strand ->
            val meta = svc.metadata.read(strand) ?: return@forEach
            if (meta.generatedDescription == null) describer.schedule(strand)
        }
    }
}
