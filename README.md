# Claude AI Plugin for Eclipse (Unofficial)

A full-featured Eclipse IDE plugin for working with **Claude Code** directly inside Eclipse — chat, tool execution, MCP servers, file context, image attachments, and more. Built around the Claude CLI's `stream-json` protocol.

> **Note:** This is an unofficial community plugin. It uses the official Claude CLI under the hood.

---

## ✨ Key Features

| Feature | Description |
|---|---|
| 💬 **Chat View** | Multi-turn conversations with Claude inside an Eclipse view. Streaming, markdown, code blocks. |
| 🛠 **Full Tool Support** | Read / Edit / Write / Bash / Grep / Glob etc. — Claude can read & modify your workspace. |
| 🔌 **MCP Servers** | First-class integration with Model Context Protocol servers. Add via dialog or `claude mcp add`. |
| 📌 **Active-File Pin** | Auto-attaches the focused editor file as context. IntelliJ-style chip with × to dismiss per path. |
| 🖼 **Image Attachments** | Paste/attach images — Claude sees them, you see thumbnails inline. Click to open full size. |
| 🪝 **Hook-Aware** | Surfaces hook errors (e.g. AWS SSO token expired) with actionable hints. |
| 🔁 **Auto-Retry** | Silent-empty results (corporate UserPromptSubmit hooks) trigger one auto-retry transparently. |
| ⏱ **Fast Button Reset** | Send button returns to "send" mode immediately when text completes — no 12s lag. |
| 🔍 **Diagnostic Mode** | Toggle verbose `[DIAG]` logging via preference or `-Dclaude.diag=true`. |
| 🎨 **RTL & Hebrew** | Right-to-left detection per message; correct rendering of Hebrew code-mixed responses. |
| 🛡 **Stop with Resume** | Stop a running query — conversation memory is preserved via CLI `--resume`. |
| ⚙ **Per-Tab CLI** | Each chat tab owns its own CLI process; no cross-talk between sessions. |

---

## 📥 Installation

### Quick Install (Recommended)

1. Browse the **[releases folder on Azure DevOps](https://vstsleumi.visualstudio.com/AI-helper-extensions/_git/claude-eclipse-plugin?path=/releases&version=GBeclipse-4.32-main)**
2. Download the latest ZIP for **Eclipse 4.32** (`claude-eclipse-plugin-update-site-4.32-*.zip`)
3. In Eclipse: **Help → Install New Software → Add → Archive...**
4. Pick the downloaded ZIP, name it (e.g. *Claude AI*), click **Add**
5. Check **Claude AI Eclipse Plugin** → **Next** → **Next** → accept license → **Finish**
6. If a "*Unsigned content*" warning appears → **Install anyway**
7. **Restart Now** when prompted

### Updating

To update: **Help → About → Installation Details → Uninstall** the old version → restart → install the new ZIP.

### Prerequisites

- **Eclipse 4.32+** (Java 17 or 21)
- **Claude Code CLI** installed and authenticated:
  ```
  npm install -g @anthropic-ai/claude-code
  claude          # initial auth
  ```
- An **Anthropic API key** (or OAuth via `claude auth login`), or **AWS Bedrock** credentials.

---

## 🚀 Getting Started

1. **Open the chat view**: `Window → Show View → Other → Claude AI → Claude Code`
   (or **Ctrl+Shift+C** if registered)
2. **Wait for** ✓ Connected in the status bar
3. **Type a message** and press Enter
4. **Pin the current file** — opening any editor auto-shows a 📌 chip above the input. Click × to dismiss it for that path.

### Keyboard

| Shortcut | Action |
|---|---|
| `Enter` | Send message |
| `Shift+Enter` | New line in input |
| `Ctrl+Alt+I` | Paste image from clipboard |
| `Esc` | Stop streaming response |
| `Ctrl+@` | Open the attach menu |

### Slash Commands

Type `/` in the input. Available: `/help`, `/clear`, `/cost`, `/stop`, `/resume`, plus any installed Claude Code skills (`/init`, `/review`, etc.).

### `@`-mention Files

Type `@` to attach files / directories from your workspace as context. They appear as chips above the input.

---

## ⚙️ Configuration

**Window → Preferences → Claude AI**

| Setting | What it does |
|---|---|
| **Anthropic API Key** | Stored encrypted via Eclipse secure storage. Optional if using OAuth or Bedrock. |
| **Model** | `sonnet` (default) / `opus` / `haiku` / model ID |
| **Claude CLI Path** | Auto-detected; override if `claude` is not on PATH |
| **Permission Mode** | `Default (ask)` / `Accept Edits` / `Bypass` / `Plan Mode` |
| **Auto-Approve Tools** | Comma-separated tools always allowed (default: `Read,Grep,Glob`) |
| **Max Turns** | `0` = unlimited |
| **Theme** | `Auto` / `Light` / `Dark` |
| **Show streaming** | Live token streaming display (recommended ON) |
| **Enter to send** | If unchecked, Enter inserts newline and Ctrl+Enter sends |
| **Auto-save before tools** | Save dirty editors before Claude reads them |
| **Auto-attach active file** | Auto-pin the focused editor file (default ON) |
| **Enable diagnostic logging** | Verbose `[DIAG]` entries to Error Log |

### MCP Servers

**Toolbar → MCP Servers** opens a dialog with two tabs:
- **Project Servers** — `<project>/.mcp.json`
- **Global Servers** — root-level `mcpServers` in `~/.claude.json`

Double-click any cell in the env-vars table to **edit in place** (Enter saves, Esc cancels). Servers added via `claude mcp add --scope user` will show up in the Global tab.

Verify connections from the CLI:
```
claude mcp list
```

---

## 🐛 Troubleshooting

### "Empty response" / blocked prompts (corporate environments)

If an enterprise hook (e.g. AIM "obfuscation attack" detector) blocks your prompt, the plugin shows:

> ⚠ Your prompt was blocked.
> The CLI returned an empty response with 0 tokens used...
> Workarounds: rephrase in English, try again later, or ask your IT/AWS admin to relax the hook rule.

The plugin **automatically retries once** since these hooks are often non-deterministic. To see the exact reason from the CLI:
```
claude --debug
> <your prompt>
```

### AWS SSO token expired

You'll see a banner: `⚠ Hook 'SessionStart:resume' reported an error: Token has expired and refresh failed`. Fix:
```
aws sso login
```
Then close and reopen the Claude tab.

### Diagnostic logging

Turn on **Enable diagnostic logging** in Preferences. Reproduce the issue. **Window → Show View → Error Log → Export Log**. Log entries are tagged:

| Tag | Meaning |
|---|---|
| `[DIAG-MSG]` | Every CLI message received |
| `[DIAG-RAW-SYSTEM]` | Full content of system messages (init, hooks) |
| `[DIAG-RAW-RESULT]` | Full content of result messages with `resultLen=0` |
| `[DIAG-STDERR]` | CLI stderr lines |
| `[DIAG-LISTENER]` / `[DIAG-MODEL]` | Listener attach/detach + model lifecycle |
| `[DIAG-TIMING]` | T0/T1/T2/T3 turn timings |
| `[DIAG-FLAG]` | Internal flag transitions |

Or enable diagnostics at startup:
```ini
# eclipse.ini, before -vmargs:
-Dclaude.diag=true
```

### CLI process keeps crashing

Check the Error Log for `[Claude CLI stderr]` and `Process exited unexpectedly with code N`. The plugin shows a Reconnect banner. Common exit codes:
- `1` — API key issue
- `2` — invalid arguments
- `127` — CLI not found (check **Claude CLI Path** preference)
- `130` — Ctrl+C / interrupted

---

## 🏗 Build From Source

```bash
cd claude-eclipse-plugin

# Build classes
javac -source 11 -target 11 \
      -cp "C:/eclipse/plugins/*" \
      -d bin -sourcepath src \
      $(find src -name "*.java")

# Package the JAR
jar cfm build/com.anthropic.eclipse.claude_<version>.jar \
        META-INF/MANIFEST.MF -C bin . -C . plugin.xml icons
```

The repo's release builds package a P2 update site ZIP with `features/`, `plugins/`, `artifacts.xml`, `content.xml`, `p2.index`. See recent commit history for the exact format.

### Branches

- **`eclipse-4.38`** — Eclipse 2025-12 / Java 21 (default)
- **`eclipse-4.32`** — Eclipse 2024-06 / Java 17
- **`main`** — historical baseline

Cherry-pick fixes between them with `git cherry-pick`.

---

## 📚 Documentation

- **[docs/FIXES-SUMMARY.md](docs/FIXES-SUMMARY.md)** — Detailed write-up of every bug fix and feature added in 2026-04 / 2026-05, including IntelliJ-port notes for each one.
- **[docs/mcp-jdbc-server-setup.md](docs/mcp-jdbc-server-setup.md)** — Walkthrough for setting up an MCP-JDBC server (H2 / DB2 etc.) for Claude.

---

## 🗂 Architecture (high level)

```
ClaudeCliManager   ─ spawns + monitors `claude` CLI process per chat tab
       │
       ▼
NdjsonProtocolHandler  ─ reads stream-json from stdout, writes user prompts to stdin
       │ (ICliMessageListener)
       ▼
ConversationModel  ─ stateful: message history, streaming block, auto-retry,
                    silent-empty detection, hook-error surfacing
       │ (IConversationListener)
       ▼
ClaudeConversationView  ─ SWT UI: chat bubbles, input, attach chips,
                         active-file pin chip, diff viewers, permission prompts
```

Stream-json messages are fanned out by type:
- `system` → `SystemInit` (subtype=init) **or** `SystemNotification` (hooks/compact)
- `assistant` → `AssistantMessage` (full snapshot — usually skipped when stream events drive)
- `stream_event` → `message_start` / `content_block_*` / `message_stop`
- `result` → final cost/usage + auto-retry trigger if silent-empty
- `control_request` → `PermissionRequest` (tool approval prompts)

---

## 🤝 Contributing

This is a community project. PRs welcome — please target the appropriate `eclipse-X.Y` branch.

For bug reports, please include:
- Eclipse version & Java version
- Exported Error Log (with diagnostic logging enabled)
- Repro steps

---

## 📜 License

Provided as-is for personal/community use.

The plugin uses the official **Claude Code CLI** by Anthropic, which has its own terms of service.

---

## 🙏 Credits

Built on top of [Claude Code](https://docs.anthropic.com/en/docs/claude-code) by Anthropic.
Inspired by VS Code's Claude extension and Amazon Q Developer.
