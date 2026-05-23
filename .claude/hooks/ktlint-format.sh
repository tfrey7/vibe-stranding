#!/usr/bin/env bash
# Auto-format Kotlin files after Claude edits them.
# Wired via .claude/settings.json -> PostToolUse on Edit|Write|MultiEdit.
set -o pipefail

file=$(jq -r '.tool_input.file_path // empty')
[[ -z "$file" ]] && exit 0
[[ -n "${CLAUDE_PROJECT_DIR:-}" ]] || exit 0
[[ "$file" == "$CLAUDE_PROJECT_DIR"/* ]] || exit 0
[[ "$file" == *.kt || "$file" == *.kts ]] || exit 0

cd "$CLAUDE_PROJECT_DIR"
./gradlew -q ktlintFormat 2>&1 | tail -5 || true
