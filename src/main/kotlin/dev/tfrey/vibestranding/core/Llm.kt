package dev.tfrey.vibestranding.core

import java.nio.file.Path

/** Capability tier — backends map this to a concrete model id. */
enum class LlmTier { Fast, Default }

/** Result of an LLM call. Implementations are responsible for logging detail. */
sealed interface LlmResult {
    data class Ok(val text: String) : LlmResult
    data class Timeout(val afterMs: Int) : LlmResult
    data class Error(val message: String) : LlmResult
}

/**
 * Pure prompt-in / text-out completion. Suitable for self-contained prompts
 * (e.g. picking an emoji for a strand description). Backends:
 *
 *  - [ClaudeCliClient] shells out to `claude -p` — the no-config default.
 *  - Future: direct API clients (Anthropic / OpenAI / …) gated on a
 *    user-provided key, faster than the CLI fork for short prompts.
 */
interface LlmClient {
    fun complete(prompt: String, tier: LlmTier = LlmTier.Default, timeoutMs: Int): LlmResult
}

/**
 * Agentic completion in a working directory — the model can run tools / read
 * files in [worktree] to ground its answer. Required by use cases (e.g. strand
 * summary) that depend on the model invoking `git log` / `git diff` itself
 * rather than us pre-stuffing context. Only backends with tool use can
 * implement this; currently just [ClaudeCliClient].
 */
interface AgenticLlmClient : LlmClient {
    fun completeInWorktree(prompt: String, worktree: Path, tier: LlmTier = LlmTier.Default, timeoutMs: Int): LlmResult
}

/**
 * Resolves the active LLM backend(s). Single seam for future settings-driven
 * selection (Anthropic API key, OpenAI API key, …). For now, both methods
 * return [ClaudeCliClient].
 */
object LlmClients {
    fun client(): LlmClient = ClaudeCliClient
    fun agentic(): AgenticLlmClient = ClaudeCliClient
}
