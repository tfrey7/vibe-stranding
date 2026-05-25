# Changelog

Sections marked with `## X.Y.Z — YYYY-MM-DD` are picked up by
`build.gradle.kts` (latest section → bundled `plugin.xml`'s
`<change-notes>`) and by the publish flow in `CLAUDE.md`
(`gh release create --notes-file`, which the GH Pages workflow then
forwards into `updatePlugins.xml`).

Each release should add a new section at the top. Keep entries terse
prose — IntelliJ's change-notes view renders a subset of HTML, not
markdown, so bullet syntax won't render; use sentences.

## 0.5.1 — 2026-05-24

Strand tabs now animate while their claude session is mid-turn. New strands are wired up automatically via a per-strand .claude/settings.local.json hook file; existing strands self-heal the hooks on Resume Strand. The tab title cycles through a short busy indicator on UserPromptSubmit and restores its idle label on Stop, so it's easy to tell at a glance which siblings are working.

## 0.5.0 — 2026-05-24

Fix Summarize This Strand on macOS for users whose claude install lives on a PATH set up in .zshrc / .zprofile (npm-global, mise, asdf). The action previously shelled out via bash -lc, which only sources bash login config, so zsh users hit "claude: command not found". It now uses the same shell-environment path as the strand-emoji subprocess. Also adds a 5-minute timeout so a hung summary surfaces instead of waiting forever.

## 0.4.0 — 2026-05-24

New Summarize This Strand action drops a claude -p summary into a side tool window. New Strand opens its terminal tab instantly with a modal progress overlay during the git work, and each tab now gets its own background color from a rotating palette so siblings are easy to tell apart. The strand dropdown inlines active strands as Resume entries and drops the separate Finish / Delete pickers. The MCP server now exposes strand metadata: list_strands returns JSON (name, emoji, description, color) per strand, and a new get_strand tool returns the same shape for one strand.

## 0.3.1 — 2026-05-24

Populate the IDE's "What's New" panel — the bundled plugin.xml now ships with a change-notes element sourced from CHANGELOG.md, so installed copies show release notes in Settings → Plugins.

## 0.3.0 — 2026-05-24

Persist strand emoji + description across IDE restarts; auto-publish updatePlugins.xml so installed copies can self-update from GitHub Pages; README rewrite.

## 0.2.1 — 2026-05-23

Fix silent symlink failures; .env / node_modules links now self-heal on Resume Strand.

## 0.2.0 — 2026-05-23

Add Open Strand in IDE action; harden Resume against missing session.

## 0.1.1 — 2026-05-23

Patch release. Install via Settings → Plugins → ⚙ → Install Plugin from Disk.
