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
