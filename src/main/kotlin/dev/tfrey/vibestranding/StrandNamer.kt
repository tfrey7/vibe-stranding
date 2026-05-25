package dev.tfrey.vibestranding

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
 *  - [suggestEmoji] is best-effort: asks the configured [LmClient] (a Haiku-
 *    class model via [LmTier.Fast]) to pick a semantically meaningful single
 *    emoji. Any failure (LM unavailable, timeout, unparseable output) returns
 *    null and the caller falls back to [Actions.fallbackEmoji].
 */
object StrandNamer {

    private const val TIMEOUT_MS = 30_000

    private const val PROMPT_TEMPLATE = """Pick one Unicode emoji that represents this software work.
Reply with the emoji only — no quotes, no code fences, no prose, nothing else.

Work: %s"""

    fun suggestEmoji(description: String): String? {
        val prompt = PROMPT_TEMPLATE.format(description)
        val result = LmClients.client().complete(prompt, tier = LmTier.Fast, timeoutMs = TIMEOUT_MS)
        val text = when (result) {
            is LmResult.Ok -> result.text
            is LmResult.Timeout, is LmResult.Error -> return null
        }
        // Take the first non-empty line and strip common decorations
        // (quotes, backticks, surrounding whitespace). Haiku usually obeys
        // the "emoji only" instruction, but defensive-trim anyway.
        val emoji = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.trim('"', '\'', '`', ' ')
        if (emoji.isNullOrBlank()) {
            LOG.warn("LM returned no usable emoji: $text")
            return null
        }
        return emoji
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
