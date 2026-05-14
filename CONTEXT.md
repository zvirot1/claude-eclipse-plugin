# CONTEXT — Full-session summary

> Synthesised from the entire 770 MB session JSONL
> (`88237ebd-61e7-43b6-b8e7-15f7b106b7e1.jsonl`, 22 001 records, ~669 user messages, ~2 830 assistant text turns, 516 file edits across 76 files, Hebrew/RTL throughout).
> Period covered: **2026-03-29 → 2026-05-12** (~6 weeks, dozens of `/compact` continuations).

---

## 1. Project & goal

**Repo:** `claude-eclipse-plugin` — an Eclipse plug-in (SWT, Java) that wraps the Claude Code CLI inside Eclipse, mirroring the official VS Code extension. A sibling project (`claude-intellij-plugin`) is developed in parallel; the two cross-pollinate fixes.

**Top-level goal that ran through the whole session:** make the Eclipse plug-in feature-parity with the VS Code extension, working correctly **on Windows in Hebrew**, deployable to several Eclipse releases (4.32, 4.34, 4.35, 4.38), with a clean `Help -> Install New Software` flow and a sane multi-version branching layout. Toward the end of the period, also: migrate hosting from GitHub to Azure DevOps (VSTS) and prepare a handoff for a second developer on a different machine.

The user works partly on a corporate machine (Bank Leumi) behind an AIM hook proxy with AWS Bedrock — many late-period bugs trace back to that environment.

---

## 2. Timeline of major work blocks

### 2.1 RTL / Hebrew rendering and duplicate-text fix (29-30 Mar)
First explicit task: install the v1.0.0.202603291409 update site, check for (a) text duplication, (b) Hebrew right-alignment, (c) tool-call layout not mirrored.

Root cause for RTL: on Windows, `StyledText.setOrientation(SWT.RIGHT_TO_LEFT)` sets `WS_EX_LAYOUTRTL` which mirrors the coordinate system — calling `setAlignment(SWT.RIGHT)` on top causes a *double* mirror. Fix: set orientation only, never alignment.

Files: `src/com/anthropic/eclipse/claude/views/widgets/StreamingTextWidget.java`, `MessageComposite.java`.

The duplicate-assistant-text bug (text appearing twice in a bubble) was tracked the same day; the workaround that finally stuck was an `exactHalfDup` detector in `StreamingTextWidget.finalizeContent()` plus revised streaming bookkeeping.

### 2.2 Clipboard-image paste (Ctrl+Alt+I -> Ctrl+V) (29-30 Mar)
*"עוד לא , אני רוצה שיהיה אפשר להדביק תנונה מהclipboard"*. Implemented image paste from clipboard using `Display.addFilter(SWT.KeyDown)` (which fires *before* the native Text widget consumes Ctrl+V), wired both Ctrl+Alt+I and Ctrl+V, then added **thumbnail rendering** of the pasted image instead of just an "[Image N]" chip. Affected: `AttachmentManager.java`, `MessageComposite.java`, `ClaudeConversationView.java`.

### 2.3 Enter / Shift+Enter behaviour (30 Mar)
User wanted: any Enter = send, Shift+Enter = newline (regardless of which Enter key). Several iterations until stable. Same area: input listener in `ClaudeConversationView`.

### 2.4 Hebrew keyboard reverting to English on Enter (30 Mar — multi-hour debugging chase)
The most painful early bug: after pressing Enter or the Send button, the Windows IME flipped back to English. Many failed attempts (*"עדיין עובר לאנגלית"* repeated 10+ times). Eventually solved **not** with timing-based restore but by understanding *why* focus loss triggered the switch and addressing it at the SWT focus level. Approach was directed by the user: *"תחשוב על פתרון שלא קשור לתזמון, אי אפשר להסתמך על זה"*.

### 2.5 Eclipse-version branching scheme — first attempt (30 Mar)
*"שמור את הגרסאות בברנצ נפרד עבור כל גרסת אקליפס"*. Created branches `eclipse-4.32`, `eclipse-4.34`, `eclipse-4.35`, `eclipse-4.38`. ZIP installer naming convention settled on: `claude-eclipse-plugin-update-site-<eclipseVer>-<yyyyMMddHHmm>.zip`, with timestamp baked into both filename and bundle version.

### 2.6 LLM model list refresh and Eclipse 4.38 download (31 Mar)
Updated model picker to current Claude 4.6/4.5 line on both branches. Downloaded Eclipse 4.38 (2025-12) into `C:\eclipse2025-12` to verify compatibility.

### 2.7 Stop button — VS Code parity (31 Mar – 12 Apr)
User observed two differences vs VS Code (with screenshot): (1) VS Code's send arrow toggles into a stop square during streaming; Eclipse had a *separate* toolbar stop button. (2) VS Code preserves conversation memory after stop; Eclipse lost it.

Multiple rounds — the first "fix" stopped the stream visually but the CLI kept producing tokens. Final solution involved:
- Send button toggles to stop (`ClaudeConversationView.java` button-state machine).
- On stop: kill CLI process, then on next user message reattach with `claude --resume <sessionId>` so the model context is preserved.
- Eventually replaced the text labels "send"/"stop" with proper icons.

This block produced commits across all four `eclipse-X.Y` branches.

### 2.8 Side detour — installing VS Code and reading its code (31 Mar – 20 Apr)
*"אני מציע שתתקין vscode ועליו תתקין את הפלגין הרשמי"*. VS Code was installed and the extension dumped to `C:\dev\vscode`; key findings (each `WebviewPanel` owns its own CLI subprocess; class `UT` manages them; `spawnClaude()` per session) seeded the **CLI-per-session** rewrite below.

### 2.9 CLI-per-session architecture (15 Apr)
User pasted a detailed spec ([306], [310]): every `ClaudeConversationView` must own its own `ClaudeCliManager` instead of sharing a singleton, because the shared singleton was scrambling conversation context across tabs.

Branch `feature/cli-per-session`. New class `ClaudeSessionManager` (one per workbench, registers per-tab session IDs, owns cleanup on tab close). `ClaudeCliManager` made non-singleton. UI state (status indicator, stop button) became per-tab.

Files: `cli/ClaudeCliManager.java`, `cli/CliProcessConfig.java`, `cli/NdjsonProtocolHandler.java`, `cli/CliMessage.java`, `session/ClaudeSessionManager.java`, `session/JsonlSessionScanner.java`, `views/ClaudeConversationView.java`, `Activator.java`.

### 2.10 Effort selector (low/medium/high/max) (15-19 Apr)
Modeled after the VS Code "Effort" control inside ModeSelectorPopup. Initial implementation was visual-only; user explicitly asked to verify the model actually changes behaviour. Discovered the missing piece: changing effort **must restart the CLI** with `--effort <level> --resume <sessionId>` — otherwise the running CLI keeps the old value (VS Code restarts the CLI on every query for the same reason). Files: `views/widgets/EffortSliderWidget.java`, `views/widgets/ModeSelectorPopup.java`, `preferences/PreferenceInitializer.java`, `preferences/PreferenceConstants.java`.

### 2.11 Status-bar contribution (15-16 Apr, on a child worktree)
Branch `claude/romantic-euler` worked on a side-task. Fixed the status bar not rendering; merged back into 4.38. File: `views/ClaudeStatusBarContribution.java`.

### 2.12 Session resume + Session History dialog (20 Apr — early May)
Found that closing-and-reopening Eclipse lost a conversation that hadn't been explicitly resumed. Reviewed VS Code's `~/.claude/projects/<encoded>/<uuid>.jsonl` scanner; ported the logic into `session/JsonlSessionScanner.java` and a new `views/SessionHistoryDialog.java`. A separate Resume button was unified with Session History per user request.

Late-cycle bugs in this area (early May): tabs reopened on Eclipse start showed empty content (preview eagerly empty); fix was to defer preview load to row-click, and to retain stored conversation bodies. Commit `096bfc8 Resume restoration + Session History performance fixes`.

### 2.13 Diagnostic-log gate for the corporate machine (26-29 Apr)
Hebrew prompts silently failed on the remote (Bank Leumi) machine: the CLI got an AIM hook *"UserPromptSubmit operation blocked by hook: obfuscation attack detected"* response that the plug-in did not surface — the button just sat red, then went blue with no answer.

Built a unified diagnostic build gated by `-Dclaude.diag=true` and a Preferences flag. New `[DIAG]` log lines around send/receive/stop/finalize. User-facing improvement: when the CLI emits a hook-block error the plug-in now shows the message instead of swallowing it (per user's direct instruction `[499]`: *"אל תנסה לא לשלוח מה שאתה משער שייחסם אלא במקרה של חסימה פשוט תתן הודעה על כך"*).

### 2.14 MCP server support (28-30 Apr)
Tested adding a local MCP server through Eclipse. Added env-var **edit** (not just add/remove) to `views/McpServersDialog.java`. Some Bedrock/AIM-environment specifics for MCP discovery were diagnosed via the `mcp.lop.log` files the user provided. Closed with *"בעיית הMcp נפתרה"* `[550]`.

### 2.15 Active-File chip (Amazon-Q style auto-attach) (3-6 May)
User asked for VS Code/Amazon-Q-like behaviour: the file currently open in the editor is auto-attached to the next message. Iterations:
- `e3fd526` add chip,
- `c5277ae` hide raw `<file ...>` XML from rendered bubble,
- `81ca353` redesign chip as IntelliJ-style pill (icon + name + x),
- `8c896f5` auto-pin by default + per-path dismiss memory,
- `19412d5` (current HEAD vicinity) x-button hides chip entirely with per-path persistence so it doesn't reappear next message.

Files: `views/ClaudeConversationView.java`, `model/ConversationModel.java`, `model/IConversationListener.java`, plus the chip widget.

### 2.16 Skills folder dialog (3-4 May)
`SkillsDialog.java` — configurable Skills folder, cross-platform "Open Folder" (SWT `Program.launch` + `rundll32` fallback). User complaints `[574]` ("`open folder` does nothing, points to `~/skills/skills`") triggered the rewrite (`1e69405`).

### 2.17 Self-generated tab titles (5-6 May)
Backported from IntelliJ (commits `a122d84+ac63c73+f0bfa64`) into Eclipse — chat-tab title becomes a short summary of the first user message instead of "Claude Code". Commit `19def1b`.

### 2.18 Migration to Azure DevOps (VSTS) + new branch model (12 May)
The final block, and the one with the most lasting infrastructure impact — see Section 4.

### 2.19 Handoff to a second developer (12 May)
Tail end of session: produced `HANDOFF.md`, `CONTEXT.md` (this file), `TRANSFER_SESSION.md`, all in `eclipse-4.38-dev` and `eclipse-4.32-dev`. Discussed: the JSONL is too big to commit (~770 MB raw, 1.3 GB whole session dir); user accepted the "summary file + new conversation on the other machine" approach.

---

## 3. Code changes that matter (by file)

Order is roughly by edit-count / functional importance.

| File | What changed |
|---|---|
| `src/com/anthropic/eclipse/claude/views/ClaudeConversationView.java` (271 edits) | The whole UI shell of a tab. Send/stop button toggle, Enter-vs-Shift+Enter, Ctrl+V image paste, clipboard handler, status indicator wiring, per-tab CLI manager ownership, active-file chip wiring, welcome screen redesign. |
| `model/ConversationModel.java` (33 edits) | Per-view state (already not-singleton). Added: pinned-attachments list, active-file chip state, persistent dismiss memory, hook-block error surfacing. |
| `cli/ClaudeCliManager.java` (18 edits) | De-singleton'd. Owns one CLI process per instance. Supports `--resume <sessionId>` and `--effort <level>`. Restart on effort change. Stop kills the process. |
| `META-INF/MANIFEST.MF` (~20 edits across branches) | Bundle version bumped on every release; matches the timestamp in the zip filename. |
| `build-update-site.sh` (12 edits) | Builds the P2 update-site zip; timestamp argument; correct `category.xml`, `feature.xml`, `artifacts.xml`/`content.xml` generation. The repeated failures of "no plug-ins inside the zip" (early days) were here. |
| `views/SessionHistoryDialog.java` (11 edits) | New dialog. Lists sessions from `~/.claude/projects/...`. Lazy-loads preview on row-click (fixed empty-on-open bug). |
| `session/JsonlSessionScanner.java` (12 edits) | Scans the JSONL session dir, extracts last-user-message snippet, used by both auto-resume on restart and the dialog. |
| `cli/NdjsonProtocolHandler.java` (10 edits) | Stop-button correctness; recognise hook-block responses and bubble them up rather than swallowing. `[DIAG]` instrumentation. |
| `views/widgets/MessageComposite.java` (7 edits) | Bubble layout — RTL via orientation-only (no alignment), inline image rendering, hide attached-file XML from the visible bubble. |
| `views/widgets/StreamingTextWidget.java` (7 edits) | Final RTL fix + `exactHalfDup` duplicate detector with `[DIAG]` log. |
| `Activator.java` (6 edits) | Boot-time `ClaudeSessionManager` registration; session restoration from prior workbench memento. |
| `views/SkillsDialog.java` (6 edits) | Configurable Skills folder, cross-platform open-folder. |
| `views/widgets/EffortSliderWidget.java` (2 edits) + `ModeSelectorPopup.java` | Effort levels low/medium/high/max with CLI-restart on change. |
| `preferences/PreferenceInitializer.java`, `PreferenceConstants.java`, `ClaudePreferencePage.java` | Diagnostic-mode flag, effort default, per-path active-file dismiss store. |
| `views/McpServersDialog.java` (3 edits) | Edit-in-place for env-vars; not just add/remove. |
| `views/AttachmentManager.java` | Clipboard image extraction + thumbnail generation. |
| `util/JsonParser.java` | Minor robustness for the streaming NDJSON parser. |
| `views/ClaudeStatusBarContribution.java` | Per-tab status, fixed via the child worktree. |

---

## 4. Infrastructure / repo decisions

### 4.1 Hosting migration GitHub -> Azure DevOps (VSTS)
Triggered by `[647]` (*"תראה בשיחה של האינטליג'י את המעבר לעבוד בVSTS"*). New origin:
```
https://vstsleumi.visualstudio.com/AI-helper-extensions/_git/claude-eclipse-plugin
```
Same credentials as the IntelliJ project. From now on **all work is against VSTS**; GitHub remote (`github/*`) is kept only for historical reference.

### 4.2 Final branch model (per Eclipse version)
After several iterations the user landed on the same scheme as the IntelliJ / VS2022 projects:

```
eclipse-X.Y-dev                 <- active development, code only
eclipse-X.Y-release/<ts>        <- build candidate; contains code + releases/<single zip> + tag v<X.Y>-<ts>
eclipse-X.Y-main                <- production trunk: code + releases/<all production zips>
```

For each `X.Y` in `{4.32, 4.34, 4.35, 4.38}`. `eclipse-4.38-dev` is VSTS default.

Rationale recorded in [659]-[661]: it mirrors the IntelliJ workflow — dev for code only, `release/<ts>` branch is the immutable build artefact, main accumulates production zips under `releases/` (matches user's mental model of "the binaries we shipped").

### 4.3 .gitignore convention
Root-level `*.zip` blocked; `releases/*.zip` allowed (commit `4f07f6e`). Historical zips at the repo root were purged (`17a6267`).

### 4.4 README link rewrite
All install links repointed from `github.com/zvirot1/...` to `vstsleumi.visualstudio.com/.../eclipse-X.Y-main?path=/releases` per [663]. Done in every relevant branch (`5f3756c`).

### 4.5 ZIP naming convention
Stable form: `claude-eclipse-plugin-update-site-<X.Y>-<yyyyMMddHHmm>.zip` (sometimes with a feature suffix, e.g. `-chipdismiss`, `-autopin`). The same timestamp is used as the OSGi version qualifier in `MANIFEST.MF` and feature/plugin filenames inside the zip.

---

## 5. Open / pending work

1. **Skills folder UX** — last `SkillsDialog` work (3-4 May) addressed the "Open Folder does nothing" and the `~/skills/skills` double-path bug, but a deeper UX pass to align with IntelliJ's Skills experience was flagged at handoff time and is *not* fully closed.
2. **Active-file chip in Resumed sessions** — works for fresh sessions; behaviour on a `--resume` of an older session that already had attachments is "mostly fine" but only spot-checked.
3. **Corporate-environment hangs** — English requests "very slow but work"; long Hebrew prompts on the remote box still occasionally finish with no body. Diagnostic logging is in place; root cause believed to be AIM hook latency, but the plug-in side has no fix in flight.
4. **eclipse-4.34 / eclipse-4.35** — these branches received early fixes but were *not* re-baselined with later improvements (active-file chip, self-generated titles, resume performance). The VSTS link rewrite *was* done. Treat as semi-deprecated; the user only actively tests 4.32 and 4.38.
5. **Handoff to second developer (machine #2)** — `HANDOFF.md`, `CONTEXT.md`, `TRANSFER_SESSION.md` were pushed but the actual transfer hasn't happened yet. The 770 MB JSONL is **not** in git; needs to go via OneDrive or a side channel if the second dev wants `claude --resume` rather than a fresh summary-driven start.
6. **MCP on the corporate machine** — user reported `[550]` it was solved, but the fix is environment-side (their `claude.exe` config), not in plug-in code; if the second dev hits it they need the steps from late-April messages.

---

## 6. Key user directives (verbatim Hebrew quotes, chronological)

- `[1]` *"קרא את MEMORY.md כדי להכיר את הפרויקט. אנחנו בודקים את הפלאגין ב-Windows. צריך לבדוק: 1) אין כפילות טקסט 2) עברית מיושרת ימינה 3) layout של tool calls לא הפוך."* — opening brief.
- `[9]` *"בצורה רציפה בלי לשאול אותי רשות כל הזמן"* — saved into `MEMORY.md` as a permanent autonomy preference.
- `[16]` *"עוד לא , אני רוצה שיהיה אפשר להדביק תנונה מהclipboard"* — kicked off the clipboard-image work.
- `[41]` *"כרגע כפתור הenter הימני מבצע ירידת שורה והשמאלי שולח … אני רוצה ש shipft + enter ולא משנה איזה enter יוריד שורה וכל enter ישלח"* — defined Enter semantics.
- `[57]` *"תחשוב על פתרון שלא קשור לתזמון, אי אפשר להסתמך על זה"* — guided away from timing-based IME fix.
- `[83]` *"תבצע commit ותשמור בגיט גם את הקוד וגם את קובץ ההתקנה דרך help install new software"* — first end-of-day commit ritual.
- `[89]` *"שמור את הגרסאות בברנצ נפרד עבור כל גרסת אקליפס"* — birth of the per-version branch model.
- `[124]` *"אני רוצה שתבדוק טוב את כל הפונקציוליות של הפלגין, תוכל להשתמש ב windows-mcp ו mcp-control"* — initiates auto-driven QA via the MCP tools.
- `[146]` *"אני מציע שתתקין vscode ועליו תתקין את הפלגין הרשמי ותבדוק איך הוא מתנהג שם"* — VS Code as reference implementation.
- `[169]` *"אני רואה כמה הבדלים: בvscode כפתור השליחה הופך לכפתור עצירה כל זמן שהתשובה לא הושלמה … בeclipse לאחר סטופ הזכרון של השיחה עדיין לא נשמר"* — the two-bullet spec that drove weeks of work.
- `[252]` *"תדאג שהאקליפס הנוכחי יהיה נקי לגמרי מכל הפלגינים שבנינו … אח"כ תתקין דרך help -> install new software ובדוק שמותקן טוב … ורק אח"כ תשמור את הגרסא הטובה להתקנה בגיט בברנצ 4.38"* — install-cleanliness gate.
- `[303]` *"אני רוצה שתשמור ברנץ חדש שבו יהיה טיפול כך שכל session יהיה לו cli ייחודי"* — CLI-per-session, *the* architectural change of April.
- `[306]/[310]` — the detailed Hebrew spec (~30 lines) for CLI-per-session with the four numbered sections (per-view manager, central session manager, `~/.claude.json` locking, UI implications). This is the most important single user message of the session.
- `[311]` *"ראה את צילום הפלגין של cscide … נסה להבין מה השינויים, סכם אותם ובנה תוכנית ליישם אותם בפלגין של האקליפס. אל תבצע לפני שאתה מראה לי."* — explicit plan-before-act guard.
- `[376]` *"אני רוצה שתסכם לי את השינויים והתיקונים שבוצעו בצורה כזו שאוכל לממש אותם גם בפלגין של אינליג'י תעשה את זה כך שאוכל לבצע להוראות העתק הדבק"* — the cross-IDE port pattern.
- `[382]` *"קרא את המסמך הבא ותממש את הפיצ'ר של Effort בפלגין של Eclipse … הנקודה הכי חשובה: כל שינוי effort חייב להפעיל מחדש את ה-CLI עם --resume."* — Effort spec.
- `[428]/[430]` *"תעבור על כל הפעילות של שמירה של השיחות וביצוע resume תבין מה מתבצע איך הם נשמרים מתי ואיך הם משתחזרים תשווה גם מול התוסף של vscode שנמצא C:\dev\vscode"* — Resume study.
- `[473]` *"אני מציע שלפני התיקון תייצר גרסא שתכתוב ללוג את האירועים והשגיאות"* — directly produced the diagnostic build.
- `[499]` *"אל תנסה לא לשלוח מה שאתה משער שייחסם אלא במקרה של חסימה פשוט תתן הודעה על כך"* — design decision on hook-block UX.
- `[559]` *"חשוב האם יש דרך להוסיף משהוא דומה לactive file כמו שיש ב amazon q שמוסיף אוטומטית לקונטקסט את הקובץ שפתוח בעורך?"* — origin of the Active-File chip.
- `[647]/[648]` *"תראה בשיחה של האינטליג'י את המעבר לעבוד בVSTS … אני צריך גם כאן לעבוד עם VSTS באותה הצורה אבל כאן כבר יש לנו ברנצ עבוד כל גרסא של אקליפס"* — VSTS migration.
- `[653]` *"אולי תצור ככה: eclipse-4.32-main / eclipse-4.32-release/ בצורה הזו עבור כל גרסא של אקליפס"* — proposed the branch trio.
- `[659]` *"אני רוצה שהפתרון יהיה דומה לאינטלג'י וVS ושם כן היה DEV שבו נשמר רק הקוד וכשהיה בקשה בוצע קידום לברנץ release/[] ובו יש תקיה releases עם הzip להתקנה ואח"כ קידום לmain עבור ייצור."* — locked the final scheme.
- `[663]` *"עכשו תעבור על קבצי הreademe ועדכן את הקישורים שמופנים לגיטהב שיופנו לVSTS"*.
- `[664]/[666]/[667]/[669]` — handoff requests; `[669]` (*"אני מציע שתעבור על כל השיחה … ותייצר סיכום של כל תסכם רק מה שחשוב"*) is the directive that produced **this file**.

---

## 7. Things deliberately left out of this summary (one-liners)

- Dozens of *"תפעיל את האקליפס"* / *"סגרתי"* / *"בדוק"* / *"תמשיך"* filler turns — they only kept iteration going, no information.
- Repeated install-attempt failures with *"No repository found containing osgi.bundle…"* / *"Artifact not found"* errors — always the same root cause (stale P2 cache or zip whose internal version did not match the qualifier in the filename) and the same fix (rebuild zip with consistent timestamp; clear `p2/.../cache`).
- The first failed RTL fixes that flipped alignment instead of orientation — superseded by the orientation-only solution.
- The 4-5 timing-based Hebrew-IME attempts before the focus-based fix took.
- Long screenshot back-and-forth using `windows-mcp` / `mcp-control` to drive Eclipse — only the *outcomes* (button position, icon look, chip behaviour) are kept above.
- Side debugging of the VS Code extension's internal class names beyond what was directly ported (`UT`, `WebviewPanel`, `spawnClaude`).
- The brief Claude Desktop screen-sharing detour to compare layouts.
- Conversation-name-from-content chatter — the feature *is* in (commit `19def1b`); the discussion around it is not interesting on its own.
- All `<task-notification>`, `<local-command-stdout>`, image-pixel-coordinate, and *"Continue from where you left off"* lines.
- The ~12 `/compact` continuation summaries (each session-continuation block) — they are themselves summaries; this file replaces them all.
