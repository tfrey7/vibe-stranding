package dev.tfrey.vibestranding

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup

/**
 * Action group whose label gains a `(<worktree>)` suffix when the plugin is
 * running in an IntelliJ sandbox. Lets you tell which strand spawned the dev
 * IDE at a glance, while production installs keep the plain "Vibe Stranding".
 */
class VibeStrandingMenuGroup : DefaultActionGroup() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val suffix = sandboxWorktreeName()?.let { " ($it)" } ?: ""
        e.presentation.text = "Vibe Stranding$suffix"
    }

    private fun sandboxWorktreeName(): String? {
        if (System.getProperty("idea.plugin.in.sandbox.mode") != "true") return null
        val pluginsPath = System.getProperty("idea.plugins.path") ?: return null
        val marker = "/.intellijPlatform/"
        val idx = pluginsPath.indexOf(marker)
        if (idx < 0) return null
        return pluginsPath.substring(0, idx).substringAfterLast('/').ifEmpty { null }
    }
}
