# CONTEXT — Compact summary for a new Claude session

> This file lets a fresh Claude Code session pick up exactly where the previous one left off, without copying the 770 MB JSONL. Paste this in or just say "read CONTEXT.md".

Last updated: 2026-05-12. Project: Claude AI Eclipse Plugin (unofficial).

---

## 1. What was being built

An Eclipse IDE plugin that talks to the Claude CLI via `stream-json`. Chat view, full tool support, MCP, image attachments, hooks, RTL, per-tab CLI, Resume via `--resume`, Session History dialog.

Supported Eclipse versions: **4.32, 4.34, 4.35, 4.38** — each on its own parallel trunk (different Eclipse APIs).

---

## 2. Recent fixes done in the last session (May 2026)

1. **CLI 2.1.107 JSONL format change.** New format has no top-level `type:"assistant"`; instead `{"parentUuid":..., "message":{"role":"assistant",...}}`. Parser fallback now reads `message.role` when `type` is null. Touched: `JsonlSessionScanner.java`, `ClaudeConversationView.loadSessionHistoryFromJsonl`.
2. **`resumeSession` no longer aborts on CLI failure.** History loader runs regardless; empty Resume tabs after Eclipse restart are gone. `ClaudeConversationView.resumeSession` wraps `cliManager.start` in try/catch + `logWarning`.
3. **Title-based recovery for orphaned tabs.** When a saved tab has no `sessionId` in its memento, match by trimmed saved title against scanned summaries (normalised — strip trailing `...`/`…`). `ClaudeConversationView.createPartControl`.
4. **Session History dialog performance.** 1.5 GB of JSONL used to take >30 s. Now:
   - `JsonlSessionScanner.listSessionsFast(projectDir)` — enumerate `.jsonl` files with mtime only.
   - `JsonlSessionScanner.fillSessionDetails(info)` — lazy fill of summary/model/count, called per-row in a background thread.
   - Pre-filter inside the parser: substring check for `"type":"user|assistant|summary"` or `"role":"assistant"` before JSON-parsing the line.
   - `SessionHistoryDialog.loadSessions` rewrites — synchronous fast list, then background `fillSessionDetails` with incremental `refreshRowFor(info)`.
   - `SessionHistoryDialog.showPreview` made async with `pendingPreviewSessionId` to drop stale results.
5. **Active-file chip X button.** Dismissing it visually now actually stops the file from being sent. `getActiveEditorContext()` respects `dismissedActiveFilePaths`.
6. **Prepended file-block stripping.** Both legacy `<file path=...>...</file>` and current `[Active editor context: …]` prefixes are removed before display + before saving summaries. Used in `stripPrependedFileBlocks` and `stripPrependedFileBlocksForSummary`.

Latest production zip for 4.38: `claude-eclipse-plugin-update-site-4.38-202605070130.zip` — installed and verified working. Tag: `v4.38-202605070130`.

---

## 3. Repo migration done after the compact (also May 2026)

**Migrated from GitHub to Azure DevOps (VSTS).**

- New origin: `https://vstsleumi.visualstudio.com/AI-helper-extensions/_git/claude-eclipse-plugin`
- GitHub remote kept as read-only mirror under the name `github`.

**Branch model now mirrors the VS2022 plugin's pattern**, with one set per Eclipse version:

| Pattern | Purpose |
|---|---|
| `eclipse-X.Y-dev` | Active development. Code only. |
| `eclipse-X.Y-release/<timestamp>` | Build candidate. `releases/<single-zip>`. |
| `eclipse-X.Y-main` | Production. `releases/<all-promoted-zips>` (cumulative). |

For all four versions (4.32, 4.34, 4.35, 4.38). `eclipse-4.38-dev` is the VSTS default branch.

Cleanup done as part of the migration:
- Old shared `main` branch deleted (was 58 commits behind, redundant).
- All historical ZIPs removed from trunks (each main branch had between 1 and 43 zips committed). `.gitignore` now has `*.zip` + `!releases/*.zip`.
- The latest production zip for each version was promoted into `eclipse-X.Y-main/releases/`.
- `README.md` updated on all relevant branches — installation link now points to the VSTS releases folder instead of GitHub Releases.
- `HANDOFF.md` added to `eclipse-4.38-dev` and `eclipse-4.32-dev` for new collaborators.

---

## 4. Currently in-progress / open

### Skills dialog plan (`~/.claude/plans/happy-tinkering-fern.md`)

Three bugs in the "Local Skills" tab of the **Claude Code – Skills & Plugins** dialog:
1. **"Open Folder" no-op on Windows** — currently runs macOS-only `open`, swallows the exception.
2. **Wrong default path** `~/skills/skills/` (doubled prefix). Should be `~/.claude/skills/`.
3. **No way to point at a different folder** — no preference, no UI.

Plan covers:
- New preference `SKILLS_FOLDER = "skillsFolder"` with default `<user.home>/.claude/skills`.
- `PreferenceInitializer` and `ClaudePreferencePage` updated (new `DirectoryFieldEditor`).
- `SkillsDialog.java` reads the path from the preference, exposes a `Browse…` button, listens to preference changes via `PropertyChangeListener`.
- Cross-platform Open Folder: `Program.launch` → Windows `rundll32 url.dll,FileProtocolHandler` → AWT `Desktop.open` fallback (same chain as `MessageComposite.openImageInExternalViewer`).
- Helper `updateHeaderLabel()` shows the active path live.

Files to touch:
- `src/com/anthropic/eclipse/claude/preferences/PreferenceConstants.java`
- `src/com/anthropic/eclipse/claude/preferences/PreferenceInitializer.java`
- `src/com/anthropic/eclipse/claude/preferences/ClaudePreferencePage.java`
- `src/com/anthropic/eclipse/claude/views/SkillsDialog.java`

After implementation: build, install in Eclipse 4.38 (`C:/eclipse2025-12/eclipse/eclipse.exe` or the new `C:\eclipse4.38\eclipse\eclipse.exe`), verify all four manual scenarios from the plan, then cherry-pick to `eclipse-4.32-dev`, build matching 4.32 zip, promote both via the dev → release/<ts> → main flow.

---

## 5. Architecture pointers (skim before editing)

- `views/ClaudeConversationView.java` — main chat view, tab lifecycle, IMemento persistence.
- `views/SessionHistoryDialog.java` — Resume picker. Lazy load.
- `session/JsonlSessionScanner.java` — JSONL parser. CLI-version-tolerant.
- `views/SkillsDialog.java` — Skills/plugins UI. **Has pending bugs above.**
- `views/widgets/MessageComposite.java` — single message rendering, image attach, markdown.
- `cli/` — Claude CLI process management (per-tab).

`build-update-site.sh` (git-bash) — compiles + builds plugin jar + feature jar + p2 update site. Aborts if javac produces < 100 classes (catches silent-fail).

---

## 6. Conventions

- Java source/target: 11.
- ZIPs never in `dev`. Only under `releases/` in `release/<ts>` and `main` branches.
- Cherry-pick across versions: from `-dev` to `-dev`. Don't merge 4.38-specific commits into 4.32 blindly — APIs differ.
- Commit trailer for Claude collaborations:
  ```
  Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
  ```

---

## 7. How to continue

You don't need the previous session's JSONL. Just:
1. Clone the repo, checkout `eclipse-4.38-dev`.
2. Tell the new Claude session: "Read `HANDOFF.md` and `CONTEXT.md`. The pending task is the Skills dialog plan. Proceed."

If for some reason you *do* want byte-perfect resume of the previous session, see `TRANSFER_SESSION.md`.
