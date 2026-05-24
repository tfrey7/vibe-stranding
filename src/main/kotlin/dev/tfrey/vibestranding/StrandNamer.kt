package dev.tfrey.vibestranding

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.logger

private val LOG = logger<StrandNamerService>()

// Marker class so logger<> has somewhere to bind. StrandNamer itself is an
// object and can't be a generic type argument.
private class StrandNamerService

/**
 * Slug + emoji helpers for naming a strand.
 *
 *  - [naiveSlug] is fully local and authoritative — the strand id (= dir
 *    + branch name) is always the naive slug of the user's description, so
 *    the tab name matches the on-disk identity.
 *  - [suggestEmoji] is best-effort: shells out to `claude --model haiku`
 *    to pick a semantically meaningful single emoji. Any failure (claude
 *    not on PATH, network down, unparseable output) returns null and the
 *    caller falls back to [Actions.fallbackEmoji].
 */
object StrandNamer {

    private const val TIMEOUT_MS = 30_000

    private const val PROMPT_TEMPLATE = """Pick one Unicode emoji that represents this software work.
Reply with the emoji only — no quotes, no code fences, no prose, nothing else.

Work: %s"""

    fun suggestEmoji(description: String): String? {
        val cmd = GeneralCommandLine("claude")
            // Haiku is plenty for picking an emoji, and keeps the wait short.
            // The alias (not a pinned model id) tracks whatever the current
            // Haiku is.
            .withParameters("--model", "haiku", "-p", PROMPT_TEMPLATE.format(description))
            .withCharset(Charsets.UTF_8)
            // Use the shell's PATH/env so we behave like the terminal — fixes the
            // macOS "GUI app launched from Dock can't find claude" issue.
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

        return try {
            val started = System.currentTimeMillis()
            val out = CapturingProcessHandler(cmd).runProcess(TIMEOUT_MS)
            val elapsed = System.currentTimeMillis() - started
            LOG.info("claude haiku emoji subprocess: ${elapsed}ms (exit=${out.exitCode})")
            if (out.exitCode != 0) {
                LOG.warn("claude -p exit ${out.exitCode}: ${out.stderr}")
                return null
            }
            // Take the first non-empty line and strip common decorations
            // (quotes, backticks, surrounding whitespace). Haiku usually
            // obeys the "emoji only" instruction, but defensive-trim anyway.
            val raw = out.stdout.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?.trim('"', '\'', '`', ' ')
            if (raw.isNullOrBlank()) {
                LOG.warn("claude -p returned no usable emoji: ${out.stdout}")
                null
            } else {
                raw
            }
        } catch (t: Throwable) {
            LOG.warn("claude -p failed to launch", t)
            null
        }
    }

    /**
     * Slug derived locally from the user's description. Lowercases, collapses
     * non-alphanumerics to hyphens, keeps the first few words. This is the
     * strand's permanent identifier — directory name and branch name —
     * because we want the tab label and the on-disk identity to match.
     */
    fun naiveSlug(description: String): String {
        val cleaned = description.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        if (cleaned.isEmpty()) return "strand"
        return cleaned.split("-").filter { it.isNotEmpty() }.take(4).joinToString("-")
    }
}
