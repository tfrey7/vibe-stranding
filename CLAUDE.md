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
3. **Commit the bump** with message `Publish X.Y.Z` (matches the vocab in
   `~/.claude/general/workflow.md`).
4. **Tag annotated** — `git tag -a vX.Y.Z -m "X.Y.Z"`. Lightweight tags
   (plain `git tag vX.Y.Z`) are skipped by `git push --follow-tags` and by
   `gh release create`'s tag-lookup, so always annotate.
5. **Build** — `./gradlew buildPlugin`. Produces
   `build/distributions/vibe-stranding-X.Y.Z.zip`.
6. **Push** — `git push origin main --follow-tags` (annotated tag goes with).
7. **Create the GitHub release** with the zip attached:
   ```
   gh release create vX.Y.Z build/distributions/vibe-stranding-X.Y.Z.zip \
     --title "X.Y.Z" --notes "<one-line summary>"
   ```
8. **Report the release URL** back to the user — that's the "where it lives"
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
