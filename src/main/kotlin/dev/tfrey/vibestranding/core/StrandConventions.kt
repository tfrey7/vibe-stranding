package dev.tfrey.vibestranding.core

import kotlin.math.abs

/** Valid strand id: lowercase kebab, starts with `[a-z0-9]`. */
val STRAND_NAME = Regex("^[a-z0-9][a-z0-9-]*$")

/** Command run when a strand's terminal tab first opens. */
const val STRAND_COMMAND = "claude"

// `--continue` picks up the most recent claude session in the worktree without
// a picker; the `|| claude` fallback handles strands with no prior session
// (e.g. created then closed without interacting), where `--continue` errors.
const val RESUME_COMMAND = "claude --continue || claude"

// Sent to `claude -p` inside the strand's worktree; the subprocess has no
// conversation context, so we point it at git as the source of truth.
const val SUMMARIZE_PROMPT =
    "Summarize the work done on this strand so far — what changed, why, and what state it's in. " +
        "Use git log and git diff against main as your source of truth. Keep it under 200 words."

// Hash-based fallback so repeated reads on the same strand pick the same color.
private val MARKERS = listOf("🔵", "🟢", "🟡", "🟠", "🔴", "🟣", "🟤", "⚪")

fun fallbackEmoji(strand: String): String = MARKERS[abs(strand.hashCode()) % MARKERS.size]

// Fixed palette of terminal background tints, around L≈20% so they sit just
// above the default Darcula terminal background (~#2B2B2B) — dark enough that
// Claude's orange/yellow UI accents still read clearly, tinted enough to tell
// "the purple one" from "the green one" at a glance. We rotate (least-used
// first) at strand-create time so siblings pick different colors.
val STRAND_BACKGROUNDS = listOf(
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
fun pickStrandBackground(existing: List<String>): String {
    val counts = STRAND_BACKGROUNDS.associateWith { color -> existing.count { it == color } }
    val min = counts.values.min()
    return STRAND_BACKGROUNDS.first { counts[it] == min }
}

/** Tab label format: `"<emoji> <strand>"` — strand id is always the tab name. */
fun tabLabel(emoji: String, strand: String): String = "$emoji $strand"
