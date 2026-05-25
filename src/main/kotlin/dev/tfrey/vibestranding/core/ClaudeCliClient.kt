package dev.tfrey.vibestranding.core

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.file.Path

private val LOG = logger<ClaudeCliClientMarker>()

// Marker class so logger<> has somewhere to bind. ClaudeCliClient itself is
// an object and can't be a generic type argument.
private class ClaudeCliClientMarker

/**
 * LLM backend that shells out to the `claude` CLI in one-shot mode (`claude -p`).
 *
 * Uses [GeneralCommandLine.ParentEnvironmentType.CONSOLE] so npm-global / mise
 * / asdf installs resolve the same way they would in the user's terminal —
 * fixes the macOS "GUI app launched from Dock can't find claude" case. A plain
 * `bash -lc` would only source bash login config and miss zsh users who set
 * PATH in .zshrc / .zprofile, which is the common macOS setup.
 */
object ClaudeCliClient : AgenticLlmClient {

    override fun complete(prompt: String, tier: LlmTier, timeoutMs: Int): LlmResult =
        runClaude(prompt, tier, worktree = null, timeoutMs = timeoutMs)

    override fun completeInWorktree(prompt: String, worktree: Path, tier: LlmTier, timeoutMs: Int): LlmResult =
        runClaude(prompt, tier, worktree = worktree, timeoutMs = timeoutMs)

    private fun runClaude(prompt: String, tier: LlmTier, worktree: Path?, timeoutMs: Int): LlmResult {
        val params = buildList {
            // Claude Code accepts `haiku` / `sonnet` / `opus` as model aliases
            // that track the current generation, so we stay un-pinned.
            if (tier == LlmTier.Fast) {
                add("--model")
                add("haiku")
            }
            add("-p")
            add(prompt)
        }
        val cmd = GeneralCommandLine("claude")
            .withParameters(params)
            .withCharset(Charsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            // Redirect stdin from the platform's null device. Without this,
            // `claude -p` waits 3s for stdin (looking for a piped prompt
            // alongside the -p flag), then prints a warning to stderr that
            // would otherwise contaminate the captured output.
            .withInput(File(if (SystemInfo.isWindows) "NUL" else "/dev/null"))
        if (worktree != null) cmd.withWorkDirectory(worktree.toFile())

        return try {
            val started = System.currentTimeMillis()
            val out = CapturingProcessHandler(cmd).runProcess(timeoutMs)
            val elapsed = System.currentTimeMillis() - started
            LOG.info("claude -p subprocess: ${elapsed}ms (exit=${out.exitCode}, tier=$tier)")
            when {
                out.isTimeout -> LlmResult.Timeout(timeoutMs)
                out.exitCode != 0 -> {
                    LOG.warn("claude -p exit ${out.exitCode}: ${out.stderr}")
                    val combined = (out.stdout + out.stderr).trim()
                    LlmResult.Error("claude exited with code ${out.exitCode}:\n\n$combined")
                }
                else -> {
                    // Use stdout only on success — stderr carries warnings /
                    // progress that would corrupt the model's text answer.
                    val text = out.stdout.trim()
                    if (text.isEmpty()) LlmResult.Error("claude returned no output") else LlmResult.Ok(text)
                }
            }
        } catch (t: Throwable) {
            LOG.warn("claude -p failed to launch", t)
            LlmResult.Error("Failed to run claude: ${t.message}")
        }
    }
}
