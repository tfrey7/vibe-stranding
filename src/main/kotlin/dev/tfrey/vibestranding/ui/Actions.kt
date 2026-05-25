package dev.tfrey.vibestranding.ui

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.tfrey.vibestranding.core.GitStrands
import dev.tfrey.vibestranding.core.LlmClients
import dev.tfrey.vibestranding.core.LlmResult
import dev.tfrey.vibestranding.core.RESUME_COMMAND
import dev.tfrey.vibestranding.core.STRAND_COMMAND
import dev.tfrey.vibestranding.core.SUMMARIZE_PROMPT
import dev.tfrey.vibestranding.core.StrandDescriber
import dev.tfrey.vibestranding.core.StrandHandoff
import dev.tfrey.vibestranding.core.StrandMeta
import dev.tfrey.vibestranding.core.StrandNamer
import dev.tfrey.vibestranding.core.StrandWorktree
import dev.tfrey.vibestranding.core.fallbackEmoji
import dev.tfrey.vibestranding.core.pickStrandBackground
import dev.tfrey.vibestranding.core.tabLabel
import kotlin.io.path.exists

private val LOG = logger<NewStrandAction>()

private fun notify(project: Project, content: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("Vibe Stranding")
        .createNotification(content, type)
        .notify(project)
}

private fun onEdt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block)

private fun runInBackground(project: Project, title: String, work: () -> Unit) {
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, false) {
        override fun run(indicator: ProgressIndicator) = work()
    })
}

private fun service(project: Project): GitStrands = project.getService(GitStrands::class.java)

private fun focusedStrand(project: Project): String? =
    TerminalTabs.focusedStrand(project, service(project).listStrands())

private fun afterDelete(project: Project, strand: String, result: GitStrands.GitResult) {
    onEdt {
        if (result.ok) {
            TerminalTabs.closeTabForStrand(project, strand)
        } else {
            notify(project, "Delete failed:\n${result.stderr}", NotificationType.ERROR)
        }
    }
}

private fun runFinish(project: Project, svc: GitStrands, strand: String) {
    runInBackground(project, "Finishing '$strand'") {
        when (val r = svc.finishStrand(strand)) {
            is GitStrands.FinishResult.Finished -> {
                // Always delete on a successful finish — if the user didn't want
                // the strand gone they wouldn't have finished it.
                afterDelete(project, strand, svc.deleteStrand(strand, force = false))
            }
            GitStrands.FinishResult.NothingToFinish -> onEdt {
                notify(project, "'$strand' is already up to date; nothing to finish.", NotificationType.INFORMATION)
            }
            is GitStrands.FinishResult.Conflict -> onEdt {
                notify(
                    project,
                    "Rebase of '$strand' hit conflicts. Resolve them in ${r.worktree}, " +
                        "run 'git rebase --continue', then finish again.",
                    NotificationType.WARNING,
                )
                // EXTENSION POINT: to pop WebStorm's merge tool automatically here,
                // wire git4idea.merge.GitConflictResolver against the worktree's
                // GitRepository and call .merge(). Left out so the scaffold ships
                // without an unverified conflict-resolution flow.
            }
            is GitStrands.FinishResult.Failed -> onEdt {
                notify(project, r.message, NotificationType.ERROR)
            }
        }
    }
}

private fun promptAndRunDelete(project: Project, svc: GitStrands, strand: String) {
    val force = Messages.showYesNoDialog(
        project,
        "Force delete of '$strand', discarding any uncommitted or unfinished work?\n" +
            "Choose No to refuse unless the branch is clean and merged.",
        "Delete '$strand'",
        "Force",
        "Safe",
        Messages.getWarningIcon(),
    ) == Messages.YES

    runInBackground(project, "Deleting '$strand'") {
        afterDelete(project, strand, svc.deleteStrand(strand, force))
    }
}

class NewStrandAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val description = Messages.showInputDialog(
            project,
            "Describe the strand in plain English:",
            "New Strand",
            null,
            "",
            null,
        )?.trim()?.takeIf { it.isNotEmpty() } ?: return

        // Pick a slug + emoji synchronously from the description so the tab
        // can open right away — claude haiku naming takes ~8-16s and would
        // block the modal that long if we waited. The LLM-picked name and
        // emoji slot in asynchronously after the tab is up (see
        // [upgradeNameAsync]). The strand id is locked in at this point
        // forever because git can't safely rename a worktree's dir + branch
        // once a `claude` session is running in it.
        val strand = StrandNamer.naiveSlug(description)
        val emoji = fallbackEmoji(strand)

        runInBackground(project, "Creating strand '$strand'…") {
            val svc = service(project)
            val background = pickStrandBackground(
                svc.listStrands().mapNotNull { svc.metadata.read(it)?.background },
            )
            when (val result = svc.createStrand(strand)) {
                is GitStrands.CreateResult.Ok -> {
                    svc.metadata.write(strand, StrandMeta(emoji, description, background))
                    TerminalTabs.openTerminalTab(
                        project,
                        result.path.toString(),
                        tabLabel(emoji, strand),
                        STRAND_COMMAND,
                        strand,
                        background,
                    )
                    if (result.linkIssues.isNotEmpty()) {
                        onEdt {
                            notify(
                                project,
                                "Symlink issues in '$strand':\n${result.linkIssues.joinToString("\n")}",
                                NotificationType.WARNING,
                            )
                        }
                    }
                    upgradeEmojiAsync(project, svc, strand, description)
                    project.getService(StrandDescriber::class.java).schedule(strand)
                }
                is GitStrands.CreateResult.Failed -> onEdt {
                    notify(project, result.message, NotificationType.ERROR)
                }
            }
        }
    }
}

/**
 * Fires [StrandNamer.suggestEmoji] in the background and swaps the live
 * strand's tab emoji + sidecar emoji from the fallback color marker to the
 * LLM's pick. Best-effort: claude unavailable, parse failure, or timeout
 * all just leave the fallback in place. The slug never changes — only the
 * emoji.
 */
private fun upgradeEmojiAsync(project: Project, svc: GitStrands, strand: String, description: String) {
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Picking emoji for '$strand'…", false) {
        override fun run(indicator: ProgressIndicator) {
            val emoji = StrandNamer.suggestEmoji(description)
            if (emoji == null) {
                LOG.info("Async emoji for '$strand' returned nothing; keeping fallback marker.")
                return
            }
            // Preserve the background that was just assigned at create time —
            // overwriting the whole sidecar with a fresh StrandMeta would
            // otherwise drop it.
            val existing = svc.metadata.read(strand)
            svc.metadata.write(strand, StrandMeta(emoji, description, existing?.background))
            onEdt { TerminalTabs.relabelTab(project, strand, tabLabel(emoji, strand)) }
        }
    })
}

/**
 * Inline action group: each child strand becomes its own clickable entry in
 * the parent menu, so resuming is a single click. The group is dynamic
 * (`getChildren` runs at popup time) and renders nothing when no strands
 * exist — IntelliJ hides empty groups, so the labeled "Resume" separator
 * also disappears.
 *
 * When this project's basePath IS a strand worktree (i.e. the window was
 * opened via View This Strand's Code), the worktree-self also appears as a
 * resume entry — `GitStrands.listStrands()` wouldn't find it because its
 * path math assumes the project IS the main checkout.
 */
class ResumeStrandsGroup : ActionGroup() {
    init {
        templatePresentation.text = "Resume Strand"
        isPopup = false
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return emptyArray()
        val svc = service(project)
        // Hide strands that already have an open terminal tab — those are
        // "currently being worked on", and the menu would otherwise list a
        // resume entry that just re-focuses an already-visible tab.
        val open = TerminalTabs.openStrandsFor(project)
        val siblings = svc.listStrands().filter { it !in open }
        val selfCtx = StrandWorktree.detect(project)?.takeIf { it.strand !in open }
        if (siblings.isEmpty() && selfCtx == null) return emptyArray()
        return buildList<AnAction> {
            add(Separator.create("Resume"))
            siblings.forEach { strand ->
                val meta = svc.metadata.read(strand)
                val emoji = meta?.emoji ?: fallbackEmoji(strand)
                val shortDescription = meta?.generatedDescription ?: meta?.description
                add(ResumeOneStrandAction(strand, emoji, shortDescription))
            }
            if (selfCtx != null) {
                val meta = selfCtx.readMeta()
                val emoji = meta?.emoji ?: fallbackEmoji(selfCtx.strand)
                val shortDescription = meta?.generatedDescription ?: meta?.description
                add(ResumeSelfStrandAction(selfCtx.strand, emoji, shortDescription, meta?.background))
            }
        }.toTypedArray()
    }
}

private const val RESUME_LABEL_DESCRIPTION_MAX = 50

private class ResumeOneStrandAction(private val strand: String, emoji: String, description: String?) :
    AnAction(buildResumeLabel(emoji, strand, description)) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        resumeStrand(project, service(project), strand)
    }
}

/**
 * Resume entry for the strand whose worktree IS the current project. Path
 * math in [GitStrands] doesn't apply (the project's basePath already IS the
 * worktree), so the tab opens directly at `project.basePath`; self-heal
 * (symlinks, busy hooks) is skipped since the originating main-checkout
 * window wired those at create time.
 */
private class ResumeSelfStrandAction(
    private val strand: String,
    private val emoji: String,
    description: String?,
    private val background: String?,
) : AnAction(buildResumeLabel(emoji, strand, description)) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (TerminalTabs.focusTabForStrand(project, strand)) return
        val basePath = project.basePath ?: return
        TerminalTabs.openTerminalTab(
            project,
            basePath,
            tabLabel(emoji, strand),
            RESUME_COMMAND,
            strand,
            background,
        )
    }
}

private fun buildResumeLabel(emoji: String, strand: String, description: String?): String {
    val collapsed = description?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    if (collapsed.isEmpty()) return "$emoji $strand"
    val trimmed = if (collapsed.length > RESUME_LABEL_DESCRIPTION_MAX) {
        collapsed.take(RESUME_LABEL_DESCRIPTION_MAX - 1).trimEnd() + "…"
    } else {
        collapsed
    }
    return "$emoji $strand — $trimmed"
}

internal fun resumeStrand(project: Project, svc: GitStrands, strand: String) {
    if (TerminalTabs.focusTabForStrand(project, strand)) return
    // Self-heal runs in the background because [GitStrands.ensureClaudeHooks]
    // shells out to git (via addToInfoExclude), which OSProcessHandler refuses
    // synchronously on the EDT. Symlink-only self-heal used to be EDT-safe,
    // but the busy-hook install made the broader resume EDT-unsafe.
    runInBackground(project, "Resuming '$strand'") {
        val issues = svc.ensureLinks(strand).toMutableList()
        issues += svc.ensureClaudeHooks(strand)
        val meta = svc.metadata.read(strand)
        val emoji = meta?.emoji ?: fallbackEmoji(strand)
        TerminalTabs.openTerminalTab(
            project,
            svc.strandPath(strand).toString(),
            tabLabel(emoji, strand),
            RESUME_COMMAND,
            strand,
            meta?.background,
        )
        onEdt {
            notify(project, "Resumed strand '$strand'.", NotificationType.INFORMATION)
            if (issues.isNotEmpty()) {
                notify(
                    project,
                    "Setup issues in '$strand':\n${issues.joinToString("\n")}",
                    NotificationType.WARNING,
                )
            }
        }
    }
}

/**
 * Opens the focused tab's strand worktree as a project in a new IDE window,
 * moving the strand's `claude` session from the main window into the new one:
 * the main-window terminal tab is closed, and a `claude --continue` tab is
 * spawned in the new window via [StrandHandoff].
 *
 * Disabled while the strand's Claude turn is in flight (the busy animation
 * is running) — closing the tab mid-turn would lose the in-progress reply.
 */
class ViewThisStrandsCodeAction : AnAction() {
    override fun update(e: AnActionEvent) {
        val project = e.project
        val strand = project?.let { focusedStrand(it) }
        e.presentation.isEnabled = strand != null && !TerminalTabs.isBusy(project, strand)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val strand = focusedStrand(project) ?: return
        val svc = service(project)
        val path = svc.strandPath(strand)
        if (!path.exists()) {
            notify(project, "Strand path missing: $path", NotificationType.ERROR)
            return
        }
        val meta = svc.metadata.read(strand)
        val emoji = meta?.emoji ?: fallbackEmoji(strand)
        StrandHandoff.stash(path.toString(), StrandHandoff.Pending(strand, emoji, meta?.background))
        TerminalTabs.closeTabForStrand(project, strand)
        ProjectUtil.openOrImport(path, OpenProjectTask { forceOpenInNewFrame = true })
    }
}

/** Finishes whichever strand owns the currently-focused terminal tab. */
class FinishThisStrandAction : AnAction() {
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && focusedStrand(project) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val strand = focusedStrand(project) ?: return
        runFinish(project, service(project), strand)
    }
}

/**
 * Asks the configured [AgenticLlmClient] to summarize the strand using
 * filesystem tools (`git log` / `git diff`) and renders the result in the
 * Vibe Stranding Summary tool window. The strand's live claude session is
 * untouched.
 */
class SummarizeThisStrandAction : AnAction() {
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && focusedStrand(project) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val strand = focusedStrand(project) ?: return
        val worktree = service(project).strandPath(strand)

        onEdt { showStrandSummary(project, strand, "Summarizing '$strand'…") }
        runInBackground(project, "Summarizing '$strand'") {
            val result = LlmClients.agentic().completeInWorktree(
                prompt = SUMMARIZE_PROMPT,
                worktree = worktree,
                timeoutMs = SUMMARY_TIMEOUT_MS,
            )
            val output = when (result) {
                is LlmResult.Ok -> result.text
                is LlmResult.Timeout -> "Summary timed out after ${result.afterMs / 1000}s."
                is LlmResult.Error -> result.message
            }
            onEdt { showStrandSummary(project, strand, output) }
        }
    }
}

private const val SUMMARY_TIMEOUT_MS = 5 * 60 * 1000

/** Deletes whichever strand owns the currently-focused terminal tab. */
class DeleteThisStrandAction : AnAction() {
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && focusedStrand(project) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val strand = focusedStrand(project) ?: return
        promptAndRunDelete(project, service(project), strand)
    }
}
