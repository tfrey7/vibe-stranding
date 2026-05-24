<p align="center">
  <img src="assets/logo.png" alt="Vibe Stranding" width="600">
</p>

*The first strand-based vibe coding system.*

A small WebStorm / IntelliJ plugin for an ephemeral-strand-per-worktree
workflow: each in-flight strand of work (a feature, a bug fix, or an
investigation) gets its own git worktree and terminal tab, and the workspace
is thrown away once the strand lands on `main`.

- **New Strand…** — prompts for a free-text description, asks `claude` to
  pick a kebab-case slug + emoji, runs
  `git worktree add -b strand/<name> ../<project>-strands/<name> main`,
  symlinks `node_modules` and `.env` from the main checkout, and opens a
  terminal tab named for the strand running `claude` in it.
- **Finish Strand…** — rebases `strand/<name>` onto the default branch,
  fast-forwards it, then deletes the worktree (the expected next step in
  the ephemeral model). On rebase conflict it stops and tells you where to
  resolve.
- **Delete Strand…** — `git worktree remove` + `git branch -d` (or `-D`
  with Force). Standalone path for strands being abandoned without
  finishing.

The "This Strand" variants act on whichever strand owns the focused
terminal tab.

All five live under **Tools ▸ Vibe Stranding** and on the Terminal tool
window's toolbar dropdown.

## Build / run

```bash
./gradlew runIde      # launches a sandbox IDE with the plugin loaded
./gradlew buildPlugin # produces build/distributions/vibe-stranding-*.zip
```

## Local install cheat sheet

Use this flow to load the plugin into your real WebStorm (no marketplace
publish required).

```bash
# 1. Build a fresh zip
./gradlew buildPlugin

# 2. Locate it
open build/distributions/        # zip is vibe-stranding-<version>.zip
```

If you see an error like `The operation couldn’t be completed. Unable to locate a Java Runtime.`, you can download the 
.zip directly from the [releases page](https://github.com/tfrey7/vibe-stranding/releases) instead.

In WebStorm:

1. **Settings ▸ Plugins**
2. Gear icon ▸ **Install Plugin from Disk…**
3. Pick `build/distributions/vibe-stranding-<version>.zip`
4. Restart when prompted

**Upgrading** — same flow. Rebuild, install from disk over the existing
install, restart. WebStorm replaces the prior version in place.

**Uninstalling** — Settings ▸ Plugins ▸ Installed ▸ Vibe Stranding ▸
gear icon ▸ Uninstall.

**Gotchas**

- `sinceBuild = "261"` in `build.gradle.kts` means the zip only loads on
  WebStorm 2026.1+ (build 261.x). Older IDEs reject it.
- `build/distributions/` may contain a stale `feature-worktrees-*.zip`
  from before the rename — grab `vibe-stranding-*.zip`.
- Bump `version` in `build.gradle.kts` before rebuilding if you want to
  tell successive local installs apart in the Plugins list.

## HTTP API

The plugin also serves an HTTP API on the IDE's built-in port (default
63342) so a Claude Code session can spawn / finish / delete strands without
clicking through menus:

```
GET|POST /api/vibe-stranding/list
POST     /api/vibe-stranding/new?description=<free text>
POST     /api/vibe-stranding/new?name=<kebab>[&emoji=<one-emoji>]
POST     /api/vibe-stranding/finish?name=<kebab>[&delete=true]
POST     /api/vibe-stranding/delete?name=<kebab>[&force=true]
```

All endpoints take an optional `?project=<name|basePath>` to pick which IDE
window to act on when more than one is open.

## Things you may need to adjust

- **Terminal engine** — `TerminalTabs.openTerminalTab` uses the classic
  terminal API. If your WebStorm runs the reworked terminal engine and tabs
  don't appear, swap that one function for the `TerminalToolWindowTabsManager`
  path documented in the file.
- **Platform version** — `build.gradle.kts` targets IDEA Community 2026.1.2
  with `sinceBuild = "261"`. Bump both to match your WebStorm build, or
  switch `intellijIdeaCommunity("2026.1.2")` to `webstorm("…")`.
- **Git executable lookup** — `GitStrands.gitPath()` calls
  `GitExecutableManager.getPathToGit(project)`; confirm the signature on your
  platform version (it falls back to `git` on PATH regardless).

## Not built yet

- Auto-launching the merge tool on rebase conflict (extension point marked in
  `Actions.kt`).
- A tool window listing live strands with status (ahead-of-main count,
  dirty/clean, last activity) so you can see at a glance what's in flight.
