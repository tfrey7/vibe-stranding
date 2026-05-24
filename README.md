<p align="center">
  <img src="assets/logo.png" alt="Vibe Stranding" width="600">
</p>

*The first strand-based vibe coding system.*

## What it does

A WebStorm / IntelliJ plugin for running multiple Claude Code agents on the
same project in parallel.

Normally you'd give each agent its own branch — but a single checkout can
only have one branch checked out at a time, so parallel agents end up
stepping on each other's files. This plugin uses **git worktrees** instead:
each in-flight strand of work (a feature, a bug fix, an investigation) gets
its own sibling checkout on its own branch, with a terminal tab running
`claude` inside it. When the strand lands on `main`, the worktree is thrown
away.

Two actions, under **Tools ▸ Vibe Stranding** and on the Terminal tool
window's toolbar:

- **New Strand…** — spins up a fresh worktree for an agent to work in.
- **Finish Strand…** — lands that worktree's work back on `main`.

## Install

Grab the latest pre-built zip from the
[releases page](https://github.com/tfrey7/vibe-stranding/releases/latest) —
[![Latest release](https://img.shields.io/github/v/release/tfrey7/vibe-stranding?label=latest&color=blue)](https://github.com/tfrey7/vibe-stranding/releases/latest).

In WebStorm: **Settings ▸ Plugins ▸ ⚙ ▸ Install Plugin from Disk…** and
pick the downloaded `vibe-stranding-<version>.zip`. Restart when prompted.

## Build from source

Requires JDK 17+ on PATH. Gradle auto-downloads JDK 21 for the build itself.

```bash
./gradlew buildPlugin
```

Produces `build/distributions/vibe-stranding-<version>.zip`, which installs
the same way as the pre-built release above.
