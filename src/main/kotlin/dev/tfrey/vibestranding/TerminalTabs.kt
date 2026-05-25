package dev.tfrey.vibestranding

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.JBTerminalPanel
import com.intellij.terminal.JBTerminalSystemSettingsProviderBase
import com.intellij.terminal.TerminalColorPalette
import com.intellij.ui.content.Content
import com.jediterm.terminal.ui.TerminalPanel
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.awt.Color
import javax.swing.Timer

private val LOG = logger<TerminalTabsService>()

// Marker class so logger<> has somewhere to bind. TerminalTabs itself is an
// object and can't be a generic type argument.
private class TerminalTabsService

/** Parse `"#RRGGBB"` (or `"RRGGBB"`) → [Color], or null if it's malformed. */
private fun parseHex(hex: String): Color? = try {
    Color.decode(if (hex.startsWith("#")) hex else "#$hex")
} catch (t: NumberFormatException) {
    LOG.warn("Bad strand background hex: '$hex'", t)
    null
}

// Reflective handles into JediTerm + IntelliJ internals so we can swap a
// per-tab settings provider into a stock-built widget.
//
// Why reflection: `ShellTerminalWidget(Project, JBTerminalSystemSettingsProviderBase, Disposable)`
// is deprecated *and broken* in 2026.1 — its body ignores the passed base
// and constructs a fresh concrete `JBTerminalSystemSettingsProvider`
// (which is `final` and so can't be subclassed either). So the only way
// to color the terminal body is to let the stock factory build a normal
// widget, then post-hoc swap the settings field on the underlying panels.
//
// `TerminalPanel.mySettingsProvider` is the field the paint code reads
// for default background; `JBTerminalPanel.mySettingsProvider` shadows it
// with the IDE-typed view used by IDE plumbing (font, UI listeners).
// We swap both so the panel stays internally consistent.
private val TERMINAL_PANEL_SETTINGS = TerminalPanel::class.java
    .getDeclaredField("mySettingsProvider")
    .apply { isAccessible = true }

private val JB_TERMINAL_PANEL_SETTINGS = JBTerminalPanel::class.java
    .getDeclaredField("mySettingsProvider")
    .apply { isAccessible = true }

// `TerminalColorPalette.getAttributesByColorIndex` is `protected` and we
// want to delegate it to the base palette of *another instance*, which
// Kotlin's protected forbids — reflection bypasses that.
private val ATTRIBUTES_BY_COLOR_INDEX = TerminalColorPalette::class.java
    .getDeclaredMethod("getAttributesByColorIndex", Int::class.javaPrimitiveType)
    .apply { isAccessible = true }

/**
 * A [JBTerminalSystemSettingsProviderBase] that paints [bg] as the terminal's
 * default background. We override [getTerminalColorPalette] (rather than
 * [getDefaultBackground] directly) because the base class's default-background
 * supplier calls `getTerminalColorPalette().getDefaultBackground()` — so a
 * palette override propagates through both the high-level TerminalColor path
 * and the low-level paint path. The foreground is delegated to the base
 * palette so existing IDE theme colors (light/dark text) still apply.
 */
private class ColoredTerminalSettings(bg: Color) : JBTerminalSystemSettingsProviderBase() {
    // Capture the base palette before our override is installed so we can
    // borrow its foreground + ANSI colors. Safe at init-time: the base
    // constructor has already wired up myUiSettingsManager.
    private val basePalette: TerminalColorPalette = super.getTerminalColorPalette()
    private val jediBg = com.jediterm.core.Color(bg.red, bg.green, bg.blue)

    private val palette = object : TerminalColorPalette() {
        override val defaultForeground: com.jediterm.core.Color get() = basePalette.defaultForeground
        override val defaultBackground: com.jediterm.core.Color get() = jediBg

        override fun getAttributesByColorIndex(index: Int): TextAttributes? =
            ATTRIBUTES_BY_COLOR_INDEX.invoke(basePalette, index) as TextAttributes?
    }

    override fun getTerminalColorPalette(): TerminalColorPalette = palette
}

/**
 * The ONLY version-sensitive part of the plugin. Everything else (git, VFS,
 * actions) is stable across IDE versions.
 *
 * This uses the classic terminal engine API, which is the default through the
 * 2024.x line and still present afterward. If your WebStorm is set to the
 * reworked ("gen2") terminal engine and tabs do not appear, replace the body
 * of [openTerminalTab] with the reworked path.
 *
 * Keeping the swap localized to this object means strand identity (via
 * [STRAND_KEY] on the tab's [Content]) keeps working regardless.
 */
object TerminalTabs {

    /**
     * Tag attached to a Terminal [Content] when it owns a strand's tab.
     * Lookups (close, focus, relabel) go through this key so the displayed
     * tab name can change freely (e.g. when async naming returns a nicer
     * slug) without breaking strand identity.
     */
    private val STRAND_KEY = Key.create<String>("vibe-stranding.strand")

    /**
     * Safe to call from any thread — the Swing parts are bounced to the EDT
     * internally. When [backgroundHex] is supplied, the terminal *body* is
     * painted in that color via a reflective settings-provider swap (see the
     * file-top reflective handles for why this can't be done at construction
     * time). The tab header is also tinted via [Content.setTabColor].
     */
    fun openTerminalTab(
        project: Project,
        workingDir: String,
        tabName: String,
        command: String?,
        strand: String,
        backgroundHex: String?,
    ) {
        val bg = backgroundHex?.let { parseHex(it) }
        ApplicationManager.getApplication().invokeAndWait {
            val widget = TerminalToolWindowManager.getInstance(project)
                .createLocalShellWidget(workingDir, tabName)
            if (bg != null) recolorPanel(widget, bg)
            if (!command.isNullOrBlank()) widget.executeCommand(command)
            val content = findContentByName(project, tabName) ?: return@invokeAndWait
            content.putUserData(STRAND_KEY, strand)
            if (bg != null) content.tabColor = bg
        }
    }

    /**
     * Swap the panel's settings provider with one that returns [bg] as the
     * default background, then trigger a repaint. Best-effort: reflection
     * failures degrade silently to "tab tint only".
     */
    private fun recolorPanel(widget: ShellTerminalWidget, bg: Color) {
        try {
            val panel = widget.terminalPanel
            val settings = ColoredTerminalSettings(bg)
            TERMINAL_PANEL_SETTINGS.set(panel, settings)
            JB_TERMINAL_PANEL_SETTINGS.set(panel, settings)
            panel.repaint()
        } catch (t: Throwable) {
            LOG.warn("Could not apply per-tab terminal background via reflection", t)
        }
    }

    /** EDT. Re-label an existing strand's tab; no-op if the tab is gone. */
    fun relabelTab(project: Project, strand: String, newName: String) {
        val content = findContentForStrand(project, strand) ?: return
        val state = animations[content]
        if (state == null) {
            content.displayName = newName
        } else {
            // Mid-animation: stash the new base label and re-render the current
            // frame against it so the emoji/name swap is visible immediately
            // without the title flickering back to the unanimated form.
            state.baseLabel = newName
            content.displayName = renderBusy(newName, state.phase)
        }
    }

    /** EDT. Close the strand's tab; no-op if nothing matches. */
    fun closeTabForStrand(project: Project, strand: String) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return
        val content = findContentForStrand(project, strand) ?: return
        stopAnimation(content)
        toolWindow.contentManager.removeContent(content, true)
    }

    // --- Busy-state animation -------------------------------------------------

    /**
     * Per-tab animation state. [baseLabel] is the un-animated displayName we
     * restore on idle (and re-render against on each tick + on relabel).
     * [phase] indexes into [BUSY_FRAMES].
     */
    private data class AnimationState(var baseLabel: String, val timer: Timer, var phase: Int = 0)

    /**
     * Keyed by [Content] so the entry naturally disposes with the tab. We
     * additionally clear it from [closeTabForStrand] and on each tick if the
     * content has been detached from its manager (e.g. closed by the user via
     * the tab's × button).
     */
    private val animations = mutableMapOf<Content, AnimationState>()

    private const val BUSY_INTERVAL_MS = 400
    private val BUSY_FRAMES = listOf("• ·", "· •")

    private fun renderBusy(baseLabel: String, phase: Int): String =
        "$baseLabel  ${BUSY_FRAMES[phase % BUSY_FRAMES.size]}"

    /**
     * Flip the strand's tab into / out of a "Claude is working" animation.
     * Safe to call from any thread; bounced to the EDT internally. Repeated
     * calls with the same state are no-ops, so hook-fired transitions are
     * idempotent.
     */
    fun setBusy(project: Project, strand: String, busy: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            val content = findContentForStrand(project, strand) ?: return@invokeLater
            if (busy) startAnimation(content) else stopAnimation(content)
        }
    }

    private fun startAnimation(content: Content) {
        if (animations.containsKey(content)) return
        val baseLabel = content.displayName
        val timer = Timer(BUSY_INTERVAL_MS, null)
        val state = AnimationState(baseLabel, timer)
        animations[content] = state
        timer.addActionListener {
            // Tab gone (user closed it, IDE shut its terminal panel, etc.):
            // tear down rather than chase a detached Content.
            if (content.manager == null) {
                stopAnimation(content)
                return@addActionListener
            }
            state.phase = (state.phase + 1) % BUSY_FRAMES.size
            content.displayName = renderBusy(state.baseLabel, state.phase)
        }
        // Render frame 0 immediately so the user sees the animation start on
        // the same EDT pass; the timer only drives subsequent frames.
        content.displayName = renderBusy(baseLabel, 0)
        timer.start()
    }

    private fun stopAnimation(content: Content) {
        val state = animations.remove(content) ?: return
        state.timer.stop()
        // Restore only if the tab is still around; otherwise displayName is moot.
        if (content.manager != null) content.displayName = state.baseLabel
    }

    /**
     * Strand ids that currently own an open terminal tab. Used by the Resume
     * dropdown to hide strands already being worked on, so the menu only shows
     * strands the user could actually resume. Mirrors [findContentForStrand]'s
     * matching: primary lookup by [STRAND_KEY] user-data, with the legacy
     * slug-from-displayName fallback for tabs that predate the user-data tag.
     */
    fun openStrandsFor(project: Project): Set<String> {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return emptySet()
        return toolWindow.contentManager.contents.mapNotNullTo(mutableSetOf()) { content ->
            content.getUserData(STRAND_KEY) ?: parseSlug(content.displayName)
        }
    }

    /** EDT. Focus the strand's tab; returns true if a tab was activated. */
    fun focusTabForStrand(project: Project, strand: String): Boolean {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return false
        val content = findContentForStrand(project, strand) ?: return false
        toolWindow.contentManager.setSelectedContent(content, true)
        toolWindow.activate(null)
        return true
    }

    /**
     * EDT. Strand id of the currently-selected terminal tab, if any.
     * [knownStrands] is the on-disk list, used only for the legacy fallback
     * when a tab predates the user-data tagging (e.g. survived an IDE
     * restart).
     */
    fun focusedStrand(project: Project, knownStrands: List<String>): String? {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return null
        val content = toolWindow.contentManager.selectedContent ?: return null
        content.getUserData(STRAND_KEY)?.let { return it }
        // Legacy: parse the dir-name slug from the displayed label and match
        // against on-disk strands. Only kicks in for tabs missing the
        // user-data tag (pre-restart, pre-this-refactor).
        val parsed = parseSlug(content.displayName) ?: return null
        return knownStrands.firstOrNull { it == parsed }
    }

    private fun findContentByName(project: Project, name: String): Content? {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return null
        return toolWindow.contentManager.contents.firstOrNull { it.displayName == name }
    }

    private fun findContentForStrand(project: Project, strand: String): Content? {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return null
        // Primary: user-data tag from openTerminalTab.
        toolWindow.contentManager.contents.firstOrNull { it.getUserData(STRAND_KEY) == strand }
            ?.let { return it }
        // Legacy fallback: match against parsed display name when user-data
        // is missing (tab from a previous IDE session).
        return toolWindow.contentManager.contents.firstOrNull { parseSlug(it.displayName) == strand }
    }

    private val SLUG_RE = Regex("^[a-z0-9][a-z0-9-]*$")

    /** Pulls the kebab slug back out of a `"<emoji> <slug>"` tab label. */
    private fun parseSlug(displayName: String): String? {
        val parts = displayName.split(' ', limit = 2)
        if (parts.size != 2) return null
        val candidate = parts[1].trim()
        return if (SLUG_RE.matches(candidate)) candidate else null
    }
}
