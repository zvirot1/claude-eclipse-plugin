# PLANS — original blueprints and their status

This file collects the formal plan documents that shaped the project, with notes on what got implemented vs. what is still open. Read alongside `CONTEXT.md` (the chronological narrative) and `HANDOFF.md` (the operational guide).

---

## 0. Was there an "initial plan"?

**No formal one.** The first git commit is `5b674ad` (2026-03-19) — `"Eclipse Claude Code plugin - production-ready release"` — already **16,454 lines across 72 files**, with the full chat view, tool support, MCP, markdown rendering, theming, etc. in place. There is no `INITIAL_PLAN.md` in history.

What did exist was a *reference implementation*: the IntelliJ plugin (`C:/dev/claudecode/claude-intelij-plugin/`). The Eclipse plugin was conceptually a port of it, then diverged as Eclipse-specific issues surfaced. Many design decisions in the early codebase mirror IntelliJ's `ClaudeChatPanel`.

After the initial commit, work moved by **issue-driven plan files** under `~/.claude/plans/`, captured below.

---

## 1. piped-snuggling-noodle — *Strip file-XML on JSONL replay + true Reconnect*

**Status:** ✅ **DONE** (ported from IntelliJ wording to Eclipse implementation)

**Origin:** The plan was authored against the **IntelliJ** plugin (`src/main/java/com/anthropic/claude/intellij/ui/ClaudeChatPanel.java`). The same conceptual fixes were re-implemented for Eclipse in `ClaudeConversationView.java`.

**Three issues addressed:**

1. **Raw `<file path=…>…</file>` blocks reappearing in user bubbles on JSONL replay** — fixed in Eclipse by `stripPrependedFileBlocks(String)` applied in `loadSessionHistoryFromJsonl` before feeding text to the bubble.
2. **Reconnect button wiping the chat** — IntelliJ-specific symptom; Eclipse equivalent fix: reconnect path now restarts the CLI with `--resume <sessionId>` rather than clearing the conversation.
3. **Post-wipe replay re-rendering the old conversation with the XML** — same root cause; resolved by #1.

In the Eclipse session, the same regex was later **extended** to also strip the newer `[Active editor context: …]` prefix once the chip mechanism replaced the legacy `<file>` block format:

```java
s = s.replaceAll("(?is)^\\s*\\[Active editor context:[^\\]]*\\]\\s*", "");
s = s.replaceAll("(?is)^(?:\\s*<file\\s+path=\"[^\"]*\"[^>]*>.*?</file>\\s*)+", "");
```

**Files touched (Eclipse):**
- `src/com/anthropic/eclipse/claude/views/ClaudeConversationView.java`
- `src/com/anthropic/eclipse/claude/session/JsonlSessionScanner.java` (`stripPrependedFileBlocksForSummary`)

**Notes on what changed vs. plan:** the plan called out IntelliJ files only; Eclipse adoption was implicit. The Eclipse implementation also has to deal with **CLI 2.1.107's JSONL format change** (no top-level `type:"assistant"`, only `message.role`) which the original plan didn't anticipate — that addition is its own un-planned fix described in `CONTEXT.md`.

---

## 2. happy-tinkering-fern — *Fix Skills dialog folder bugs + make folder configurable*

**Status:** ⏳ **PENDING** — this is the currently-open task for whoever continues development.

### Context (from the plan)

The "Local Skills" tab of the **Claude Code – Skills & Plugins** dialog has three problems users hit immediately:

1. **"Open Folder" button does nothing on Windows.** It runs the macOS-only `open` command and silently swallows the exception (`catch (Exception ignored) {}`), so on Windows nothing happens.
2. **The path "~/skills/skills/" is wrong.** Hard-coded as `Paths.get(home, "skills", "skills")` — a doubled prefix that does not match what the Claude CLI actually uses (`~/.claude/skills/`).
3. **No way to point the dialog at a different folder.** No preference, no UI; the path is immutable at runtime.

**Goal:** Open Folder works on every OS, default points at `~/.claude/skills/`, user can override the path either inline (Browse button) or globally (Preferences > Claude AI).

### Files to modify

| File | Change |
|---|---|
| `src/com/anthropic/eclipse/claude/preferences/PreferenceConstants.java` | New constant `SKILLS_FOLDER = "skillsFolder"` |
| `src/com/anthropic/eclipse/claude/preferences/PreferenceInitializer.java` | Default = `<user.home>/.claude/skills` |
| `src/com/anthropic/eclipse/claude/preferences/ClaudePreferencePage.java` | New `DirectoryFieldEditor` for the skills folder |
| `src/com/anthropic/eclipse/claude/views/SkillsDialog.java` | Read path from preference, add Browse button, fix Open Folder |

### Approach (condensed)

**Preference key + default + page entry**
```java
// PreferenceConstants
public static final String SKILLS_FOLDER = "skillsFolder";

// PreferenceInitializer
store.setDefault(PreferenceConstants.SKILLS_FOLDER,
    Paths.get(System.getProperty("user.home"), ".claude", "skills").toString());

// ClaudePreferencePage
addField(new DirectoryFieldEditor(SKILLS_FOLDER, "Local skills folder:", getFieldEditorParent()));
```

**SkillsDialog reads from preference** (replaces the hard-coded `Paths.get(home, "skills", "skills")` at the current line 144):
```java
String configured = Activator.getDefault().getPreferenceStore()
        .getString(PreferenceConstants.SKILLS_FOLDER);
if (configured == null || configured.isBlank()) {
    configured = Paths.get(home, ".claude", "skills").toString();
}
this.localSkillsDir = Paths.get(configured);
```

**Cross-platform "Open Folder"** — three-tier fallback mirroring `MessageComposite.openImageInExternalViewer`:
```java
openFolderBtn.addListener(SWT.Selection, e -> {
    try {
        java.nio.file.Files.createDirectories(localSkillsDir);
        String absPath = localSkillsDir.toAbsolutePath().toString();
        if (org.eclipse.swt.program.Program.launch(absPath)) return;
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", absPath).start();
            return;
        }
        if (java.awt.Desktop.isDesktopSupported()) {
            java.awt.Desktop.getDesktop().open(localSkillsDir.toFile());
            return;
        }
        MessageDialog.openInformation(getShell(), "Folder",
                "Could not open the folder. Path:\n" + absPath);
    } catch (Exception ex) {
        Activator.logError("[SkillsDialog] open folder failed: " + ex.getMessage(), ex);
        MessageDialog.openError(getShell(), "Open folder",
                "Failed to open folder: " + ex.getMessage());
    }
});
```

**Browse… button** + `DirectoryDialog`, persists the chosen path to the same preference and calls `updateHeaderLabel()` + `refreshLocalSkills()`.

**Preference change synchronisation:** `PropertyChangeListener` on the preference store so changes made via Preferences while the dialog is open update the dialog header live.

**Header label refresh:** extract the existing label-setting code into a helper `updateHeaderLabel()` and call it on construction, on Browse, and on preference change. Render `localSkillsDir.toString()` literally — drop the hard-coded `"~/skills/skills/"` string.

### Verification checklist

1. Compile: `javac -source 11 -target 11 …` succeeds with the existing single unchecked warning, no new errors.
2. Build the update-site ZIP via `./build-update-site.sh` — both 4.38 and 4.32.
3. Install in Eclipse 4.38 (`C:/eclipse2025-12/eclipse/eclipse.exe` or the new `C:\eclipse4.38\eclipse\eclipse.exe`).
4. Manual checks:
   - Default path: fresh install — header shows `<user>\.claude\skills` (not `~/skills/skills/`). Folder auto-created if missing.
   - Open Folder: click — File Explorer opens at the right path on Windows. Repeat on a system where the folder doesn't exist beforehand → still opens after auto-create.
   - Browse: click → DirectoryDialog → pick a different folder → header updates → skills list re-populates from new folder. Reopening the dialog uses the new path.
   - Preferences sync: Window → Preferences → Claude AI → change "Local skills folder" → Skills dialog (open or reopened) reflects the new path.
   - Empty folder: list renders empty cleanly, Open Folder still works.
5. Cherry-pick to `eclipse-4.32-dev`, rebuild, follow the standard release flow (`dev` → `release/<timestamp>` → `main`, with the zip under `releases/`).

---

## 3. Other plan files (not relevant to this project)

For completeness, plan files in `~/.claude/plans/` that **do not** apply to the Eclipse plugin:

| File | What it is | Why we skip it here |
|---|---|---|
| `distributed-gliding-bunny.md` | "Port All Eclipse + IntelliJ Fixes to VS 2022 (Round 4)" | Targets the **VS 2022** plugin, separate repo. |
| `lexical-marinating-lightning.md` | "Create Hello World Python File" | Sandbox/test plan, unrelated. |
| `tidy-bubbling-duckling.md` | Same Hello World stub | Unrelated. |

---

## How to add a new plan

When starting a non-trivial change:
1. Draft a plan in `~/.claude/plans/<adjective-gerund-noun>.md` (standard Claude Code plan layout).
2. Reference it in a new section here (`PLANS.md`) with status **🟡 IN PROGRESS**.
3. On merge to `eclipse-X.Y-main`, flip status to **✅ DONE** and add a short "What was actually done vs. plan" paragraph.

This keeps the project self-documenting without depending on the chat history.
