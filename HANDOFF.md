# HANDOFF — Claude Eclipse Plugin

Last updated: 2026-05-12

This document hands off the project to a new developer/Claude session. Read it once and you should be productive immediately.

---

## 1. Where the code lives

**Primary repo (Azure DevOps):**
https://vstsleumi.visualstudio.com/AI-helper-extensions/_git/claude-eclipse-plugin

**Mirror (read-only, may lag):** https://github.com/zvirot1/claude-eclipse-plugin

Clone:
```bash
git clone https://vstsleumi.visualstudio.com/AI-helper-extensions/_git/claude-eclipse-plugin
cd claude-eclipse-plugin
git checkout eclipse-4.38-dev   # default branch for active dev
```

---

## 2. Branch model

There are 4 supported Eclipse versions, each with its own independent trunk (parallel codebases — different Eclipse APIs).

For every version `X.Y` ∈ {4.32, 4.34, 4.35, 4.38}:

| Branch | Purpose |
|---|---|
| `eclipse-X.Y-dev` | Active development. Code only. |
| `eclipse-X.Y-release/<timestamp>` | Build candidate. Same code + `releases/<zip>`. Tested before promotion. |
| `eclipse-X.Y-main` | Production. Code + cumulative `releases/<zip-history>`. |

**Workflow:**
1. Develop on `eclipse-X.Y-dev`.
2. Build via `./build-update-site.sh` → creates `claude-eclipse-plugin-update-site-X.Y-<timestamp>.zip`.
3. Create `eclipse-X.Y-release/<timestamp>` from `dev`, place the zip under `releases/`, commit, tag `vX.Y-<timestamp>`.
4. Test the zip in a real Eclipse.
5. Merge release → `eclipse-X.Y-main` (the zip accumulates in `main/releases/`).
6. Fix that applies to multiple versions → cherry-pick between `eclipse-X.Y-dev` branches.

**Default branch on VSTS:** `eclipse-4.38-dev` (most active version).

---

## 3. Where to download builds

Each `eclipse-X.Y-main` has a `releases/` folder with the latest production zip(s):
- 4.32 → https://vstsleumi.visualstudio.com/AI-helper-extensions/_git/claude-eclipse-plugin?path=/releases&version=GBeclipse-4.32-main
- 4.34 → same pattern with `eclipse-4.34-main`
- 4.35 → same pattern with `eclipse-4.35-main`
- 4.38 → same pattern with `eclipse-4.38-main`

Install via Eclipse: **Help → Install New Software → Add → Archive** → pick the zip.

---

## 4. Build system

`build-update-site.sh` (git-bash) — compiles, packages the plugin jar, builds a feature jar, runs p2 publishers, zips into `claude-eclipse-plugin-update-site-X.Y-<timestamp>.zip`.

**Pre-reqs on a fresh machine:**
- JDK 17+ in PATH
- Eclipse 2025-12 (or matching version) installed at `C:/eclipse2025-12/eclipse` (or `C:/eclipse`, `C:/dev/eclipse25-12/eclipse` — the script searches). Needed for both `org.eclipse.*.jar` classpath and for the p2 publisher launcher.
- git-bash

Run: `./build-update-site.sh` (optional first arg = timestamp override).

The script aborts loudly if javac produced < 100 class files (catches silent-fail bugs we've been bitten by).

---

## 5. Architecture pointers

Key packages under `src/com/anthropic/eclipse/claude/`:
- `views/ClaudeConversationView.java` — main chat view, tab lifecycle, IMemento persistence.
- `views/SessionHistoryDialog.java` — Resume picker. Lazy-loads file enumeration + on-demand content.
- `session/JsonlSessionScanner.java` — JSONL parser. Tolerates CLI 2.1.107 format (no top-level `type:"assistant"` — fallback to `message.role`).
- `views/SkillsDialog.java` — Skills/plugins UI. **Has pending bugs** (see §7).
- `views/widgets/MessageComposite.java` — single message rendering, image attach, markdown.
- `cli/` — Claude CLI process management (per-tab).

---

## 6. Recent significant fixes (May 2026)

1. **CLI 2.1.107 JSONL format change.** `type:"assistant"` no longer at top level. Parser now falls back to `message.role`. (Both `JsonlSessionScanner` and `ClaudeConversationView.loadSessionHistoryFromJsonl`.)
2. **`resumeSession` no longer aborts on CLI failure.** History loader runs regardless — empty Resume tabs after Eclipse restart are gone.
3. **Title-based recovery for orphaned tabs.** When a saved tab has no `sessionId` in its memento, match by saved title against scanned summaries.
4. **Session History dialog performance.** With 1.5 GB of JSONL, was >30 s. Now: fast file-list (mtime only), then background `fillSessionDetails` per row with incremental UI refresh. Plus a substring pre-filter (`"type":"user|assistant|summary"` or `"role":"assistant"`) before JSON parse.
5. **Active-file chip X button.** Dismissing visually now actually stops the file from being sent. `getActiveEditorContext()` consults `dismissedActiveFilePaths`.
6. **Prepended file-block stripping.** Both legacy `<file path=...>` and current `[Active editor context: …]` prefixes are removed before display + before saving summaries.

Tags in git for each released build: `vX.Y-<timestamp>`. Latest in production for 4.38: `v4.38-202605070130`.

---

## 7. Known pending work (good first task)

There is a plan file at `~/.claude/plans/happy-tinkering-fern.md` for the **Skills dialog bug fix + configurable folder**. Summary:

- **Bug 1:** "Open Folder" button on Skills dialog runs macOS-only `open` — no-op on Windows.
- **Bug 2:** Hardcoded path `~/skills/skills/` (doubled prefix). Should default to `~/.claude/skills/`.
- **Bug 3:** No way to change the folder.

Plan covers: new pref `SKILLS_FOLDER`, default `<user.home>/.claude/skills`, `PreferencePage` field editor, Browse button on the dialog, cross-platform Open via `Program.launch` → `rundll32 url.dll,FileProtocolHandler` → AWT Desktop fallback (same chain as `MessageComposite.openImageInExternalViewer`).

Files: `PreferenceConstants.java`, `PreferenceInitializer.java`, `ClaudePreferencePage.java`, `SkillsDialog.java`.

---

## 8. Conventions

- Java source/target: 11.
- Commit messages: 1–2 sentences explaining **why**. Co-authored trailer for Claude collaborations.
- ZIP files **never** committed to `dev`. Only to `release/<ts>` and `main` under `releases/`. `.gitignore` has `*.zip` + `!releases/*.zip`.
- Cherry-pick across versions: do it from the `-dev` branch of each version. Don't merge a 4.38-specific commit into 4.32 blindly — Eclipse APIs differ.
- Diagnostic logging: gated by preference or `-Dclaude.diag=true`. Use `[DIAG]` prefix.

---

## 9. If you're continuing the previous Claude Code session

The session JSONL is at:
```
%USERPROFILE%\.claude\projects\C--dev-claudecode-claude-eclipse-plugin\88237ebd-61e7-43b6-b8e7-15f7b106b7e1.jsonl
```
Copy that whole directory to your machine (same path on the new host), clone the repo to `C:\dev\claudecode\claude-eclipse-plugin`, and run:
```bash
claude --resume 88237ebd-61e7-43b6-b8e7-15f7b106b7e1
```

If you'd rather start fresh, just open Claude Code in the cloned repo and paste this file — that's enough context.
