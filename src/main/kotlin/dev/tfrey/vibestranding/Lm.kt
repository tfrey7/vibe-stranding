package dev.tfrey.vibestranding

import java.nio.file.Path

/** Capability tier — backends map this to a concrete model id. */
enum class LmTier { Fast, Default }

/** Result of an LM call. Implementations are responsible for logging detail. */
sealed interface LmResult {
    data class Ok(val text: String) : LmResult
    data class Timeout(val afterMs: Int) : LmResult
    data class Error(val message: String) : LmResult
}

/**
 * Pure prompt-in / text-out completion. Suitable for self-contained prompts
 * (e.g. picking an emoji for a strand description). Backends:
 *
 *  - [ClaudeCliClient] shells out to `claude -p` — the no-config default.
 *  - Future: direct API clients (Anthropic / OpenAI / …) gated on a
 *    user-provided key, faster than the CLI fork for short prompts.
 */
interface LmClient {
    fun complete(prompt: String, tier: LmTier = LmTier.Default, timeoutMs: Int): LmResult
}

/**
 * Agentic completion in a working directory — the model can run tools / read
 * files in [worktree] to ground its answer. Required by use cases (e.g. strand
 * summary) that depend on the model invoking `git log` / `git diff` itself
 * rather than us pre-stuffing context. Only backends with tool use can
 * implement this; currently just [ClaudeCliClient].
 */
interface AgenticLmClient : LmClient {
    fun completeInWorktree(prompt: String, worktree: Path, tier: LmTier = LmTier.Default, timeoutMs: Int): LmResult
}

/**
 * Resolves the active LM backend(s). Single seam for future settings-driven
 * selection (Anthropic API key, OpenAI API key, …). For now, both methods
 * return [ClaudeCliClient].
 */
object LmClients {
    fun client(): LmClient = ClaudeCliClient
    fun agentic(): AgenticLmClient = ClaudeCliClient
}
