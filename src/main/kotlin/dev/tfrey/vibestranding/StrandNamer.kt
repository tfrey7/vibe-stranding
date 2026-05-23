package dev.tfrey.vibestranding

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.logger

private val LOG = logger<StrandNamerService>()

// Marker class so logger<> has somewhere to bind. StrandNamer itself is an
// object and can't be a generic type argument.
private class StrandNamerService

/**
 * Asks the `claude` CLI to turn a free-text strand description into a
 * `{slug, emoji}` pair. Best-effort: any failure (claude not on PATH, network
 * down, unparseable output) returns null and the caller falls back to a
 * naive slug + hash-based emoji.
 */
object StrandNamer {
    data class Suggestion(val slug: String, val emoji: String)

    private const val TIMEOUT_MS = 30_000

    private val SLUG_RE = """"slug"\s*:\s*"([^"]+)"""".toRegex()
    private val EMOJI_RE = """"emoji"\s*:\s*"([^"]+)"""".toRegex()

    private const val PROMPT_TEMPLATE = """You are naming a strand of software work for a kebab-case git branch.
Return strict JSON on a single line, no prose, no code fences:
{"slug": "kebab-case-2-to-4-words", "emoji": "one Unicode emoji related to the work"}

Description: %s"""

    fun suggest(description: String): Suggestion? {
        val cmd = GeneralCommandLine("claude")
            // Haiku is more than enough for "pick a 2-4 word slug and an emoji"
            // and keeps the dialog latency tight. Using the alias rather than a
            // pinned model ID so it tracks whatever the current Haiku is.
            .withParameters("--model", "haiku", "-p", PROMPT_TEMPLATE.format(description))
            .withCharset(Charsets.UTF_8)
            // Use the shell's PATH/env so we behave like the terminal — fixes the
            // macOS "GUI app launched from Dock can't find claude" issue.
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

        return try {
            val out = CapturingProcessHandler(cmd).runProcess(TIMEOUT_MS)
            if (out.exitCode != 0) {
                LOG.warn("claude -p exit ${out.exitCode}: ${out.stderr}")
                return null
            }
            val text = out.stdout
            val slug = SLUG_RE.find(text)?.groupValues?.get(1)
            val emoji = EMOJI_RE.find(text)?.groupValues?.get(1)
            if (slug.isNullOrBlank() || emoji.isNullOrBlank()) {
                LOG.warn("claude -p returned no usable suggestion: $text")
                null
            } else {
                Suggestion(slug.trim(), emoji.trim())
            }
        } catch (t: Throwable) {
            LOG.warn("claude -p failed to launch", t)
            null
        }
    }

    /**
     * Best-effort slug from a free-text description, used when claude is
     * unavailable. Lowercases, collapses non-alphanumerics to hyphens, keeps
     * the first few words.
     */
    fun naiveSlug(description: String): String {
        val cleaned = description.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        if (cleaned.isEmpty()) return "strand"
        return cleaned.split("-").filter { it.isNotEmpty() }.take(4).joinToString("-")
    }
}
