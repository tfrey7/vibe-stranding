# Using Feature Worktrees from Claude Code

This file teaches a Claude Code session how to drive the Feature Worktrees
IntelliJ plugin from the command line, so the user can say "start a feature
for X" and Claude will spawn a properly-named worktree + terminal tab inside
their IDE instead of inventing its own ad-hoc `git worktree` commands.

## How to install

Pick one:

1. **Import (recommended).** Add this line to `~/.claude/CLAUDE.md`:
   ```
   @<path-to-this-repo>/claude-workflow.md
   ```
   The file stays in sync with the plugin source; no copy to maintain.

2. **Copy/symlink.** Drop a copy of this file into `~/.claude/` and `@`-import
   it from your global `CLAUDE.md`, or symlink it. Use this if you want to
   pin a specific revision.

## When to use the plugin's HTTP API

The plugin exposes an HTTP API on the IDE's built-in server (default port
`63342`). Prefer it over rolling your own `git worktree add` / `git rebase` /
`git worktree remove` sequence whenever:

- The user asks to **start / create / spin up** a feature.
- The user asks to **ship / land** a feature ("ship the oauth-cleanup work").
- The user asks to **tear down / abandon / discard** a feature worktree.
- The user asks **what features are in flight** ("what worktrees do I have
  open?").

Using the API means the IDE's tab list, notification center, and rebase
conflict surface all stay in sync with what's on disk. Hand-rolled git
commands skip that and leave orphan tabs / stale UI.

## Endpoints

All endpoints live under `http://localhost:63342/api/feature-worktrees/`.
Responses are plain text. Status codes: `200` on success, `400` on bad input,
`409` on rebase conflict or name collision, `500` on internal failure.

### `POST /new`

Create a worktree + branch + named terminal tab running `claude`. Two input
styles:

- `?description=<url-encoded free text>` — preferred. The plugin asks
  `claude --model haiku` to coin a kebab slug + emoji, falls back to a naive
  slug + hash-based emoji if claude isn't reachable. Takes ~5–10s.
- `?name=<kebab-slug>[&emoji=<one-emoji>]` — explicit. Skips inference.
  Use this when the user already supplied a name verbatim.

Example:
```bash
curl -X POST 'http://localhost:63342/api/feature-worktrees/new?description=add%20oauth%20cleanup'
# → "Created feature 'oauth-cleanup' 🔐 at /path/to/repo-worktrees/oauth-cleanup"
```

### `POST /ship?name=<slug>[&teardown=true]`

Auto-commits any outstanding work in the feature worktree, rebases onto the
default branch, fast-forwards `main`. If `teardown=true`, also removes the
worktree and closes the tab on success.

A `409` here means rebase conflicts — surface the response body to the user
and let them resolve in the IDE.

### `POST /teardown?name=<slug>[&force=true]`

Removes the worktree, deletes the branch, closes the tab. Without `force`,
git refuses if the branch has unmerged commits — pass `force=true` only when
the user explicitly wants to discard work.

### `GET /list`

Newline-separated list of active feature slugs. Use this to disambiguate when
the user says "tear down the dark-mode one" without confirming the slug.

### Optional: `&project=<name|basePath>`

Every endpoint accepts a `project` parameter to pick which IDE window to act
on when more than one is open. With a single open project the parameter can
be omitted.

## Worked examples

**User:** "Let's start a feature for the dark mode redesign."
**Claude:**
```bash
curl -X POST 'http://localhost:63342/api/feature-worktrees/new?description=dark%20mode%20redesign'
```
Report the slug + emoji + path from the response so the user knows what
ended up in their tab list.

**User:** "Ship the oauth-cleanup work and clean it up."
**Claude:**
```bash
curl -X POST 'http://localhost:63342/api/feature-worktrees/ship?name=oauth-cleanup&teardown=true'
```

**User:** "What features do I have open?"
**Claude:**
```bash
curl -sS 'http://localhost:63342/api/feature-worktrees/list'
```

## When NOT to use the API

- The user is asking about main-branch git operations (commits, pushes,
  history) — those are just normal git.
- The user is inside a feature session already and asking about that
  feature's own work (editing files, running tests, committing). Only the
  *lifecycle* operations (new / ship / teardown / list) go through the API.
- The IDE isn't running or the port is unreachable — fall back to telling
  the user to use the Tools ▸ Feature Worktrees menu instead of inventing
  worktree commands.
