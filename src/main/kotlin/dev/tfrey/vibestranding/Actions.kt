package dev.tfrey.vibestranding

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
import com.intellij.openapi.ui.popup.JBPopupFactory
import kotlin.io.path.exists
import kotlin.math.abs

private val LOG = logger<NewStrandAction>()

internal val STRAND_NAME = Regex("^[a-z0-9][a-z0-9-]*$")

// What runs in the new tab. Change to taste (e.g. "claude --resume").
internal const val STRAND_COMMAND = "claude"

// What runs when reopening a tab for an existing strand. `--continue` picks
// up the most recent claude session in that worktree without a picker; the
// `|| claude` fallback handles strands with no prior session (e.g. created
// then closed without interacting), where `--continue` would otherwise error.
internal const val RESUME_COMMAND = "claude --continue || claude"

// Prompt sent to a one-shot `claude -p` invocation inside the strand's worktree.
// The subprocess has no prior conversation context, but it does have the
// worktree on disk plus shell tools, so we point it at `git log` / `git diff`
// as the source of truth for what the strand actually changed.
internal const val SUMMARIZE_PROMPT =
    "Summarize the work done on this strand so far — what changed, why, and what state it's in. " +
        "Use git log and git diff against main as your source of truth. Keep it under 200 words."

// Fallback emoji palette used while we're waiting on claude haiku to pick
// a semantic one (or forever, when claude isn't on PATH). Hash-based so
// repeated reads on the same strand pick the same color.
private val MARKERS = listOf(
    "🔵",
    "🟢",
    "🟡",
    "🟠",
    "🔴",
    "🟣",
    "🟤",
    "⚪",
)

internal fun fallbackEmoji(strand: String): String = MARKERS[abs(strand.hashCode()) % MARKERS.size]

// Fixed palette of terminal background tints. Targeted around L≈20% so they
// sit just above the default Darcula terminal background (~#2B2B2B) — dark
// enough that Claude's orange/yellow UI accents still read clearly, but
// tinted enough to tell "the purple one" from "the green one" at a glance.
// We rotate (least-used-first) at strand-create time so siblings pick
// different colors; duplicates are allowed once we wrap around.
internal val STRAND_BACKGROUNDS = listOf(
    "#1F2A3D", // deep navy
    "#1F2F25", // deep forest
    "#33291A", // deep amber
    "#33201F", // deep rust
    "#291F33", // deep plum
    "#2D241A", // deep umber
    "#1A2E2C", // deep teal
    "#2D1F2A", // deep mauve
)

/**
 * Pick the next strand background by counting existing usages and returning
 * the first palette entry with the lowest count. Ties go to palette order,
 * so siblings created in sequence cycle through colors deterministically.
 */
internal fun pickStrandBackground(existing: List<String>): String {
    val counts = STRAND_BACKGROUNDS.associateWith { color -> existing.count { it == color } }
    val min = counts.values.min()
    return STRAND_BACKGROUNDS.first { counts[it] == min }
}

/** Tab label format: `"<emoji> <strand>"` — strand id is always the tab name. */
internal fun tabLabel(emoji: String, strand: String): String = "$emoji $strand"

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

private fun chooseStrand(e: AnActionEvent, title: String, strands: List<String>, onChosen: (String) -> Unit) {
    if (strands.size == 1) {
        onChosen(strands.first())
        return
    }
    JBPopupFactory.getInstance()
        .createPopupChooserBuilder(strands)
        .setTitle(title)
        .setItemChosenCallback(onChosen)
        .createPopup()
        .showInBestPositionFor(e.dataContext)
}

private fun service(project: Project): GitStrands = project.getService(GitStrands::class.java)

private fun focusedStrand(project: Project): String? =
    TerminalTabs.focusedStrand(project, service(project).listStrands())

private fun afterDelete(project: Project, strand: String, result: GitStrands.GitResult) {
    onEdt {
        if (result.ok) {
            TerminalTabs.closeTabForStrand(project, strand)
            notify(project, "Deleted '$strand'.", NotificationType.INFORMATION)
        } else {
            notify(project, "Delete failed:\n${result.stderr}", NotificationType.ERROR)
        }
    }
}

private fun runFinish(project: Project, svc: GitStrands, strand: String) {
    runInBackground(project, "Finishing '$strand'") {
        when (val r = svc.finishStrand(strand)) {
            is GitStrands.FinishResult.Finished -> {
                onEdt {
                    notify(project, "Finished '$strand' (${r.commits} commit(s)).", NotificationType.INFORMATION)
                }
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
                    onEdt {
                        notify(project, "Created strand '$strand'.", NotificationType.INFORMATION)
                        if (result.linkIssues.isNotEmpty()) {
                            notify(
                                project,
                                "Symlink issues in '$strand':\n${result.linkIssues.joinToString("\n")}",
                                NotificationType.WARNING,
                            )
                        }
                    }
                    upgradeEmojiAsync(project, svc, strand, description)
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
        val strands = svc.listStrands()
        if (strands.isEmpty()) return emptyArray()
        return buildList<AnAction> {
            add(Separator.create("Resume"))
            strands.forEach { strand ->
                val emoji = svc.metadata.read(strand)?.emoji ?: fallbackEmoji(strand)
                add(ResumeOneStrandAction(strand, emoji))
            }
        }.toTypedArray()
    }
}

private class ResumeOneStrandAction(private val strand: String, emoji: String) : AnAction("$emoji $strand") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        resumeStrand(project, service(project), strand)
    }
}

private fun resumeStrand(project: Project, svc: GitStrands, strand: String) {
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

class OpenStrandInIdeAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val svc = service(project)
        val strands = svc.listStrands()
        if (strands.isEmpty()) {
            notify(project, "No strands to open.", NotificationType.WARNING)
            return
        }
        chooseStrand(e, "Open which strand in IDE?", strands) { strand ->
            val path = svc.strandPath(strand)
            if (!path.exists()) {
                notify(project, "Strand path missing: $path", NotificationType.ERROR)
                return@chooseStrand
            }
            ProjectUtil.openOrImport(path, OpenProjectTask { forceOpenInNewFrame = true })
        }
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
 * Runs `claude -p` in the focused strand's worktree, asks for a summary of the
 * work so far, and renders the result in the Vibe Stranding Summary tool window.
 * The strand's live claude session is untouched.
 */
class SummarizeThisStrandAction : AnAction() {
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && focusedStrand(project) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val strand = focusedStrand(project) ?: return
        val worktree = service(project).strandPath(strand).toString()

        onEdt { showStrandSummary(project, strand, "Summarizing '$strand'…") }
        runInBackground(project, "Summarizing '$strand'") {
            val output = runClaudeSummary(worktree, SUMMARIZE_PROMPT)
            onEdt { showStrandSummary(project, strand, output) }
        }
    }
}

// Invoke `claude -p` with the shell's PATH/env (ParentEnvironmentType.CONSOLE)
// so npm-global / mise / asdf installs resolve the same way they do in a
// terminal — fixes the "GUI app launched from Dock can't find claude" case.
// A plain `bash -lc` would only source bash login config and miss zsh users
// who set PATH in .zshrc / .zprofile, which is the common macOS setup.
private fun runClaudeSummary(worktree: String, prompt: String): String {
    val cmd = com.intellij.execution.configurations.GeneralCommandLine("claude")
        .withParameters("-p", prompt)
        .withWorkDirectory(worktree)
        .withCharset(Charsets.UTF_8)
        .withParentEnvironmentType(
            com.intellij.execution.configurations.GeneralCommandLine.ParentEnvironmentType.CONSOLE,
        )
    return try {
        val out = com.intellij.execution.process.CapturingProcessHandler(cmd).runProcess(SUMMARY_TIMEOUT_MS)
        val combined = (out.stdout + out.stderr).trim()
        when {
            out.isTimeout -> "claude timed out after ${SUMMARY_TIMEOUT_MS / 1000}s."
            out.exitCode != 0 -> "claude exited with code ${out.exitCode}:\n\n$combined"
            else -> combined.ifBlank { "(claude returned no output)" }
        }
    } catch (t: Throwable) {
        "Failed to run claude: ${t.message}"
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
