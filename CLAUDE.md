# Vibe Stranding — repo workflow

Developer-workflow rules for *this repo* (the plugin source).

## Publishing a new version

When the user says **"publish a patch / minor / major version"** (or "publish
a bug fix" → patch), run this flow end-to-end:

1. **Commit outstanding work.** Any modified or untracked files in the working
   tree get committed first under their own descriptive message — not folded
   into the version-bump commit. If the diff is non-obvious, ask before
   choosing a message.
2. **Bump `version` in `build.gradle.kts`** (currently the sole source of
   truth — `plugin.xml` inherits it). Semver: patch = `0.1.0 → 0.1.1`,
   minor = `0.1.0 → 0.2.0`, major = `0.1.0 → 1.0.0`.
3. **Add a `CHANGELOG.md` section** at the top (under `# Changelog`) for the
   new version: `## X.Y.Z — YYYY-MM-DD` followed by terse prose describing
   what changed. This is the single source of truth — Gradle reads it into
   the bundled `plugin.xml`'s `<change-notes>`, and step 7 hands it to
   `gh release create`, which the GH Pages workflow then forwards into
   `updatePlugins.xml`. IntelliJ renders a subset of HTML, not markdown, so
   keep entries as sentences (no `-`/`*` bullets).
4. **Commit the bump + changelog together** with message `Publish X.Y.Z`
   (matches the vocab in `~/.claude/general/workflow.md`).
5. **Tag annotated** — `git tag -a vX.Y.Z -m "X.Y.Z"`. Lightweight tags
   (plain `git tag vX.Y.Z`) are skipped by `git push --follow-tags` and by
   `gh release create`'s tag-lookup, so always annotate.
6. **Build** — `./gradlew buildPlugin`. Produces
   `build/distributions/vibe-stranding-X.Y.Z.zip`.
7. **Push** — `git push origin main --follow-tags` (annotated tag goes with).
8. **Create the GitHub release** with the zip attached. Extract the new
   `CHANGELOG.md` section as the release body so it matches what's bundled
   in the zip:
   ```
   awk '/^## /{c++; next} c==1' CHANGELOG.md > /tmp/release-notes.md
   gh release create vX.Y.Z build/distributions/vibe-stranding-X.Y.Z.zip \
     --title "X.Y.Z" --notes-file /tmp/release-notes.md
   ```
9. **Report the release URL** back to the user — that's the "where it lives"
   answer. Local zip path is secondary.

If anything fails partway, stop and surface it — don't paper over (e.g. don't
retag, don't `--force`).

## Version source

Only `build.gradle.kts:9` (`version = "X.Y.Z"`). `plugin.xml` has no
`<version>` element; Gradle injects it at build time via `patchPluginXml`.
Don't add a second version string elsewhere.

## Build hygiene

- Never `--no-verify`. The ktlint hook in `.claude/hooks/ktlint-format.sh`
  runs on edits — let it.
- `./gradlew buildPlugin` is the canonical build. Don't hand-zip from
  `build/libs/`.

## Threading (EDT vs background)

EDT violations only show up at runtime — `Slow operations are prohibited
on EDT` or `Access is allowed from event dispatch thread only`. Get this
right at write time; the user shouldn't have to run the IDE and paste
the stack to catch it.

**On the EDT by default (don't block it):**
- `AnAction.actionPerformed` / `update`, `DataContext` access
- Anything Swing: `Messages.show*`, `JBPopupFactory`, `JComponent`,
  `Content`, `ToolWindow`, `Notification.notify`
- Terminal-widget creation (`TerminalToolWindowManager.createLocalShellWidget`),
  tab color/label, `Content.setTabColor`, `Swing.Timer` ticks

**MUST run off the EDT:**
- Subprocess: `GeneralCommandLine`, `OSProcessHandler`,
  `CapturingProcessHandler` (git, claude, zsh). Platform actively
  asserts — not a soft warning.
- Every `GitStrands` method (they all eventually shell out — including
  ones that look pure like `ensureLinks` / `ensureClaudeHooks`)
- Network and non-trivial file IO

**Helpers already in `Actions.kt`** — use them, don't roll your own:
- `runInBackground(project, "Title") { ... }` wraps `Task.Backgroundable`
- `onEdt { ... }` wraps `ApplicationManager.getApplication().invokeLater`
- `HttpHandler` is the exception: it uses `invokeAndWait` because Netty
  needs the result before writing the response

Typical `AnAction` shape:
```kotlin
override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    // EDT-safe prompts go here (Messages.showInputDialog, etc.)
    runInBackground(project, "Doing X") {
        val r = svc.gitWork(strand)         // off EDT
        onEdt { notify(project, r.message) } // back on EDT for Swing
    }
}
```

**KDoc the threading on anything non-trivial** — `/** EDT. ... */`,
`/** Safe to call from any thread — bounced to EDT internally. */`,
or `/** Must be called off the EDT. */`. If you can't decide which to
write, the code is the bug.
