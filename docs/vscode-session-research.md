# VS Code Claude Code Extension — Session Management Research

Research compiled from inspection of the official Anthropic VS Code extension and
the `@anthropic-ai/claude-agent-sdk` TypeScript definitions. Useful as a reference
for matching VS Code's behavior in the Eclipse plugin.

## Sources

| File | Kind | Notes |
|------|------|-------|
| `@anthropic-ai/claude-agent-sdk/sdk.d.ts` | TypeScript `.d.ts` | Real, authoritative |
| `@anthropic-ai/claude-code/sdk-tools.d.ts` | TypeScript `.d.ts` | Tool schemas |
| `extension.js` (from VSIX) | Minified JS | Reverse-engineered via grep |
| `webview/index.js` (from VSIX) | Minified JS | Reverse-engineered via grep |

The CLI itself (`@anthropic-ai/claude-code/cli.js`) is also minified — Anthropic do not publish the source.

## Key SDK APIs (from sdk.d.ts)

```typescript
// List all sessions in one project or across all projects.
// Reads the JSONL files in ~/.claude/projects/<project>/*.jsonl
export declare function listSessions(
    options?: ListSessionsOptions
): Promise<SDKSessionInfo[]>;

export declare type ListSessionsOptions = {
    dir?: string;             // project dir, or undefined for all
    limit?: number;
    offset?: number;
    includeWorktrees?: boolean;  // default true
    sessionStore?: SessionStore; // alpha, for external stores
};

export declare type SDKSessionInfo = {
    sessionId: string;        // UUID
    summary: string;          // custom title OR auto-generated OR first prompt
    lastModified: number;     // ms since epoch
    fileSize?: number;        // only for local JSONL
    customTitle?: string;     // set via /rename
    firstPrompt?: string;     // first meaningful user prompt
    gitBranch?: string;       // branch at end of session
    cwd?: string;
    tag?: string;
    createdAt?: number;       // from first entry's timestamp
};

// Metadata for a single session by ID
export declare function getSessionInfo(
    sessionId: string,
    options?: GetSessionInfoOptions
): Promise<SDKSessionInfo | undefined>;

// Full conversation messages (parsed, parent-chained)
export declare function getSessionMessages(
    sessionId: string,
    options?: GetSessionMessagesOptions
): Promise<SessionMessage[]>;
```

### Query options for resume

```typescript
// Passed to SDK.query() / SDK.startup():
interface QueryOptions {
    resume?: string;            // session id - activates --resume <id>
    resumeSessionAt?: string;   // resume up to specific message UUID
    sessionId?: string;         // custom session ID for NEW session (mutex with resume)
    persistSession?: boolean;   // default: true
    sessionStore?: SessionStore; // alpha — external mirror
    loadTimeoutMs?: number;     // for external session store loads
}
```

## VS Code extension flow (reverse-engineered)

### 1. Extension activation (extension.js)

```javascript
// When user opens Claude sidebar view:
registerWebviewViewProvider("claudeVSCodeSidebar", provider, {
    webviewOptions: { retainContextWhenHidden: true }
});

// Session list view - shows all sessions
registerWebviewViewProvider("claudeVSCodeSessionsList", {
    resolveWebviewView(view, ctx, token) {
        sidebar.resolveSessionListView(view, ctx, token);
    }
}, { webviewOptions: { retainContextWhenHidden: true } });

// Panel serializer - called when VS Code restores persisted panels on startup
registerWebviewPanelSerializer("claudeVSCodePanel", {
    async deserializeWebviewPanel(panel, state) {
        // NOTE: state parameter is IGNORED for session restore!
        // setupPanel is called with undefined initialSession
        setupPanel(panel, undefined, undefined, isFullEditor);
    }
});
```

Key point: **on VS Code restart, `deserializeWebviewPanel` does NOT pass a session ID**. The webview is responsible for figuring out which session to activate, via its own state.

### 2. setupPanel — creates the webview

```javascript
setupPanel(panel, initialSessionId, initialPrompt, isFullEditor) {
    panel.webview.html = getHtmlForWebview(
        webview,
        initialSessionId,     // becomes data-initial-session on #root
        initialPrompt,        // becomes data-initial-prompt on #root
        false,                // IS_SIDEBAR
        isFullEditor
    );

    // Create a session communication channel (q3 class)
    let channel = new q3(ctx, workspaceRoot, settings, panel.webview, ...);
    
    // Bi-directional message passing
    panel.webview.onDidReceiveMessage(msg => channel.fromClient(msg));
}
```

### 3. Webview bootstrap (webview/index.js, function `PR0`)

This is the heart of session restoration in VS Code:

```javascript
function PR0() {
    // 1. Acquire VS Code webview API (provides state persistence)
    let vscode = acquireVsCodeApi();
    let stateWrapper = new iq1(vscode);  // wraps setState/getState
    
    // 2. Read initial data passed from extension (via HTML attributes)
    let root = document.querySelector("#root");
    let initial = {
        initialPrompt: root.dataset.initialPrompt,
        initialSession: root.dataset.initialSession   // only set via command
    };
    
    // 3. Read previously saved session from webview state
    let savedSessionId;
    if (stateWrapper.value.sessionID) {
        // CRITICAL: 10-minute freshness window!
        if (stateWrapper.value.sessionUpdatedAt === undefined ||
            Date.now() - stateWrapper.value.sessionUpdatedAt < 600000) {
            savedSessionId = stateWrapper.value.sessionID;
        }
    }
    
    // 4. Load the session list from the extension, then decide
    sessionsManager.listSessions().then(() => {
        if (initial.initialSession) {
            // Extension explicitly asked to open a session
            sessionsManager.activateSessionFromServer(
                initial.initialSession, initial.initialPrompt
            );
        } else if (savedSessionId) {
            // Restore from webview state (within 10-min window)
            let session = sessionsManager.sessions.value
                .find(s => s.sessionId.value === savedSessionId);
            if (session) {
                sessionsManager.activeSession.value = session;
            } else {
                // session is gone - create new one
                sessionsManager.createSession({isExplicit: false});
            }
        } else if (initial.initialPrompt) {
            // New session with preset prompt
            sessionsManager.createSession({isExplicit: false})
                .then(s => {
                    if (s && initial.initialPrompt)
                        s.initialPrompt.value = initial.initialPrompt;
                });
        }
        // else: leave empty → user must pick from list
    });
    
    // 5. REACTIVE PERSISTENCE: save active session whenever it changes
    effect(() => {
        let openNewInTab = config.value?.openNewInTab;
        let active = sessionsManager.activeSession.value;
        if (!active) {
            stateWrapper.update({sessionID: undefined, sessionUpdatedAt: undefined});
            return;
        }
        stateWrapper.update({
            sessionID: active.sessionId.value,
            sessionUpdatedAt: openNewInTab ? undefined : Date.now()
        });
    });
}
```

### 4. State wrapper (iq1 class, webview/index.js)

```javascript
class iq1 {
    api;  // vscode webview API (acquireVsCodeApi result)
    constructor(vscodeApi) { this.api = vscodeApi; }
    update(partial) {
        let newState = {...this.api.getState() || {}, ...partial};
        this.api.setState(newState);  // VS Code persists automatically
    }
    get value() { return this.api.getState() || {}; }
}
```

`vscode.setState()` / `vscode.getState()` are automatically persisted by VS Code
across restarts — this is framework-level guarantee, no manual save/load needed.

### 5. listSessions request path

Webview:
```javascript
listSessions() {
    return this.sendRequest({type: "list_sessions_request"});
}
```

Extension handler (extension.js):
```javascript
async listSessions() {
    let sessions = await r$({          // r$ → WU4 → bU4 (filesystem scan)
        dir: this.cwd,
        includeWorktrees: false
    });
    // Merge with teleport metadata (remote/cloud sessions)
    let teleportMeta = await c1.readTeleportMetadata(this.cwd, sessions.map(s => s.sessionId));
    
    let result = sessions.map(s => {
        let tp = teleportMeta.get(s.sessionId);
        return {
            id: s.sessionId,
            lastModified: s.lastModified,
            fileSize: s.fileSize,
            summary: s.summary,
            gitBranch: s.gitBranch,
            worktree: normalizeWorktree(s.cwd),
            isCurrentWorkspace: true,
            ...tp
        };
    });
    
    // Filter hidden sessions (user-hidden via UI)
    let hidden = new Set(this.settings.getHiddenSessionIds());
    return {
        type: "list_sessions_response",
        sessions: hidden.size > 0 ? result.filter(s => !hidden.has(s.id)) : result
    };
}
```

### 6. File scanning (extension.js `bU4`, `EU4`, `Kx`)

```javascript
// bU4: handles git worktrees
async function bU4(dir, includeWorktrees, paginated) {
    let resolved = await Gl(dir);
    let worktrees = includeWorktrees ? await Bl(resolved) : [];
    
    if (worktrees.length <= 1) {
        // No worktrees — scan all project dirs whose prefix matches our dir
        let results = [];
        for (let projDir of await Hx(resolved)) {
            results.push(...await Kx(projDir, paginated, resolved));
        }
        return results;
    }
    // ... worktree-aware path ...
}

// Kx: reads directory entries from a project dir
async function Kx(projectDir, paginated, rootDir) {
    let entries = await fs.readdir(projectDir);
    return (await Promise.all(entries.map(async (name) => {
        if (!name.endsWith(".jsonl")) return null;
        let sessionId = parseUUID(name.slice(0, -6));
        if (!sessionId) return null;
        let filePath = path.join(projectDir, name);
        if (!paginated) {
            return {sessionId, filePath, mtime: 0, projectPath: rootDir};
        }
        let stat = await fs.stat(filePath);
        return {sessionId, filePath, mtime: stat.mtime.getTime(), projectPath: rootDir};
    }))).filter(x => x !== null);
}

// fx: returns ~/.claude/projects path
function fx() {
    return path.join(claudeHome(), "projects");
}
```

### 7. Active session reactive persistence

The webview uses a `_4(() => {...})` effect. Every time the active session
changes, two keys are written to `vscode.setState`:

- `sessionID`: string (UUID of current session)
- `sessionUpdatedAt`: number (Date.now() at time of change)

On startup, these are read back; the 10-minute freshness check prevents
stale restores if the user didn't touch it for a long time.

### 8. Path to ~/.claude

```javascript
// Python pseudo-code
function claudeHome() {
    return process.env.CLAUDE_CONFIG_DIR || path.join(os.homedir(), ".claude");
}
```

## Key architectural takeaways

1. **Single source of truth**: The JSONL files in `~/.claude/projects/<project>/<session>.jsonl`
   are the authoritative record. Neither extension nor webview keeps its own metadata store.

2. **SDK owns filesystem access**: Extension never reads JSONL directly for metadata —
   it calls SDK functions (`listSessions`, `getSessionInfo`, `getSessionMessages`).
   For content replay, the CLI itself replays JSONL via stream-json when invoked with
   `--resume <id>`.

3. **Two-layer restore**:
   - **Fast path**: webview state (`sessionID` + `sessionUpdatedAt` with 10-min TTL)
     → activate the previous session immediately.
   - **Slow path**: read session list, show user or pick based on input.

4. **Reactive save**: every change to active session is written to webview state
   automatically (no explicit save points needed).

5. **10-minute window**: prevents awkward auto-restore if user hasn't interacted
   for a long time; they see a fresh list instead.

6. **`retainContextWhenHidden: true`**: keeps webview in memory when hidden
   (e.g., sidebar collapsed), separate from `setState` persistence across restarts.

## How Eclipse plugin currently differs (problems)

| Area | VS Code | Eclipse |
|---|---|---|
| Metadata source | JSONL only | Separate `claude-sessions/*.json` + JSONL (out of sync) |
| Restore mechanism | `vscode.setState` (framework-level, reliable) | Eclipse memento (CompatibilityView — unreliable) |
| Freshness check | 10 min window | None — always tries to restore |
| Tab title on resume | Set from session summary | Not updated (bug) |
| Auto-start CLI before memento check | No | Yes (race; creates extra JSONL) |
| Empty session files | Not created | 29 of 56 metadata files have messageCount=0 |
| Sessions visible to user | All JSONL (from filesystem) | Only plugin's metadata (56 of 192 real sessions) |

## Recommended Eclipse implementation strategy

1. **Remove `SessionStore.java`** — replace with direct JSONL scan under
   `~/.claude/projects/<projectKey>/*.jsonl` (mimic `listSessions`).
   Project key computation: `/` and `\` → `-` (same as CLI).

2. **Memento fields**:
   - `claudeSessionId` (string UUID)
   - `sessionUpdatedAt` (long, ms since epoch)
   - Drop `claudeTabTitle` (regenerate from JSONL summary instead).

3. **Auto-restore logic in `createPartControl`**:
   ```java
   if (memento has sessionId && now - updatedAt < 10_minutes) {
       resumeSession(sessionId);  // with full JSONL replay
   } else {
       // show sessions list OR create new
   }
   ```

4. **Remove the current `autoStartCli()` before memento check** — it creates
   orphan JSONL files.

5. **Extract summary from JSONL** — scan for first `"type":"user"` line,
   take `message.content` up to ~60 chars.

6. **Update tab title on resume** — after loading history, take first user
   message and set as part name.

7. **Save memento on every send/receive** — not just on dispose, so that
   closing Eclipse abruptly doesn't lose state.
