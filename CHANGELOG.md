# Changelog

Sections marked with `## X.Y.Z — YYYY-MM-DD` are picked up by
`build.gradle.kts` (latest section → bundled `plugin.xml`'s
`<change-notes>`) and by the publish flow in `CLAUDE.md`
(`gh release create --notes-file`, which the GH Pages workflow then
forwards into `updatePlugins.xml`).

Each release should add a new section at the top, formatted as an HTML
`<ul><li>…</li></ul>` list — one bullet per atomic change. IntelliJ's
change-notes view renders a subset of HTML, not markdown, so use
`<ul>`/`<li>` rather than `-`/`*` bullets; inline `<code>` and `<em>`
are fine. GitHub release notes and `updatePlugins.xml` render the same
HTML, so one format works for all three destinations.

## 0.7.0 — 2026-05-25

<ul>
<li><em>View This Strand's Code</em> now hands the strand's running <code>claude</code> session off to the new IDE window instead of stranding it in the main window — the source tab closes and a <code>claude --continue</code> tab opens in the worktree project. Disabled while the strand's turn is mid-flight so the hand-off can't lose an in-progress reply. The worktree window's Resume Strand dropdown also surfaces the worktree-self entry, so the tab can be re-opened after being killed.</li>
<li>New <em>Create Strand From Selection</em> actions seed a new strand's claude session with selected text as its first prompt. Available from the editor / Run-Debug console right-click when text is selected, and from the Vibe Stranding dropdown when the focused terminal tab has a JediTerm selection.</li>
<li>The <code>new_strand</code> MCP tool now accepts an optional <code>prompt</code> that's sent to the new strand's claude session on launch, so callers can ignite work in one step instead of spawning + typing.</li>
<li>Support <code>.worktreeinclude</code> (gitignore syntax) for copying gitignored files into new strands — mirrors <code>claude --worktree</code>'s mechanism. Existing destination files are never overwritten, so the <code>node_modules</code> / <code>.env</code> symlinks take precedence and strand-local edits survive resume. With no file present, falls back to a built-in <code>.env.*</code> / <code>.envrc</code> pattern list so a typical strand picks up <code>.env.local</code> without per-project setup. Runs on create and on Resume Strand self-heal.</li>
<li>Dropped the bottom-right balloons announcing every strand create, delete, and finish — they were reading like log spam. Warnings and errors still notify.</li>
<li>Busy-state tab animation now matches the Claude CLI's dot: a single bouncing middle-dot (<code>·</code>) in a 2-char window, 600ms cadence (was 400ms).</li>
</ul>

## 0.6.1 — 2026-05-25

<ul>
<li>New strands no longer wipe out the MCP-server approvals from <code>.claude/settings.local.json</code> in the main checkout. The plugin used to write a fresh file containing only its own busy/idle hooks, forcing re-approval of every MCP server on every new strand; it now reads main's file as the base, overlays the plugin-owned <code>hooks</code> key, and writes the merged result. Existing strands self-heal on Resume Strand.</li>
<li>Switched off the <code>createLocalShellWidget</code> terminal API, which is <code>forRemoval</code> in IDEA 2026.1. The plugin now opens strand tabs through <code>TerminalToolWindowManager.createNewSession</code>, the closest non-deprecated public entry point.</li>
</ul>

## 0.6.0 — 2026-05-25

<ul>
<li>A few minutes after a strand is created, the configured LLM now generates a one-sentence summary of what the strand is about, grounded in its git log and diff against main. The blurb appears in the Resume Strand dropdown next to the emoji, and rides along on the <code>get_strand</code> / <code>list_strands</code> MCP responses as <code>generated_description</code>. The delay defaults to 5 minutes and is configurable at Settings → Tools → Vibe Stranding; strands with an empty diff at the timer fire reschedule rather than summarize nothing.</li>
<li>When a project opens, every existing strand now gets its terminal tab restored automatically, running <code>claude --continue</code> so each worktree's claude session picks up where it left off. The behavior is on by default and can be toggled at Settings → Tools → Vibe Stranding.</li>
<li>The Resume Strand dropdown now hides strands that already own an open tab, since selecting them just refocused the visible tab.</li>
<li>Plugin settings are now reachable from outside the IDE via a new <code>settings</code> MCP tool (and matching REST endpoint) — read the whole config with no args, or atomically update any subset of keys. The schema is generated from the same field list the Settings UI uses, so new options show up in both places automatically.</li>
<li>The mid-turn busy animation now replaces the strand emoji on the left of the tab rather than trailing on the right, so the tab reads like the Claude CLI prompt.</li>
<li>The IDE's project picker entry <em>Open Strand</em> is renamed to <em>View This Strand's Code</em> to match what it actually does.</li>
<li>Stray "no stdin data received" warnings from <code>claude -p</code> no longer leak into LLM output.</li>
</ul>

## 0.5.1 — 2026-05-24

<ul>
<li>Strand tabs now animate while their claude session is mid-turn — the tab title cycles through a short busy indicator on UserPromptSubmit and restores its idle label on Stop, so it's easy to tell at a glance which siblings are working.</li>
<li>New strands are wired up automatically via a per-strand <code>.claude/settings.local.json</code> hook file; existing strands self-heal the hooks on Resume Strand.</li>
</ul>

## 0.5.0 — 2026-05-24

<ul>
<li>Fix Summarize This Strand on macOS for users whose claude install lives on a PATH set up in <code>.zshrc</code> / <code>.zprofile</code> (npm-global, mise, asdf). The action previously shelled out via <code>bash -lc</code>, which only sources bash login config, so zsh users hit "claude: command not found". It now uses the same shell-environment path as the strand-emoji subprocess.</li>
<li>Add a 5-minute timeout so a hung summary surfaces instead of waiting forever.</li>
</ul>

## 0.4.0 — 2026-05-24

<ul>
<li>New Summarize This Strand action drops a <code>claude -p</code> summary into a side tool window.</li>
<li>New Strand opens its terminal tab instantly with a modal progress overlay during the git work.</li>
<li>Each strand tab now gets its own background color from a rotating palette, so siblings are easy to tell apart.</li>
<li>The strand dropdown inlines active strands as Resume entries and drops the separate Finish / Delete pickers.</li>
<li>The MCP server now exposes strand metadata: <code>list_strands</code> returns JSON (name, emoji, description, color) per strand, and a new <code>get_strand</code> tool returns the same shape for one strand.</li>
</ul>

## 0.3.1 — 2026-05-24

<ul>
<li>Populate the IDE's "What's New" panel — the bundled <code>plugin.xml</code> now ships with a <code>change-notes</code> element sourced from <code>CHANGELOG.md</code>, so installed copies show release notes in Settings → Plugins.</li>
</ul>

## 0.3.0 — 2026-05-24

<ul>
<li>Persist strand emoji + description across IDE restarts.</li>
<li>Auto-publish <code>updatePlugins.xml</code> so installed copies can self-update from GitHub Pages.</li>
<li>README rewrite.</li>
</ul>

## 0.2.1 — 2026-05-23

<ul>
<li>Fix silent symlink failures; <code>.env</code> / <code>node_modules</code> links now self-heal on Resume Strand.</li>
</ul>

## 0.2.0 — 2026-05-23

<ul>
<li>Add Open Strand in IDE action.</li>
<li>Harden Resume against missing session.</li>
</ul>

## 0.1.1 — 2026-05-23

<ul>
<li>Patch release. Install via Settings → Plugins → ⚙ → Install Plugin from Disk.</li>
</ul>
