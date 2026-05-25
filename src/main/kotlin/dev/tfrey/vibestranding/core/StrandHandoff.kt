package dev.tfrey.vibestranding.core

/**
 * Process-wide hand-off table for the "View This Strand's Code" flow.
 *
 * When the main IDE window opens a strand's worktree as its own project, it
 * stashes the strand's identity here keyed by the worktree's absolute path.
 * The new project's startup activity consumes the entry and opens a Claude
 * resume tab in the new window — moving the strand's terminal from the main
 * window to the worktree window without losing the conversation.
 *
 * In-memory only. Survives only as long as the IDE process is up, which is
 * fine: the hand-off is consumed in the same process at the very next
 * project-open.
 */
object StrandHandoff {

    data class Pending(val strand: String, val emoji: String, val background: String?)

    private val pending = mutableMapOf<String, Pending>()

    @Synchronized
    fun stash(basePath: String, info: Pending) {
        pending[basePath] = info
    }

    @Synchronized
    fun consume(basePath: String): Pending? = pending.remove(basePath)
}
