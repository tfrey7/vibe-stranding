package dev.tfrey.vibestranding

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.awt.BorderLayout
import javax.swing.JPanel

internal const val SUMMARY_TOOL_WINDOW_ID = "Vibe Stranding Summary"

// Single shared panel per project — each new summary overwrites the previous
// one. We keep a one-tab tool window because the source-of-truth for a strand's
// summary is "the latest one you asked for"; preserving history would just make
// users hunt for the freshest tab.
internal class StrandSummaryPanel : JPanel(BorderLayout()) {
    // JBHtmlPane is the IntelliJ-blessed HTML viewer — it picks up theme
    // colors, font sizing, and code/list/heading styling automatically, so
    // we don't need to hand-roll CSS to match dark vs. light mode.
    private val htmlPane = JBHtmlPane()

    init {
        add(JBScrollPane(htmlPane), BorderLayout.CENTER)
    }

    fun setMarkdown(markdown: String) {
        htmlPane.text = renderHtml(markdown)
        htmlPane.caretPosition = 0
    }
}

// GFM gives us tables, strikethrough, task lists, and fenced code blocks —
// claude emits all of those, so plain CommonMark would degrade the output.
private val MARKDOWN_FLAVOUR = GFMFlavourDescriptor()

private fun renderHtml(markdown: String): String {
    val tree = MarkdownParser(MARKDOWN_FLAVOUR).buildMarkdownTreeFromString(markdown)
    val body = HtmlGenerator(markdown, tree, MARKDOWN_FLAVOUR).generateHtml()
    return "<html><body>$body</body></html>"
}

class StrandSummaryToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = StrandSummaryPanel()
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * Render [markdown] into the summary tool window, label the tab with [strand],
 * and bring the window forward. Must be called on the EDT.
 */
internal fun showStrandSummary(project: Project, strand: String, markdown: String) {
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(SUMMARY_TOOL_WINDOW_ID) ?: return
    val content = toolWindow.contentManager.contents.firstOrNull() ?: return
    val panel = content.component as? StrandSummaryPanel ?: return
    panel.setMarkdown(markdown)
    content.displayName = strand
    toolWindow.activate(null)
}
