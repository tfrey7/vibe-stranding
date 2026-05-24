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
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindowManager
import kotlin.io.path.exists
import kotlin.math.abs

internal val STRAND_NAME = Regex("^[a-z0-9][a-z0-9-]*$")

// What runs in the new tab. Change to taste (e.g. "claude --resume").
internal const val STRAND_COMMAND = "claude"

// What runs when reopening a tab for an existing strand. `--continue` picks
// up the most recent claude session in that worktree without a picker; the
// `|| claude` fallback handles strands with no prior session (e.g. created
// then closed without interacting), where `--continue` would otherwise error.
internal const val RESUME_COMMAND = "claude --continue || claude"

// Fallback emoji palette used only when claude isn't available to pick a
// semantic one. Hash-based so retries on the same description get the same
// color within a session.
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

// Tab display format: "<emoji> <kebab-slug>". The emoji is whatever the user
// (or claude) chose at create time; we never need to reconstruct it later
// because lookups (delete, focused-strand) parse the slug out of the
// existing tab name rather than recomputing the full label.
private fun tabLabel(emoji: String, strand: String): String = "$emoji $strand"

// Pull the strand slug back out of a terminal tab's display name. Used by
// the "this strand" actions to identify the focused strand, and by delete
// to find the matching tab to close.
internal fun strandFromTabName(name: String): String? {
    val parts = name.split(' ', limit = 2)
    if (parts.size != 2) return null
    val candidate = parts[1].trim()
    return if (STRAND_NAME.matches(candidate)) candidate else null
}

private fun focusedStrand(project: Project): String? {
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return null
    val content = toolWindow.contentManager.selectedContent ?: return null
    return strandFromTabName(content.displayName)
}

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

private fun afterDelete(project: Project, strand: String, result: GitStrands.GitResult) {
    onEdt {
        if (result.ok) {
            TerminalTabs.closeTerminalTab(project) { strandFromTabName(it) == strand }
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

        runInBackground(project, "Naming strand") {
            val suggestion = StrandNamer.suggest(description)
            val (slug, emoji) = if (suggestion != null) {
                suggestion.slug to suggestion.emoji
            } else {
                val s = StrandNamer.naiveSlug(description)
                s to fallbackEmoji(s)
            }
            createStrand(project, slug, emoji, description)
        }
    }

    private fun createStrand(project: Project, strand: String, emoji: String, description: String?) {
        val svc = service(project)
        runInBackground(project, "Creating strand '$strand'") {
            when (val r = svc.createStrand(strand)) {
                is GitStrands.CreateResult.Ok -> {
                    svc.metadata.write(strand, StrandMeta(emoji, description))
                    onEdt {
                        TerminalTabs.openTerminalTab(
                            project,
                            r.path.toString(),
                            tabLabel(emoji, strand),
                            STRAND_COMMAND,
                        )
                        notify(project, "Created strand '$strand'.", NotificationType.INFORMATION)
                        if (r.linkIssues.isNotEmpty()) {
                            notify(
                                project,
                                "Symlink issues in '$strand':\n${r.linkIssues.joinToString("\n")}",
                                NotificationType.WARNING,
                            )
                        }
                    }
                }
                is GitStrands.CreateResult.Failed -> onEdt {
                    notify(project, r.message, NotificationType.ERROR)
                }
            }
        }
    }
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
    if (TerminalTabs.focusTerminalTab(project) { strandFromTabName(it) == strand }) {
        return
    }
    // Self-heal symlinks before reopening: a previous spawn may have
    // failed silently, or something inside the strand may have nuked them.
    val linkIssues = svc.ensureLinks(strand)
    val emoji = svc.metadata.read(strand)?.emoji ?: fallbackEmoji(strand)
    TerminalTabs.openTerminalTab(
        project,
        svc.strandPath(strand).toString(),
        tabLabel(emoji, strand),
        RESUME_COMMAND,
    )
    notify(project, "Resumed strand '$strand'.", NotificationType.INFORMATION)
    if (linkIssues.isNotEmpty()) {
        notify(
            project,
            "Symlink issues in '$strand':\n${linkIssues.joinToString("\n")}",
            NotificationType.WARNING,
        )
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
