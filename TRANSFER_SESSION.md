# TRANSFER_SESSION — how to hand off the project to another developer on another machine

This document is written so a **Claude Code session on the receiving machine can execute it directly**. Tell that Claude:
> "Read `TRANSFER_SESSION.md` and walk me through it."

It will run the steps for you.

---

## TL;DR

Two paths:

**Path A (recommended — fast, clean):** clone the repo, read `CONTEXT.md` + `HANDOFF.md`, start a fresh Claude session. No 770 MB transfer needed.

**Path B (only if you must):** literally resume the previous Claude session by physically copying its JSONL files between machines.

Path A covers ~99% of cases. Path B is for byte-perfect continuity.

---

## Path A — fresh session with full context

### A1. Clone the code

```bash
# Pick the same parent directory used on the original machine (recommended but not required):
mkdir -p /c/dev/claudecode && cd /c/dev/claudecode
git clone https://vstsleumi.visualstudio.com/AI-helper-extensions/_git/claude-eclipse-plugin
cd claude-eclipse-plugin
git checkout eclipse-4.38-dev   # or eclipse-4.32-dev for the older Eclipse line
```

Authentication: the receiver needs a Personal Access Token (PAT) for `vstsleumi.visualstudio.com`. Windows Credential Manager will prompt on the first `git clone` if `credential.helper=manager` is set. If not, configure it once:
```bash
git config --global credential.helper manager
```

### A2. Verify tools

The receiver needs:
- **JDK 17 or 21** in `PATH` (`java -version`, `javac -version`).
- **Eclipse 2025-12 (4.38)** installed somewhere the build script can find it. Script tries: `C:/eclipse2025-12/eclipse`, `C:/eclipse`, `C:/dev/eclipse25-12/eclipse`. If installed elsewhere, edit `ECLIPSE_PLUGINS_DIR` and the `ECLIPSE_HOME` search list in `build-update-site.sh`.
- **git-bash** (for the build script).
- **Claude Code CLI** authenticated (`claude --version` should work).

### A3. Load context into a new Claude session

In the cloned repo, run `claude`. As the first message:

> Read `HANDOFF.md` and `CONTEXT.md`. After reading them, summarise what the project is, what was just done, and what's pending. Then wait for instructions.

The two files together give Claude:
- Repo layout, branch model, build instructions (`HANDOFF.md`).
- Recent code changes, design decisions, currently open task (`CONTEXT.md`).

### A4. (Optional) Run a sanity build

```bash
./build-update-site.sh
```

Expected output ends with:
```
Build complete: version 1.0.0.<timestamp>
Plugin JAR:   build/com.anthropic.eclipse.claude_1.0.0.<timestamp>.jar
Feature JAR:  build/com.anthropic.eclipse.claude.feature_1.0.0.<timestamp>.jar
Update site:  build/update-site/
Zip archive:  claude-eclipse-plugin-update-site-4.38-<timestamp>.zip
```

If the receiver wants to validate the install: **Help → Install New Software → Add → Archive** → pick the zip → through the wizard → restart Eclipse.

### A5. Done

The receiver is up. Future work follows the standard flow described in `HANDOFF.md` §2 (dev → release/<ts> → main).

---

## Path B — physically resume the previous session

Only do this if you need byte-perfect continuation (very rare). Be aware:
- The session JSONL is **~770 MB** uncompressed.
- The entire project session dir is **~1.3 GB** (includes subagents and tool-results).
- Claude Code encodes the **absolute project path** into the session directory name. The receiving machine must store the session under the matching encoded path.

### B1. Identify the files on the sending machine

```
C:\Users\<user>\.claude\projects\C--dev-claudecode-claude-eclipse-plugin\
├── 88237ebd-61e7-43b6-b8e7-15f7b106b7e1.jsonl   ← main session (770 MB)
├── 88237ebd-61e7-43b6-b8e7-15f7b106b7e1\         ← subagent + tool-result side-files
├── memory\
└── (other older session files in the same project)
```

### B2. Compress + transfer

```bash
# Example, on the sending machine:
cd /c/Users/<user>/.claude/projects/
7z a -mx=5 claude-session-eclipse-plugin.7z C--dev-claudecode-claude-eclipse-plugin/
```
Expected compressed size: ~150–250 MB (JSONL is text-heavy).

Move the `.7z` to the receiving machine via OneDrive / network share / split-zip-by-email / Azure Artifacts. **Do not commit it to the repo** — that's why we're shipping it out-of-band.

### B3. Place files on the receiving machine

```bash
# On the receiving machine. Substitute <user> for the receiver's username.
cd /c/Users/<user>/.claude/projects/
7z x claude-session-eclipse-plugin.7z
```

You should now have `/c/Users/<user>/.claude/projects/C--dev-claudecode-claude-eclipse-plugin/...`.

### B4. Make the project path match

Claude Code expects the project directory to live at the absolute path encoded in the session folder name. Decode by reversing `--` → `\`:
```
C--dev-claudecode-claude-eclipse-plugin   →   C:\dev\claudecode\claude-eclipse-plugin
```

So the receiver must clone the code to **exactly** `C:\dev\claudecode\claude-eclipse-plugin`:
```bash
mkdir -p /c/dev/claudecode && cd /c/dev/claudecode
git clone https://vstsleumi.visualstudio.com/AI-helper-extensions/_git/claude-eclipse-plugin
```

(If the receiver wants the code under a different path, they need to rename the `~/.claude/projects/C--...` directory accordingly — encode the new absolute path by replacing path separators with `--`.)

### B5. Resume

```bash
cd /c/dev/claudecode/claude-eclipse-plugin
claude --resume 88237ebd-61e7-43b6-b8e7-15f7b106b7e1
```

Or run `claude --resume` with no ID and pick from the list — the transferred session should appear with its summary.

### B6. Sanity check

After Claude loads, ask: *"What were the last 3 things we did in this session?"* The response should mention: VSTS migration, branch model with dev/release/main, README + HANDOFF.md + CONTEXT.md changes.

---

## Why not commit the JSONL into VSTS?

We considered it. It is a bad idea because:
- 770 MB single file → VSTS push limits & enormous repo bloat permanently.
- The JSONL grows with every interaction — keeping it in git means re-pushing huge deltas constantly.
- The JSONL contains the entire raw transcript (file contents, snippets of code, possibly secrets that were typed in earlier). Git history makes that permanent.
- The textual summary in `CONTEXT.md` is what's actually useful for a successor; the raw transcript almost never is.

If you want a persistent backup of the JSONL, upload the `.7z` once to **Azure Artifacts as a Universal Package** (separate from the source repo) — versioned, access-controlled, doesn't pollute clones.
