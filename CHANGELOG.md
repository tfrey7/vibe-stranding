# Changelog

Sections marked with `## X.Y.Z — YYYY-MM-DD` are picked up by
`build.gradle.kts` (latest section → bundled `plugin.xml`'s
`<change-notes>`) and by the publish flow in `CLAUDE.md`
(`gh release create --notes-file`, which the GH Pages workflow then
forwards into `updatePlugins.xml`).

Each release should add a new section at the top. Keep entries terse
prose — IntelliJ's change-notes view renders a subset of HTML, not
markdown, so bullet syntax won't render; use sentences.

## 0.3.0 — 2026-05-24

Persist strand emoji + description across IDE restarts; auto-publish updatePlugins.xml so installed copies can self-update from GitHub Pages; README rewrite.

## 0.2.1 — 2026-05-23

Fix silent symlink failures; .env / node_modules links now self-heal on Resume Strand.

## 0.2.0 — 2026-05-23

Add Open Strand in IDE action; harden Resume against missing session.

## 0.1.1 — 2026-05-23

Patch release. Install via Settings → Plugins → ⚙ → Install Plugin from Disk.
