package dev.tfrey.vibestranding

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * The ONLY version-sensitive part of the plugin. Everything else (git, VFS,
 * actions) is stable across IDE versions.
 *
 * This uses the classic terminal engine API, which is the default through the
 * 2024.x line and still present afterward. If your WebStorm is set to the
 * reworked ("gen2") terminal engine and tabs do not appear, replace the body
 * with the reworked path:
 *
 *     val mgr = org.jetbrains.plugins.terminal.TerminalToolWindowTabsManager
 *                 .getInstance(project)
 *     val tab = mgr.createTabBuilder()
 *                 .workingDirectory(workingDir)
 *                 .tabName(tabName)
 *                 .createTab()
 *     // then send `command` to tab's widget
 *
 * Keeping it in one function means that swap is the only edit required.
 */
object TerminalTabs {

    /** Must be called on the EDT. */
    fun openTerminalTab(project: Project, workingDir: String, tabName: String, command: String?) {
        val widget = TerminalToolWindowManager.getInstance(project)
            .createLocalShellWidget(workingDir, tabName)
        if (!command.isNullOrBlank()) {
            widget.executeCommand(command)
        }
    }

    /**
     * Close the first terminal tab whose display name satisfies [matches].
     * No-op if nothing matches. Operates at the tool-window content layer, so
     * it works for both the classic and the reworked terminal engines.
     *
     * Predicate-based rather than name-based because strand tabs no longer
     * have a deterministic name — the emoji is picked at creation time and
     * not stored anywhere, so callers identify their tab by parsing the slug
     * back out of the display name.
     *
     * Must be called on the EDT.
     */
    fun closeTerminalTab(project: Project, matches: (String) -> Boolean) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return
        val content = toolWindow.contentManager.contents
            .firstOrNull { matches(it.displayName) } ?: return
        toolWindow.contentManager.removeContent(content, true)
    }

    /**
     * Focus the first terminal tab whose display name satisfies [matches], activating
     * the Terminal tool window. Returns true if a tab was focused, false if none matched.
     *
     * Must be called on the EDT.
     */
    fun focusTerminalTab(project: Project, matches: (String) -> Boolean): Boolean {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return false
        val content = toolWindow.contentManager.contents
            .firstOrNull { matches(it.displayName) } ?: return false
        toolWindow.contentManager.setSelectedContent(content, true)
        toolWindow.activate(null)
        return true
    }
}
