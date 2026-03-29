# Eclipse Claude Plugin - Project Memory

## Project Overview
Eclipse plugin that replicates the VS Code Claude Code extension experience.
Location: `/Users/zvirot/eclipse-workspaceplugin/com.anthropic.eclipse.claude/`

## Build
```
MAVEN_OPTS="-Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.entityExpansionLimit=0 -DentityExpansionLimit=0 -Djdk.xml.totalEntitySizeLimit=0" mvn compile
```
Build system: Maven + Tycho (eclipse-plugin packaging).

## Unit Tests
Test project: `/Users/zvirot/eclipse-workspaceplugin/com.anthropic.eclipse.claude.tests/`
Run: `cd ...tests && mvn test` — 49 tests covering JsonParser, DiffExtractor, DiffResult.

## Completed Implementation (Production-Ready Plan)

### Phase 1 - Bug Fixes (Done)
- Thread safety in ConversationModel
- SWT Color leaks in DiffViewerDialog
- escapeJson consolidated to JsonParser.escapeJsonString()
- HttpURLConnection disconnect fixed
- System.err → ILog (Activator.logError)

### Phase 2 - Critical Features (Done)
- Status bar: ClaudeStatusBarContribution (toolbar:org.eclipse.ui.trim.status)
- Auto-refresh workspace after CLI edits
- CheckpointManager (diff/CheckpointManager.java) - snapshots files before edits
- Auto-save dirty editors before tool execution
- ClaudeSettingsReader reads ~/.claude/settings.json
- Focus toggle keybinding: M1+ESCAPE → FocusToggleHandler

### Phase 3 - UX (Done)
- Enter-to-Send preference (PreferenceConstants.ENTER_TO_SEND)
- Selection indicator label shows "N lines selected" from active editor
- @-mention with line ranges (dialog for "#10-20", stored in AttachmentManager.fileRanges)
- Inline diff via Eclipse Compare: ClaudeCompareInput.java uses org.eclipse.compare bundle

### Phase 4 - Polish (Done)
- Secure API key: ISecurePreferences via SecureApiKeyStore.java + SecureStringFieldEditor.java
- Error handling: exit-code diagnosis in diagnoseCrash(), Reconnect banner, toast notifications
- Unit tests: 49 tests in separate Maven module
- Decomposed ClaudeConversationView: extracted AttachmentManager.java (~380 lines)

### Phase 5 - Additional Fixes (Done - current session)

#### control_response ZodError Fix
- CLI requires BOTH `behavior:"allow"` AND `updatedInput:<toolInput>` in allow response
- `IConversationListener.onPermissionRequested` + `ConversationModel` + `ClaudeConversationView` all updated to pass `toolInput`
- `CliMessage.createControlResponse(requestId, allow, toolInput)` now sends correct format

#### PermissionBanner Improvements
- `startPulseAnimation()` — flashes orange 3 times to draw attention
- `scrollToBottom()` uses double-asyncExec for reliable scroll after banner appears

#### Stuck Session Detection (streaming timeout)
- `touchStreamActivity()` — called on every stream event, starts 45s timer
- `checkStreamingTimeout()` — fires if no activity for 45s AND no tool actively running (avoids false timeout during long Maven builds)
- `cancelStreamingTimeout()` — called on result, error, CLI crash, Stop button
- `ConversationModel.hasRunningToolCalls()` — used to pause timeout during tool execution
- `ConversationModel.markActiveToolCallsFailed(reason)` — marks RUNNING tool calls as FAILED on crash/timeout
- Called from: `onStateChanged(ERROR)`, `onStateChanged(STOPPED)`, `onConnectionError`

#### handleEditToolCompleted Bug Fix (critical)
- **Bug**: was reading already-modified file from disk and trying to re-apply the edit → garbage
- **Fix**: uses `CheckpointManager.getSnapshots().get(filePath)` for original content; reads disk for modified
- `EditDecisionManager.recordCompletedEdit()` new method — stores diff without stale annotations
- UI: replaced misleading PermissionBanner ("Claude wants to edit") with "✏ Edited: file.java" widget + [View Diff] [Compare] [Revert] buttons

#### MarkdownRenderer Fixes
- **Code block double-render bug**: `render()` was rendering code blocks inline AND `MessageComposite.finalizeContent()` also created `CodeBlockComposite` widgets → code appeared twice. Fix: `render()` now skips code block content entirely (only adds a blank separator line)
- **Theme-aware colors**: replaced hardcoded static RGB colors with per-render `ThemeManager`-based colors (blue accent for headers, amber for inline code). Colors disposed via `widget.addDisposeListener`

### Phase 6 - Running... Bug, Diff, Text Wrapping, Syntax Highlighting (Done - session 2)

#### Running... Bug (tool call stuck on "Running..." after completion)
- **Root cause**: `volatile` missing on `ToolCallSegment.status` + `onToolCallInputDelta` asyncExec items overwriting COMPLETED status with stale RUNNING
- **Fix**: `volatile` on `status` and `output` fields, early-return guard in `onToolCallInputDelta`, direct `toolCallWidgetById` map (ConcurrentHashMap<String, ToolCallComposite>), retry mechanism in `updateToolCallWidget`, and `syncAllToolCallStatuses()` sweep in `onAssistantMessageCompleted`
- `ToolCallComposite.setStatus()` runs immediately when on UI thread (no nested asyncExec)

#### Snapshot Timing for Diff (empty Original side)
- **Root cause**: `onToolCallStarted` has empty input (streams later); `onPermissionRequested` doesn't fire in `acceptEdits` mode
- **Fix**: New `onToolCallInputComplete` event fires from `handleContentBlockStop` when tool input is fully streamed but BEFORE tool executes. Snapshot taken here.

#### Text Wrapping
- `computeSize` now uses `scrolledMessages.getClientArea().width` as width hint (was SWT.DEFAULT)
- `ControlListener` on scrolledMessages re-layouts on resize
- Removed `heightHint` from StreamingTextWidget (was blocking proper wrap computation)

#### Duplicate Text Fix
- **Root cause**: `finalizeContent()` created new widget with ALL text when `currentTextWidget` was null after tool call
- **Fix**: `hasStreamedText` flag skips re-population if text was already rendered during streaming

#### Syntax Highlighting (NEW)
- `SyntaxHighlighter.java` — Java via JDT IScanner (optional dep) + Regex fallback for Python, JS/TS, Go, Rust, C/C++, Shell
- `ThemeManager` — VS Code Dark+ inspired palette: syntaxKeyword (blue), syntaxString (orange), syntaxComment (green), syntaxType (teal), syntaxNumber (light green), syntaxAnnotation (yellow)
- `CodeBlockComposite` — calls `SyntaxHighlighter.highlight()` and applies StyleRanges
- `MANIFEST.MF` — `org.eclipse.jdt.core;resolution:=optional`

#### Plugin Deployment
- Deploy: copy JAR to `dropins/` AND `plugins/` + update `bundles.info` + restart with `-clean`
- Removed duplicate [Compare] button (was same as [View Diff])

## Key Architecture
- ClaudeConversationView (~2214 lines) - main view (ViewPart)
- AttachmentManager - file/image attachment state and UI chips
- ClaudeCliManager - process lifecycle, health monitor
- NdjsonProtocolHandler - NDJSON stream protocol
- ConversationModel - message state machine
- ClaudeStatusBarContribution - status bar widget
- SecureApiKeyStore - ISecurePreferences wrapper

## IntelliJ Plugin (בתכנון)
- [project_intellij_plan.md](project_intellij_plan.md) — תכנית מלאה לפורט ל-IntelliJ: ארכיטקטורה, מיפוי API, קבצים לשימוש חוזר, שלבים

## Important Files
- `META-INF/MANIFEST.MF` - OSGi deps including org.eclipse.compare, org.eclipse.equinox.security
- `plugin.xml` - extensions: view, commands, handlers, keybindings, status bar, context menus
- `src/.../preferences/PreferenceConstants.java` - all preference keys
