package com.anthropic.eclipse.claude.views;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.part.ViewPart;

import com.anthropic.eclipse.claude.Activator;
import com.anthropic.eclipse.claude.cli.ClaudeCliManager;
import com.anthropic.eclipse.claude.cli.CliProcessConfig;
import com.anthropic.eclipse.claude.cli.ICliStateListener;
import com.anthropic.eclipse.claude.diff.EditDecisionManager;
import com.anthropic.eclipse.claude.model.ConversationModel;
import com.anthropic.eclipse.claude.model.IConversationListener;
import com.anthropic.eclipse.claude.model.MessageBlock;
import com.anthropic.eclipse.claude.model.SessionInfo;
import com.anthropic.eclipse.claude.model.UsageInfo;
import com.anthropic.eclipse.claude.preferences.PreferenceConstants;
import com.anthropic.eclipse.claude.preferences.SecureApiKeyStore;
import com.anthropic.eclipse.claude.session.ClaudeSessionManager;
import com.anthropic.eclipse.claude.session.JsonlHistoryLoader;
import com.anthropic.eclipse.claude.util.JsonParser;
import com.anthropic.eclipse.claude.views.webview.JsonBuilder;
import com.anthropic.eclipse.claude.views.webview.WebviewBridge;

/**
 * Webview-based Claude conversation view (V2).
 *
 * <p>Hosts a single {@link Browser} widget that loads HTML/CSS/JS bundled
 * inside this plugin (under {@code webview/}). All chat rendering happens
 * in the embedded Chromium (Edge WebView2 on Windows 10+); the Java side
 * only pushes events to and receives commands from the page via
 * {@link WebviewBridge}.
 *
 * <p>Compared with the original {@link ClaudeConversationView}, this view
 * has NO sliding-window apparatus, NO MessageComposite, NO StreamingTextWidget,
 * and no SWT/Win32 32767px child-Y limit — the browser handles arbitrary
 * scroll content natively.
 *
 * <p>The model layer ({@link ConversationModel}, {@link IConversationListener})
 * is shared verbatim with the legacy view. Only the rendering layer changed.
 */
public class ClaudeConversationViewV2 extends ViewPart implements IConversationListener, ICliStateListener {

    public static final String ID = "com.anthropic.eclipse.claude.views.ClaudeConversationViewV2";

    /** Memento keys — match V1 so the same workspace can roundtrip between V1 and V2. */
    private static final String MEMENTO_SESSION_ID = "claudeSessionId";
    private static final String MEMENTO_TAB_TITLE  = "claudeTabTitle";

    private Browser browser;
    private WebviewBridge bridge;
    private ConversationModel model;
    private ClaudeCliManager cliManager;
    private EditDecisionManager editDecisionManager;
    private ClaudeSessionManager sessionManager;

    private volatile boolean webviewReady = false;
    private Path webviewTempDir;

    /** Memento saved by Eclipse last time this view was open. Read on
     *  createPartControl to auto-resume the previously active session. */
    private org.eclipse.ui.IMemento savedMemento;

    /** Sticky session id — preserved across CLI restarts so a failed
     *  CLI resume on the next launch doesn't lose the view's session. */
    private volatile String stickySessionId;

    /** Per-view mode state, mirrors the legacy view's `currentMode`. */
    private String currentMode = "default";
    private String currentEffort = "medium";

    /**
     * Whether the view's tab title has been set from a user message. Like
     * V1's {@code partNameSet} — flips to true once and prevents subsequent
     * user messages from overwriting the title. Reset on new_session /
     * clear / resume.
     */
    private boolean partNameSet = false;

    /** Pending permission requests: requestId → original toolInput. The
     *  CLI's control_response requires the same input echoed back as
     *  {@code updatedInput} when allowing the tool. Populated in
     *  {@link #onPermissionRequested} and consumed by
     *  {@link #handlePermissionResponse}. */
    private final java.util.concurrent.ConcurrentHashMap<String, Object> pendingToolInputs =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Listener for editor activation/closing — fires {@link #pushActiveFileToWebview()}. */
    private org.eclipse.ui.IPartListener2 activeFilePartListener;
    /** Last active file path pushed to the webview — used to dedup. */
    private volatile String lastActiveFilePath;

    @Override
    public void init(org.eclipse.ui.IViewSite site, org.eclipse.ui.IMemento memento)
            throws org.eclipse.ui.PartInitException {
        super.init(site, memento);
        this.savedMemento = memento;
    }

    @Override
    public void saveState(org.eclipse.ui.IMemento memento) {
        super.saveState(memento);
        if (memento == null) return;
        SessionInfo info = (model != null) ? model.getSessionInfo() : null;
        String sid = (info != null) ? info.getSessionId() : null;
        if (sid == null || sid.isEmpty()) sid = stickySessionId;
        if (sid != null && !sid.isEmpty()) {
            memento.putString(MEMENTO_SESSION_ID, sid);
        }
        String title = getPartName();
        if (title != null && !title.equals("Claude Code")) {
            memento.putString(MEMENTO_TAB_TITLE, title);
        }
    }

    @Override
    public void createPartControl(Composite parent) {
        editDecisionManager = Activator.getDefault().getEditDecisionManager();
        sessionManager = Activator.getDefault().getSessionManager();
        cliManager = Activator.getDefault().createCliManager();

        model = new ConversationModel();
        model.addListener(this);
        cliManager.addMessageListener(model);
        cliManager.addStateListener(this);

        Activator.getDefault().setConversationModel(model);
        Activator.getDefault().setActiveCliManager(cliManager);

        initModeFromPreferences();

        parent.setLayout(new FillLayout());

        try {
            // Use Edge WebView2 on Windows 10+ (since SWT 3.116 / Eclipse 4.19).
            // Falls back to IE if WebView2 isn't installed — chat UI will not
            // render correctly in that case, surfaced as a visible error.
            browser = new Browser(parent, SWT.EDGE);
        } catch (SWTError e) {
            Activator.logWarning("[Webview] SWT.EDGE not available: " + e.getMessage()
                    + " — falling back to default Browser engine.");
            browser = new Browser(parent, SWT.NONE);
        }

        bridge = new WebviewBridge(browser);
        bridge.setMessageHandler(this::handleWebviewMessage);

        browser.addProgressListener(new ProgressAdapter() {
            @Override
            public void completed(ProgressEvent event) {
                Activator.logInfo("[Webview] ProgressListener.completed fired (page load done)");
                // Re-register the BrowserFunction after the page loaded —
                // SWT.EDGE on Windows sometimes drops pre-navigation
                // BrowserFunctions, leaving window.__sendToJava undefined
                // and silently queuing JS->Java messages forever. Doing
                // this on EVERY page-load-complete is safe even if the
                // function is still alive.
                bridge.rebindBrowserFunction();

                if (webviewReady) return;
                webviewReady = true;
                bridge.notifyBridgeReady();
                // The webview also sends 'webview_ready' explicitly after its
                // bridge is set up — pushInitialState is idempotent so either
                // path is fine.
                pushInitialState();
            }
        });

        loadWebview();
        installActiveEditorListener();
    }

    // ====================== Active file (editor) tracking ======================

    /** Install a workbench part listener so we push active_file_changed
     *  to the webview every time the user switches editors. */
    private void installActiveEditorListener() {
        if (activeFilePartListener != null) return;
        activeFilePartListener = new org.eclipse.ui.IPartListener2() {
            @Override public void partActivated(org.eclipse.ui.IWorkbenchPartReference r) { pushActiveFileToWebview(); }
            @Override public void partBroughtToTop(org.eclipse.ui.IWorkbenchPartReference r) { pushActiveFileToWebview(); }
            @Override public void partClosed(org.eclipse.ui.IWorkbenchPartReference r) { pushActiveFileToWebview(); }
            @Override public void partOpened(org.eclipse.ui.IWorkbenchPartReference r) { pushActiveFileToWebview(); }
            @Override public void partVisible(org.eclipse.ui.IWorkbenchPartReference r) { pushActiveFileToWebview(); }
            @Override public void partHidden(org.eclipse.ui.IWorkbenchPartReference r) {}
            @Override public void partDeactivated(org.eclipse.ui.IWorkbenchPartReference r) {}
            @Override public void partInputChanged(org.eclipse.ui.IWorkbenchPartReference r) { pushActiveFileToWebview(); }
        };
        try {
            getSite().getPage().addPartListener(activeFilePartListener);
        } catch (Exception ignored) {}
    }

    /** Find the currently-active workbench editor's file. Returns null when
     *  no editor is focused or the active editor isn't backed by an IFile. */
    private org.eclipse.core.resources.IFile getActiveFileFromEditor() {
        try {
            org.eclipse.ui.IWorkbenchPage page = getSite().getPage();
            if (page == null) return null;
            org.eclipse.ui.IEditorPart ed = page.getActiveEditor();
            if (ed == null) return null;
            return ed.getEditorInput().getAdapter(org.eclipse.core.resources.IFile.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** Push the currently-active editor file to the webview. The JS chip
     *  shows when path is non-null AND the user hasn't dismissed it for
     *  that path AND the "attach active file" preference is on. */
    private void pushActiveFileToWebview() {
        if (bridge == null || !webviewReady) return;
        org.eclipse.core.resources.IFile file = getActiveFileFromEditor();
        String path = (file != null && file.getLocation() != null)
                ? file.getLocation().toOSString() : null;
        String name = (file != null) ? file.getName() : null;
        // Dedup: skip if path hasn't changed.
        if (path == null ? lastActiveFilePath == null : path.equals(lastActiveFilePath)) return;
        lastActiveFilePath = path;
        StringBuilder json = new StringBuilder("{");
        json.append("\"path\":").append(path == null ? "null" : JsonBuilder.jsonString(path));
        json.append(",\"name\":").append(name == null ? "null" : JsonBuilder.jsonString(name));
        json.append("}");
        bridge.sendToWebview("active_file_changed", json.toString());
    }

    /** Build the {@code <file path="…">…</file>} block to prepend to the
     *  user message when the active-file chip is enabled. Returns null
     *  when the preference is off, no editor open, or the file can't be
     *  read. Mirrors V1's buildActiveFilePinContext with a 200ms bounded
     *  read so a slow network drive doesn't stall the UI. */
    private String buildActiveFilePinContext() {
        try {
            boolean enabled = Activator.getDefault().getPreferenceStore()
                    .getBoolean(PreferenceConstants.ATTACH_ACTIVE_FILE);
            if (!enabled) return null;
            org.eclipse.core.resources.IFile file = getActiveFileFromEditor();
            if (file == null || file.getLocation() == null) return null;
            String path = file.getLocation().toOSString();
            String content = readFileBounded(java.nio.file.Paths.get(path), 200);
            if (content == null) return null;
            // Cap large files
            int MAX = 64 * 1024;
            String truncatedNote = "";
            if (content.length() > MAX) {
                content = content.substring(0, MAX);
                truncatedNote = "\n... (truncated to 64KB)";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("<file path=\"").append(path).append("\" pinned=\"active-editor\">\n");
            sb.append(content).append(truncatedNote);
            if (!content.endsWith("\n")) sb.append("\n");
            sb.append("</file>\n\n");
            return sb.toString();
        } catch (Exception e) {
            Activator.logWarning("[Webview/ActiveFile] buildContext failed: " + e.getMessage());
            return null;
        }
    }

    private static String readFileBounded(java.nio.file.Path nio, long timeoutMs) {
        java.util.concurrent.CompletableFuture<String> fut = java.util.concurrent.CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return new String(java.nio.file.Files.readAllBytes(nio),
                                java.nio.charset.StandardCharsets.UTF_8);
                    } catch (Exception e) { return null; }
                });
        try {
            return fut.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    // ====================== Webview bootstrap ======================

    private void loadWebview() {
        try {
            Path tempDir = extractWebviewResources();
            if (tempDir == null) {
                browser.setText(errorHtml("Failed to extract webview resources."));
                return;
            }
            this.webviewTempDir = tempDir;
            Path htmlFile = tempDir.resolve("index.html");
            browser.setUrl(htmlFile.toUri().toString());
            Activator.logInfo("[Webview] Loaded from temp dir: " + tempDir);
        } catch (Exception e) {
            Activator.logWarning("[Webview] Failed to load: " + e.getMessage());
            if (browser != null && !browser.isDisposed()) {
                browser.setText(errorHtml("Could not load chat UI: " + e.getMessage()));
            }
        }
    }

    /**
     * Extracts webview resources from the bundle JAR to a temp directory so
     * SWT.Browser can resolve relative CSS/JS paths.
     */
    private Path extractWebviewResources() throws Exception {
        String[] resources = {
            "webview/index.html",
            "webview/css/chat.css",
            "webview/js/bridge.js",
            "webview/js/app.js",
            "webview/js/highlight.js"
        };

        Path tempDir = Files.createTempDirectory("claude-eclipse-webview-");
        Files.createDirectories(tempDir.resolve("css"));
        Files.createDirectories(tempDir.resolve("js"));

        ClassLoader cl = getClass().getClassLoader();
        for (String res : resources) {
            InputStream is = cl.getResourceAsStream(res);
            if (is == null) {
                // Fall back to the bundle if the classloader didn't have it.
                org.osgi.framework.Bundle bundle = Activator.getDefault().getBundle();
                java.net.URL url = bundle.getEntry(res);
                if (url != null) {
                    is = url.openStream();
                }
            }
            if (is == null) {
                Activator.logWarning("[Webview] Missing resource: " + res);
                continue;
            }
            String rel = res.substring("webview/".length());
            Path target = tempDir.resolve(rel);
            try (InputStream in = is) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // Pre-stamp index.html with the detected theme class on <body> so
        // the CSS picks the right palette FROM THE FIRST PAINT instead of
        // flashing dark → light. Without this, the page loads with the
        // CSS default (dark), then the set_theme event from Java arrives
        // after the page is interactive and JS adds body.light — which
        // is what produced the visible flicker when opening a new tab.
        try {
            Path htmlPath = tempDir.resolve("index.html");
            String html = new String(Files.readAllBytes(htmlPath), java.nio.charset.StandardCharsets.UTF_8);
            if (!isDarkTheme()) {
                // Inject light class on the <body> tag. Only when light —
                // dark is the CSS default so no edit needed for it.
                html = html.replaceFirst("<body>", "<body class=\"light\">");
                Files.write(htmlPath, html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Activator.logWarning("[Webview] could not pre-stamp theme into index.html: " + e.getMessage());
        }

        // Best-effort cleanup on JVM exit.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.walk(tempDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            } catch (Exception ignored) {}
        }, "claude-webview-cleanup"));

        return tempDir;
    }

    private String errorHtml(String msg) {
        return "<html><body style='background:#1e1e1e;color:#ddd;font-family:sans-serif;padding:40px;text-align:center'>"
            + "<h2>Claude AI Webview Error</h2><p>" + escapeHtml(msg) + "</p></body></html>";
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ====================== Initial state push ======================

    private void pushInitialState() {
        if (!webviewReady) return;

        // Theme
        boolean isDark = isDarkTheme();
        bridge.sendToWebview("set_theme", "{\"theme\":\"" + (isDark ? "dark" : "light") + "\"}");

        // CLI state
        bridge.sendToWebview("cli_state_changed",
            "{\"state\":\"" + (cliManager.isRunning() ? "connected" : "disconnected") + "\"}");

        // Session info if available
        SessionInfo info = model.getSessionInfo();
        if (info != null) {
            bridge.sendToWebview("session_initialized", JsonBuilder.buildSessionInfoJson(info));
        }

        // Mode + effort
        bridge.sendToWebview("mode_changed", "{\"mode\":" + JsonBuilder.jsonString(currentMode) + "}");
        bridge.sendToWebview("effort_changed", "{\"effort\":" + JsonBuilder.jsonString(currentEffort) + "}");

        // Per-bubble timestamp visibility — defaults to true. The webview
        // hides timestamps by adding body.hide-timestamps when visible=false.
        boolean showTs = true;
        try {
            showTs = Activator.getDefault().getPreferenceStore()
                    .getBoolean(PreferenceConstants.SHOW_MESSAGE_TIMESTAMPS);
        } catch (Exception ignored) {}
        bridge.sendToWebview("set_show_timestamps", "{\"visible\":" + showTs + "}");

        // Active-file pin: the chip in the input area lights up when an
        // editor is open and the preference is on. JS keeps these two
        // pieces of state separate (file identity vs. enabled toggle).
        boolean attachActive = true;
        try {
            attachActive = Activator.getDefault().getPreferenceStore()
                    .getBoolean(PreferenceConstants.ATTACH_ACTIVE_FILE);
        } catch (Exception ignored) {}
        bridge.sendToWebview("attach_active_file_changed", "{\"enabled\":" + attachActive + "}");
        // Push the currently-active editor file (or null) — same flow the
        // IPartListener2 uses on every editor change.
        // Reset dedup so the initial push always fires.
        lastActiveFilePath = "<init-sentinel>";
        pushActiveFileToWebview();

        // Replay existing messages (in case the model already had messages
        // when the view was opened — e.g. from a session resume).
        for (MessageBlock block : model.getMessages()) {
            if (block.getRole() == MessageBlock.Role.USER) {
                bridge.sendToWebview("user_message_added", JsonBuilder.buildMessageBlockJson(block));
            } else if (block.getRole() == MessageBlock.Role.ASSISTANT) {
                bridge.sendToWebview("assistant_message_started", JsonBuilder.buildMessageBlockJson(block));
                bridge.sendToWebview("assistant_message_completed", JsonBuilder.buildMessageBlockJson(block));
            }
        }

        // Cumulative usage
        UsageInfo usage = model.getCumulativeUsage();
        if (usage.getTotalTokens() > 0) {
            bridge.sendToWebview("result_received", JsonBuilder.buildUsageJson(usage));
        }

        // Auto-resume from memento if Eclipse restored one for this view.
        // Same semantics as V1: each view owns its own state; brand-new
        // views have null memento and start fresh. If a memento has a
        // claudeSessionId, resume that session — fetches the JSONL
        // transcript, replays it through the bridge, and starts the CLI
        // with --resume so the next message preserves memory.
        String resumeId = null;
        String savedTitle = null;
        if (savedMemento != null) {
            resumeId = savedMemento.getString(MEMENTO_SESSION_ID);
            savedTitle = savedMemento.getString(MEMENTO_TAB_TITLE);
            savedMemento = null; // consumed
        }
        Activator.logInfo("[Webview] memento resumeId="
                + (resumeId == null ? "<null>" : resumeId)
                + " title=" + (savedTitle == null ? "<null>" : savedTitle));

        if (resumeId != null && !resumeId.isEmpty()) {
            // Restore the tab title immediately so we don't flash "Claude Code"
            if (savedTitle != null && !savedTitle.isEmpty()) {
                partNameSet = true;
                final String finalTitle = savedTitle;
                browser.getDisplay().asyncExec(() -> {
                    try { setPartName(finalTitle); } catch (Exception ignored) {}
                });
            }
            // Resume the session — this starts the CLI with --resume and
            // replays the JSONL transcript into the webview. We delegate to
            // resumeSession() which already handles everything we need.
            final String finalResumeId = resumeId;
            browser.getDisplay().asyncExec(() -> {
                try { resumeSession(finalResumeId); } catch (Exception e) {
                    Activator.logError("[Webview] auto-resume failed: " + e.getMessage(), e);
                    // Fall back to a fresh CLI so the user can still send messages.
                    if (!cliManager.isRunning()) {
                        bridge.sendToWebview("cli_state_changed", "{\"state\":\"connecting\"}");
                        autoStartCli();
                    }
                }
            });
            return;
        }

        // No memento: brand-new view. Auto-start CLI like the legacy view.
        if (!cliManager.isRunning()) {
            bridge.sendToWebview("cli_state_changed", "{\"state\":\"connecting\"}");
            autoStartCli();
        }
    }

    // ====================== JS -> Java message dispatch ======================

    private void handleWebviewMessage(String type, String payload) {
        if (type == null) return;
        Activator.logInfo("[Webview] msg from JS: " + type);
        switch (type) {
            case "webview_ready":
                webviewReady = true;
                pushInitialState();
                break;
            case "send_message":
                handleSendMessage(payload);
                break;
            case "stop_generation":
                if (cliManager.isRunning()) cliManager.stop();
                break;
            case "new_session":
                handleNewSession();
                break;
            case "clear_conversation":
                model.clear();
                break;
            case "reconnect":
                autoStartCli();
                break;
            case "accept_permission":
            case "reject_permission":
            case "always_allow_permission":
                handlePermissionResponse(type, payload);
                break;
            case "change_mode":
                handleChangeMode(payload);
                break;
            case "change_effort":
                handleChangeEffort(payload);
                break;
            case "open_dialog":
                handleOpenDialog(payload);
                break;
            case "apply_to_editor":
                handleApplyToEditor(payload);
                break;
            case "insert_at_cursor":
                handleInsertAtCursor(payload);
                break;
            case "new_tab":
                handleNewTab();
                break;
            case "close_tab":
                handleCloseTab();
                break;
            case "rename_tab":
                handleRenameTab(payload);
                break;
            case "set_attach_active_file":
                handleSetAttachActiveFile(payload);
                break;
            case "view_diff":
                handleViewDiff(payload);
                break;
            case "accept_edit":
                handleAcceptEdit(payload);
                break;
            case "reject_edit":
                handleRejectEdit(payload);
                break;
            case "tools_aborted_on_turn_end":
                // JS reports tools it had to mark Aborted because the turn
                // ended without a matching tool_call_completed. Log them so
                // the workspace .log is a self-contained record of the
                // event (devs don't need to read the JSONL or screenshot
                // to know what got cut off).
                try {
                    Map<String, Object> abData = JsonParser.parseObject(payload);
                    Object idsObj = (abData != null) ? abData.get("toolIds") : null;
                    Activator.logWarning("[Webview/Tool] ABORTED on turn end -- "
                        + "the CLI stream ended (likely Bedrock rate_limit or proxy "
                        + "cutoff per the preceding rate_limit_event log) before "
                        + "these tool calls completed: " + WebviewBridge.toJson(idsObj));
                } catch (Exception e) {
                    Activator.logWarning("[Webview/Tool] tools_aborted_on_turn_end "
                        + "log failed: " + e.getMessage());
                }
                break;
            default:
                // Unknown — log for now. v1 covers send/stop/clear/permission;
                // file mentions, slash commands, attachments, fork, edit_staged
                // are not yet wired through.
                Activator.logInfo("[Webview] (v1 unhandled) " + type + " payload=" + truncate(payload));
        }
    }

    private void handleSendMessage(String payload) {
        try {
            Map<String, Object> data = JsonParser.parseObject(payload);
            String message = JsonParser.getString(data, "message");
            if (message == null || message.isEmpty()) return;

            // The webview bubble shows ONLY what the user typed.
            model.addUserMessage(message);

            // If the active-file chip is enabled (JS sets includeActiveFile
            // based on chip visibility), prepend a <file path="…">…</file>
            // context block to what we send to the CLI — matches V1's
            // buildActiveFilePinContext behaviour. The chip in app.js
            // already filtered out dismissed paths and disabled state.
            boolean includeActiveFile = false;
            Object iaf = (data != null) ? data.get("includeActiveFile") : null;
            if (iaf instanceof Boolean) includeActiveFile = (Boolean) iaf;
            String outgoing = message;
            if (includeActiveFile) {
                String ctx = buildActiveFilePinContext();
                if (ctx != null && !ctx.isEmpty()) {
                    outgoing = ctx + message;
                }
            }

            if (!cliManager.isRunning()) {
                autoStartCli();
            }
            if (cliManager.isRunning()) {
                cliManager.sendMessage(outgoing);
            } else {
                bridge.sendToWebview("error",
                    "{\"message\":" + JsonBuilder.jsonString("Claude CLI is not running.") + "}");
            }
        } catch (Exception e) {
            Activator.logWarning("[Webview] send_message failed: " + e.getMessage());
            bridge.sendToWebview("error",
                "{\"message\":" + JsonBuilder.jsonString("Failed to send message: " + e.getMessage()) + "}");
        }
    }

    /**
     * Open a side-by-side compare editor showing the file as it WAS
     * before Claude's edit vs. as it IS now. Reuses V1's ClaudeCompareInput.
     */
    private void handleViewDiff(String payload) {
        Activator.logInfo("[Webview/Edit] view_diff entered, payload=" + truncate(payload));
        try {
            Map<String, Object> data = JsonParser.parseObject(payload);
            String editId = JsonParser.getString(data, "editId");
            Activator.logInfo("[Webview/Edit] view_diff editId=" + editId);
            if (editId == null) {
                Activator.logWarning("[Webview/Edit] view_diff: editId is null in payload");
                return;
            }
            final String finalEditId = editId;
            org.eclipse.swt.widgets.Display d =
                (browser != null) ? browser.getDisplay() : org.eclipse.swt.widgets.Display.getDefault();
            d.asyncExec(() -> {
                try {
                    Activator.logInfo("[Webview/Edit] view_diff UI: editDecisionManager="
                        + (editDecisionManager == null ? "null" : "ok"));
                    if (editDecisionManager == null) return;
                    com.anthropic.eclipse.claude.diff.EditDecisionManager.PendingEdit edit =
                        editDecisionManager.getEdit(finalEditId);
                    Activator.logInfo("[Webview/Edit] view_diff getEdit -> "
                        + (edit == null ? "null" : "found (orig="
                            + edit.getOriginalContent().length() + " bytes, mod="
                            + edit.getModifiedContent().length() + " bytes)"));
                    if (edit == null) {
                        bridge.sendToWebview("toast",
                            "{\"message\":" + JsonBuilder.jsonString("Diff no longer available.") + "}");
                        return;
                    }
                    String fileName = java.nio.file.Paths.get(finalEditId).getFileName().toString();
                    com.anthropic.eclipse.claude.diff.ClaudeCompareInput.open(
                        getSite().getPage(),
                        edit.getOriginalContent(),
                        edit.getModifiedContent(),
                        fileName);
                    Activator.logInfo("[Webview/Edit] view_diff: ClaudeCompareInput.open returned");
                } catch (Exception e) {
                    Activator.logError("[Webview/Edit] view_diff failed: " + e.getClass().getName()
                        + ": " + e.getMessage(), e);
                }
            });
        } catch (Exception e) {
            Activator.logWarning("[Webview/Edit] view_diff payload parse failed: " + e.getMessage());
        }
    }

    /** Accept the edit — keep the file as Claude wrote it; just clear
     *  the pending state. */
    private void handleAcceptEdit(String payload) {
        try {
            Map<String, Object> data = JsonParser.parseObject(payload);
            String editId = JsonParser.getString(data, "editId");
            if (editId == null || editDecisionManager == null) return;
            editDecisionManager.acceptEdit(editId);
            bridge.sendToWebview("toast",
                "{\"message\":" + JsonBuilder.jsonString("Edit accepted: "
                    + java.nio.file.Paths.get(editId).getFileName()) + "}");
        } catch (Exception e) {
            Activator.logError("[Webview/Edit] accept_edit failed", e);
        }
    }

    /** Reject the edit — restore the file to its pre-edit state from the
     *  CheckpointManager snapshot and refresh the editor. */
    private void handleRejectEdit(String payload) {
        try {
            Map<String, Object> data = JsonParser.parseObject(payload);
            String editId = JsonParser.getString(data, "editId");
            if (editId == null || editDecisionManager == null) return;
            // Revert restores the snapshotted content on disk.
            try {
                Activator.getDefault().getCheckpointManager().revert(editId);
            } catch (Exception e) {
                Activator.logWarning("[Webview/Edit] revert from snapshot failed: " + e.getMessage());
            }
            editDecisionManager.rejectEdit(editId);
            // Refresh the file in Eclipse so the editor reflects the revert.
            try {
                org.eclipse.core.resources.IWorkspaceRoot root =
                    org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot();
                org.eclipse.core.resources.IFile[] files = root.findFilesForLocationURI(
                    java.nio.file.Paths.get(editId).toUri());
                for (org.eclipse.core.resources.IFile f : files) {
                    if (f != null && f.exists()) {
                        f.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_ZERO, null);
                    }
                }
            } catch (Exception ignored) {}
            bridge.sendToWebview("toast",
                "{\"message\":" + JsonBuilder.jsonString("Edit reverted: "
                    + java.nio.file.Paths.get(editId).getFileName()) + "}");
        } catch (Exception e) {
            Activator.logError("[Webview/Edit] reject_edit failed", e);
        }
    }

    /** Persist the user's "auto-attach active file" toggle and echo it
     *  back to JS so the chip UI reflects the new state. */
    private void handleSetAttachActiveFile(String payload) {
        try {
            Map<String, Object> data = JsonParser.parseObject(payload);
            Object e = (data != null) ? data.get("enabled") : null;
            boolean enabled = (e instanceof Boolean) ? (Boolean) e : true;
            Activator.getDefault().getPreferenceStore()
                .setValue(PreferenceConstants.ATTACH_ACTIVE_FILE, enabled);
            bridge.sendToWebview("attach_active_file_changed",
                "{\"enabled\":" + enabled + "}");
            // Re-push current file so the chip rerenders.
            lastActiveFilePath = "<resync-sentinel>";
            pushActiveFileToWebview();
        } catch (Exception ex) {
            Activator.logWarning("[Webview] set_attach_active_file failed: " + ex.getMessage());
        }
    }

    private void handleNewSession() {
        if (cliManager.isRunning()) {
            try { cliManager.stop(); } catch (Exception ignored) {}
        }
        ConversationModel oldModel = model;
        model.removeListener(this);
        cliManager.removeMessageListener(model);
        model = new ConversationModel();
        model.addListener(this);
        cliManager.addMessageListener(model);
        Activator.getDefault().setConversationModel(model);
        partNameSet = false;
        Display d = (browser != null) ? browser.getDisplay() : Display.getDefault();
        if (d != null && !d.isDisposed()) {
            d.asyncExec(() -> {
                try { setPartName("Claude Code"); } catch (Exception ignored) {}
            });
        }
        bridge.sendToWebview("conversation_cleared", "{}");
        autoStartCli();
    }

    private void handlePermissionResponse(String type, String payload) {
        try {
            Map<String, Object> data = JsonParser.parseObject(payload);
            String requestId = JsonParser.getString(data, "requestId");
            String toolUseId = JsonParser.getString(data, "toolUseId");
            String toolName  = JsonParser.getString(data, "toolName");
            boolean allow = !"reject_permission".equals(type);

            // Recover the original toolInput we stashed during onPermissionRequested.
            String key = (requestId != null && !requestId.isEmpty()) ? requestId : toolUseId;
            Object toolInput = (key != null) ? pendingToolInputs.remove(key) : null;

            // Send the response to the CLI — without this the tool stays
            // "Running" forever waiting on permission.
            sendPermissionResponse(requestId, toolUseId, allow, toolInput);

            // "Always allow" — add the tool to AUTO_APPROVE_TOOLS so future
            // requests for the same tool skip the banner.
            if ("always_allow_permission".equals(type) && toolName != null) {
                try {
                    org.eclipse.jface.preference.IPreferenceStore prefs =
                            Activator.getDefault().getPreferenceStore();
                    String current = prefs.getString(PreferenceConstants.AUTO_APPROVE_TOOLS);
                    if (current == null || current.isBlank()) {
                        prefs.setValue(PreferenceConstants.AUTO_APPROVE_TOOLS, toolName);
                    } else if (!current.contains(toolName)) {
                        prefs.setValue(PreferenceConstants.AUTO_APPROVE_TOOLS, current + "," + toolName);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Activator.logError("[Webview] permission response failed: " + e.getMessage(), e);
        }
    }

    private void handleChangeMode(String payload) {
        try {
            Map<String, Object> data = JsonParser.parseObject(payload);
            String mode = JsonParser.getString(data, "mode");
            if (mode != null) {
                currentMode = mode;
                bridge.sendToWebview("mode_changed", "{\"mode\":" + JsonBuilder.jsonString(mode) + "}");
            }
        } catch (Exception e) {
            Activator.logWarning("[Webview] change_mode failed: " + e.getMessage());
        }
    }

    private void handleChangeEffort(String payload) {
        try {
            Map<String, Object> data = JsonParser.parseObject(payload);
            String effort = JsonParser.getString(data, "effort");
            currentEffort = (effort != null) ? effort : "";
            bridge.sendToWebview("effort_changed", "{\"effort\":" + JsonBuilder.jsonString(currentEffort) + "}");
        } catch (Exception e) {
            Activator.logWarning("[Webview] change_effort failed: " + e.getMessage());
        }
    }

    /**
     * Open a brand new V2 view instance as a sibling tab next to this
     * view — port of V1's openNewConversationWindow. Strategy:
     * <ol>
     *   <li>{@code page.showView()} opens the view (Eclipse places it
     *       wherever the perspective default says — often a different
     *       folder than the current one).</li>
     *   <li>Reach into the e4 model and MOVE the new view's MPlaceholder
     *       into our MPartStack so it becomes a sibling tab.</li>
     * </ol>
     * Retries with backoff because the e4 compat layer wires the new
     * view's placeholder asynchronously.
     */
    private void handleNewTab() {
        org.eclipse.swt.widgets.Display d =
            (browser != null) ? browser.getDisplay() : org.eclipse.swt.widgets.Display.getDefault();
        d.asyncExec(() -> {
            try {
                org.eclipse.ui.IWorkbenchPage page = getSite().getPage();
                if (page == null) return;
                String secondaryId = "ctab-" + System.nanoTime();
                org.eclipse.ui.IViewPart newView = page.showView(
                    ID, secondaryId, org.eclipse.ui.IWorkbenchPage.VIEW_ACTIVATE);
                tryRelocateWithRetry(newView, 0);
            } catch (Exception e) {
                Activator.logError("[Webview] new_tab failed", e);
            }
        });
    }

    /** Retry the relocation up to 5 times with 100ms backoff — the
     *  e4 compat layer wires the new view's MPlaceholder asynchronously. */
    private void tryRelocateWithRetry(org.eclipse.ui.IViewPart newView, int attempt) {
        if (newView == null || attempt >= 5) return;
        org.eclipse.swt.widgets.Display.getDefault().timerExec(attempt == 0 ? 0 : 100, () -> {
            if (relocateToOwnStack(newView)) return;
            tryRelocateWithRetry(newView, attempt + 1);
        });
    }

    /**
     * Move the freshly-opened view's MPlaceholder to sit next to ours in
     * the same MPartStack. Mirrors V1's relocateToOwnStack — 3.x compat
     * views are SHARED MParts in the top-level window's sharedElements,
     * and what actually lives in a perspective's stack is the
     * {@link org.eclipse.e4.ui.model.application.ui.advanced.MPlaceholder
     * MPlaceholder} returned by {@code EModelService.findPlaceholderFor}.
     *
     * @return true if the relocation succeeded OR failed in a way that
     *         retrying won't help; false when the model isn't ready yet.
     */
    private boolean relocateToOwnStack(org.eclipse.ui.IViewPart newView) {
        if (newView == null) return true;
        try {
            org.eclipse.e4.ui.model.application.ui.basic.MPart thisPart =
                    getSite().getService(org.eclipse.e4.ui.model.application.ui.basic.MPart.class);
            org.eclipse.e4.ui.model.application.ui.basic.MPart newPart =
                    newView.getSite().getService(
                            org.eclipse.e4.ui.model.application.ui.basic.MPart.class);
            org.eclipse.e4.ui.workbench.modeling.EModelService modelService =
                    getSite().getService(org.eclipse.e4.ui.workbench.modeling.EModelService.class);
            org.eclipse.e4.ui.workbench.modeling.EPartService partService =
                    getSite().getService(org.eclipse.e4.ui.workbench.modeling.EPartService.class);
            if (thisPart == null || newPart == null
                    || modelService == null || partService == null) {
                Activator.logWarning("[Webview/new_tab/relocate] missing service");
                return true;
            }
            if (newPart == thisPart) return true;

            org.eclipse.e4.ui.model.application.ui.basic.MWindow window =
                    modelService.getTopLevelWindowFor(thisPart);
            if (window == null) return true;

            // The element that sits inside a perspective's MPartStack is
            // the placeholder (for shared parts), not the MPart itself.
            org.eclipse.e4.ui.model.application.ui.advanced.MPlaceholder thisPh =
                    modelService.findPlaceholderFor(window, thisPart);
            org.eclipse.e4.ui.model.application.ui.advanced.MPlaceholder newPh =
                    modelService.findPlaceholderFor(window, newPart);
            org.eclipse.e4.ui.model.application.ui.MUIElement thisAnchor =
                    (thisPh != null) ? thisPh : thisPart;
            org.eclipse.e4.ui.model.application.ui.MUIElement newAnchor =
                    (newPh != null) ? newPh : newPart;

            if (newAnchor.getParent() == null) {
                Activator.logInfo("[Webview/new_tab/relocate] newAnchor not yet attached — retry");
                return false;
            }
            // Walk up from thisAnchor to find the enclosing MPartStack.
            org.eclipse.e4.ui.model.application.ui.MUIElement cursor = thisAnchor.getParent();
            org.eclipse.e4.ui.model.application.ui.basic.MPartStack targetStack = null;
            while (cursor != null) {
                if (cursor instanceof org.eclipse.e4.ui.model.application.ui.basic.MPartStack) {
                    targetStack = (org.eclipse.e4.ui.model.application.ui.basic.MPartStack) cursor;
                    break;
                }
                cursor = cursor.getParent();
            }
            if (targetStack == null) {
                Activator.logInfo("[Webview/new_tab/relocate] no enclosing MPartStack — retry");
                return false;
            }
            if ((Object) newAnchor.getParent() == (Object) targetStack) {
                Activator.logInfo("[Webview/new_tab/relocate] already in target stack");
                return true;
            }
            // Detach from current container, attach to our stack.
            org.eclipse.e4.ui.model.application.ui.MElementContainer<?> oldParent = newAnchor.getParent();
            if (oldParent != null) {
                @SuppressWarnings({ "unchecked", "rawtypes" })
                java.util.List children = oldParent.getChildren();
                children.remove(newAnchor);
            }
            @SuppressWarnings({ "unchecked", "rawtypes" })
            java.util.List stackChildren = targetStack.getChildren();
            stackChildren.add(newAnchor);
            targetStack.setSelectedElement(
                    (org.eclipse.e4.ui.model.application.ui.basic.MStackElement) newAnchor);
            partService.activate(newPart);
            Activator.logInfo("[Webview/new_tab/relocate] moved newAnchor into our stack");
            return true;
        } catch (Throwable t) {
            Activator.logWarning("[Webview/new_tab/relocate] failed: " + t.getMessage());
            return true;
        }
    }

    /** @deprecated unused after switching to V1's relocate strategy. */
    @SuppressWarnings("unused")
    private boolean tryOpenNewTabInSameStack(org.eclipse.ui.IWorkbenchPage page) {
        try {
            org.eclipse.e4.ui.workbench.modeling.EPartService partService =
                getSite().getService(org.eclipse.e4.ui.workbench.modeling.EPartService.class);
            org.eclipse.e4.ui.model.application.ui.basic.MPart currentPart =
                getSite().getService(org.eclipse.e4.ui.model.application.ui.basic.MPart.class);
            // The MPart fetched from the SITE for a 3.x compatibility view
            // is sometimes detached from the runtime e4 model tree (its
            // getParent() returns null). The active part returned by
            // partService is the live one in the visible stack — prefer
            // that when the site-provided part has no parent.
            if (partService != null && (currentPart == null || currentPart.getParent() == null)) {
                org.eclipse.e4.ui.model.application.ui.basic.MPart activePart = partService.getActivePart();
                if (activePart != null) {
                    Activator.logInfo("[Webview/new_tab] using getActivePart() instead of "
                        + "site MPart (the latter had no parent). active="
                        + activePart.getElementId()
                        + " parent=" + (activePart.getParent() == null
                            ? "null"
                            : activePart.getParent().getClass().getSimpleName()));
                    currentPart = activePart;
                }
            }
            Activator.logInfo("[Webview/new_tab] e4 services: partService="
                + (partService == null ? "null" : "ok")
                + " currentPart=" + (currentPart == null ? "null" : currentPart.getElementId())
                + " parent=" + (currentPart != null && currentPart.getParent() != null
                    ? currentPart.getParent().getClass().getSimpleName()
                    : "null"));
            if (partService == null || currentPart == null) return false;

            org.eclipse.e4.ui.model.application.ui.basic.MPart newPart =
                partService.createPart(ID);
            Activator.logInfo("[Webview/new_tab] createPart(" + ID + ") -> "
                + (newPart == null ? "null" : newPart.getElementId()));
            if (newPart == null) return false;
            newPart.setElementId(ID + ":ctab-" + System.nanoTime());
            newPart.setLabel("Claude Code");

            // Walk up the model tree to find the enclosing MPartStack.
            org.eclipse.e4.ui.model.application.ui.MUIElement p = currentPart.getParent();
            org.eclipse.e4.ui.model.application.ui.basic.MPartStack stack = null;
            int depth = 0;
            while (p != null && depth++ < 20) {
                Activator.logInfo("[Webview/new_tab] walk parent[" + depth + "]: "
                    + p.getClass().getSimpleName()
                    + " id=" + p.getElementId());
                if (p instanceof org.eclipse.e4.ui.model.application.ui.basic.MPartStack) {
                    stack = (org.eclipse.e4.ui.model.application.ui.basic.MPartStack) p;
                    break;
                }
                p = p.getParent();
            }
            // Fallback: the site MPart's getParent() returns null because
            // the 3.x compatibility layer DOESN'T attach this synthetic
            // MPart to the live e4 model tree. Use EModelService to find
            // ALL MPart instances with our element id in the application
            // tree — the one with a non-null parent is the live tab.
            if (stack == null) {
                Activator.logInfo("[Webview/new_tab] parent chain empty, "
                    + "trying EModelService.findElements fallback");
                try {
                    org.eclipse.e4.ui.workbench.modeling.EModelService modelService =
                        getSite().getService(org.eclipse.e4.ui.workbench.modeling.EModelService.class);
                    org.eclipse.e4.ui.model.application.MApplication app =
                        getSite().getService(org.eclipse.e4.ui.model.application.MApplication.class);
                    if (modelService != null && app != null) {
                        java.util.List<org.eclipse.e4.ui.model.application.ui.basic.MPart> liveParts =
                            modelService.findElements(app, ID,
                                org.eclipse.e4.ui.model.application.ui.basic.MPart.class);
                        Activator.logInfo("[Webview/new_tab] findElements returned "
                            + liveParts.size() + " parts with id=" + ID);
                        for (org.eclipse.e4.ui.model.application.ui.basic.MPart lp : liveParts) {
                            org.eclipse.e4.ui.model.application.ui.MUIElement lpParent = lp.getParent();
                            Activator.logInfo("[Webview/new_tab] live part: "
                                + " elementId=" + lp.getElementId()
                                + " parent=" + (lpParent == null ? "null" : lpParent.getClass().getSimpleName())
                                + " rendered=" + lp.isToBeRendered());
                            if (lpParent instanceof org.eclipse.e4.ui.model.application.ui.basic.MPartStack) {
                                stack = (org.eclipse.e4.ui.model.application.ui.basic.MPartStack) lpParent;
                                currentPart = lp; // align index lookup below
                                Activator.logInfo("[Webview/new_tab] adopted live stack id="
                                    + stack.getElementId());
                                break;
                            }
                        }
                    }
                } catch (Throwable t) {
                    Activator.logWarning("[Webview/new_tab] findElements fallback failed: "
                        + t.getClass().getName() + ": " + t.getMessage());
                }
            }
            if (stack == null) {
                Activator.logWarning("[Webview/new_tab] no MPartStack found (even after fallback)");
                return false;
            }
            Activator.logInfo("[Webview/new_tab] placing new part into stack id="
                + stack.getElementId() + " (currentPart idx="
                + stack.getChildren().indexOf(currentPart) + " of "
                + stack.getChildren().size() + ")");

            int idx = stack.getChildren().indexOf(currentPart);
            if (idx < 0) idx = stack.getChildren().size() - 1;
            stack.getChildren().add(idx + 1, newPart);
            partService.activate(newPart);
            return true;
        } catch (Throwable t) {
            Activator.logWarning("[Webview/new_tab] e4 placement failed, "
                + "falling back to legacy showView: " + t.getClass().getName()
                + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * Close this V2 view instance — triggered by the trash-can header
     * button. The view's dispose() handles CLI stop and listener cleanup.
     */
    private void handleCloseTab() {
        org.eclipse.swt.widgets.Display d =
            (browser != null) ? browser.getDisplay() : org.eclipse.swt.widgets.Display.getDefault();
        d.asyncExec(() -> {
            try {
                org.eclipse.ui.IWorkbenchPage page = getSite().getPage();
                if (page != null) page.hideView(ClaudeConversationViewV2.this);
            } catch (Exception e) {
                Activator.logError("[Webview] close_tab failed", e);
            }
        });
    }

    /**
     * Rename this tab — webview sends a {name} payload. Pins the title so
     * the auto-generated topic title doesn't overwrite it.
     */
    private void handleRenameTab(String payload) {
        try {
            Map<String, Object> data = JsonParser.parseObject(payload);
            String name = JsonParser.getString(data, "name");
            if (name == null || name.isBlank()) return;
            final String finalName = name.trim();
            partNameSet = true; // pin against auto-generated title overwrite
            org.eclipse.swt.widgets.Display d =
                (browser != null) ? browser.getDisplay() : org.eclipse.swt.widgets.Display.getDefault();
            d.asyncExec(() -> {
                try { setPartName(finalName); } catch (Exception ignored) {}
            });
        } catch (Exception e) {
            Activator.logError("[Webview] rename_tab failed", e);
        }
    }

    /**
     * Apply a code block to the active text editor — replaces the current
     * selection if one exists, otherwise replaces the entire document
     * content. Used by the "Apply" button on every code block in the
     * webview. Mirrors the IntelliJ plugin's handleApplyToEditor.
     */
    private void handleApplyToEditor(String payload) {
        try {
            Map<String, Object> data = JsonParser.parseObject(payload);
            String code = JsonParser.getString(data, "code");
            if (code == null || code.isEmpty()) return;
            final String finalCode = code;
            org.eclipse.swt.widgets.Display d =
                (browser != null) ? browser.getDisplay() : org.eclipse.swt.widgets.Display.getDefault();
            d.asyncExec(() -> {
                try {
                    org.eclipse.ui.IWorkbenchPage page = getSite().getPage();
                    org.eclipse.ui.IEditorPart editor = (page != null) ? page.getActiveEditor() : null;
                    if (!(editor instanceof org.eclipse.ui.texteditor.ITextEditor)) {
                        bridge.sendToWebview("toast",
                            "{\"message\":" + JsonBuilder.jsonString("No active text editor to apply to.") + "}");
                        return;
                    }
                    org.eclipse.ui.texteditor.ITextEditor textEditor =
                        (org.eclipse.ui.texteditor.ITextEditor) editor;
                    org.eclipse.jface.text.IDocument doc = textEditor.getDocumentProvider()
                        .getDocument(textEditor.getEditorInput());
                    if (doc == null) return;
                    org.eclipse.jface.viewers.ISelection sel = textEditor.getSelectionProvider().getSelection();
                    int offset, length;
                    if (sel instanceof org.eclipse.jface.text.ITextSelection
                            && ((org.eclipse.jface.text.ITextSelection) sel).getLength() > 0) {
                        org.eclipse.jface.text.ITextSelection ts =
                            (org.eclipse.jface.text.ITextSelection) sel;
                        offset = ts.getOffset();
                        length = ts.getLength();
                    } else {
                        // No selection — replace the whole document.
                        offset = 0;
                        length = doc.getLength();
                    }
                    doc.replace(offset, length, finalCode);
                    bridge.sendToWebview("toast",
                        "{\"message\":" + JsonBuilder.jsonString("Code applied to " + editor.getTitle()) + "}");
                } catch (Exception e) {
                    Activator.logError("[Webview] apply_to_editor failed", e);
                    bridge.sendToWebview("toast",
                        "{\"message\":" + JsonBuilder.jsonString("Failed to apply: " + e.getMessage()) + "}");
                }
            });
        } catch (Exception e) {
            Activator.logError("[Webview] apply_to_editor payload parse failed", e);
        }
    }

    /**
     * Insert a code block at the active editor's caret position. Mirrors
     * the IntelliJ plugin's handleInsertAtCursor.
     */
    private void handleInsertAtCursor(String payload) {
        try {
            Map<String, Object> data = JsonParser.parseObject(payload);
            String code = JsonParser.getString(data, "code");
            if (code == null || code.isEmpty()) return;
            final String finalCode = code;
            org.eclipse.swt.widgets.Display d =
                (browser != null) ? browser.getDisplay() : org.eclipse.swt.widgets.Display.getDefault();
            d.asyncExec(() -> {
                try {
                    org.eclipse.ui.IWorkbenchPage page = getSite().getPage();
                    org.eclipse.ui.IEditorPart editor = (page != null) ? page.getActiveEditor() : null;
                    if (!(editor instanceof org.eclipse.ui.texteditor.ITextEditor)) {
                        bridge.sendToWebview("toast",
                            "{\"message\":" + JsonBuilder.jsonString("No active text editor to insert into.") + "}");
                        return;
                    }
                    org.eclipse.ui.texteditor.ITextEditor textEditor =
                        (org.eclipse.ui.texteditor.ITextEditor) editor;
                    org.eclipse.jface.text.IDocument doc = textEditor.getDocumentProvider()
                        .getDocument(textEditor.getEditorInput());
                    if (doc == null) return;
                    int offset = 0;
                    org.eclipse.jface.viewers.ISelection sel = textEditor.getSelectionProvider().getSelection();
                    if (sel instanceof org.eclipse.jface.text.ITextSelection) {
                        offset = ((org.eclipse.jface.text.ITextSelection) sel).getOffset();
                    }
                    doc.replace(offset, 0, finalCode);
                    // Place caret right after the inserted text.
                    textEditor.selectAndReveal(offset + finalCode.length(), 0);
                    bridge.sendToWebview("toast",
                        "{\"message\":" + JsonBuilder.jsonString("Code inserted at cursor in " + editor.getTitle()) + "}");
                } catch (Exception e) {
                    Activator.logError("[Webview] insert_at_cursor failed", e);
                    bridge.sendToWebview("toast",
                        "{\"message\":" + JsonBuilder.jsonString("Failed to insert: " + e.getMessage()) + "}");
                }
            });
        } catch (Exception e) {
            Activator.logError("[Webview] insert_at_cursor payload parse failed", e);
        }
    }

    /**
     * Open a settings dialog from the header dropdown or the history button.
     * Dialog name comes from app.js settings-dropdown / history button.
     * Reuses the existing JFace dialogs from V1 (SessionHistoryDialog,
     * RulesDialog, etc.) — no need to re-implement them in HTML.
     */
    private void handleOpenDialog(String payload) {
        try {
            Map<String, Object> data = JsonParser.parseObject(payload);
            String dialog = JsonParser.getString(data, "dialog");
            if (dialog == null) return;
            String workDir = getDefaultWorkingDirectory();
            org.eclipse.swt.widgets.Shell shell = getViewSite().getShell();
            switch (dialog) {
                case "history":
                    openHistoryDialog(shell, workDir);
                    break;
                case "rules":
                    new com.anthropic.eclipse.claude.views.RulesDialog(shell, workDir).open();
                    break;
                case "mcp":
                    new com.anthropic.eclipse.claude.views.McpServersDialog(shell, workDir).open();
                    break;
                case "hooks":
                    new com.anthropic.eclipse.claude.views.HooksDialog(shell, workDir).open();
                    break;
                case "memory":
                    new com.anthropic.eclipse.claude.views.MemoryDialog(shell, workDir).open();
                    break;
                case "skills":
                    new com.anthropic.eclipse.claude.views.SkillsDialog(shell).open();
                    break;
                case "preferences":
                    // Open Eclipse preferences scoped to our page if possible.
                    org.eclipse.ui.dialogs.PreferencesUtil.createPreferenceDialogOn(
                        shell, "com.anthropic.eclipse.claude.preferences.ClaudePreferencePage",
                        null, null).open();
                    break;
                default:
                    Activator.logInfo("[Webview] open_dialog unknown dialog: " + dialog);
            }
        } catch (Exception e) {
            Activator.logError("[Webview] open_dialog failed: " + e.getClass().getName()
                + ": " + e.getMessage(), e);
        }
    }

    /**
     * Open the Session History dialog and, if the user selects a session,
     * resume it: stop the current CLI, clear the model, replay the JSONL
     * transcript into the view, and start a new CLI with --resume.
     */
    private void openHistoryDialog(org.eclipse.swt.widgets.Shell shell, String workDir) {
        com.anthropic.eclipse.claude.views.SessionHistoryDialog dialog =
            new com.anthropic.eclipse.claude.views.SessionHistoryDialog(shell, workDir, sessionManager);
        if (dialog.open() == Window.OK) {
            String sessionId = dialog.getSelectedSessionId();
            if (sessionId != null && !sessionId.isEmpty()) {
                resumeSession(sessionId);
            }
        }
    }

    /**
     * Resume a previous session. Stops the current CLI, swaps the model,
     * replays the JSONL into the new model (which fires the same listener
     * events the webview is already wired to consume), and starts a fresh
     * CLI process with --resume so subsequent turns preserve memory.
     */
    private void resumeSession(String sessionId) {
        try { cliManager.stop(); } catch (Exception ignored) {}

        // Tell the webview we're starting fresh so it clears the bubble list.
        bridge.sendToWebview("conversation_cleared", "{}");

        // Swap in a new model — old listeners detach, new one becomes the
        // CLI's message sink.
        if (model != null) model.removeListener(this);
        if (cliManager != null) cliManager.removeMessageListener(model);
        model = new ConversationModel();
        model.addListener(this);
        cliManager.addMessageListener(model);
        Activator.getDefault().setConversationModel(model);

        // Resume: derive the tab title from the resumed session's summary
        // (CLI auto-summary preferred), then keep partNameSet=true so the
        // FIRST replayed user message doesn't overwrite it. Falls through
        // to the user-message-derived title if no summary is on disk.
        try {
            SessionInfo info = com.anthropic.eclipse.claude.session.JsonlSessionScanner.findSessionById(sessionId);
            if (info != null && info.getSummary() != null && !info.getSummary().isBlank()) {
                String title = info.getSummary().trim();
                if (title.length() > 30) title = title.substring(0, 30) + "…";
                final String finalTitle = title;
                partNameSet = true;
                Display d = (browser != null) ? browser.getDisplay() : Display.getDefault();
                if (d != null && !d.isDisposed()) {
                    d.asyncExec(() -> {
                        try { setPartName(finalTitle); } catch (Exception ignored) {}
                    });
                }
            } else {
                // No summary — let maybeUpdateTabTitle fire from the first
                // replayed user message instead.
                partNameSet = false;
            }
        } catch (Exception ignored) {
            partNameSet = false;
        }

        // Load the JSONL transcript into the model. loadHistory() takes care
        // of mutex-safe adding to the messages list AND fires the right
        // listener events (onUserMessageAdded / onAssistantMessageStarted /
        // onAssistantMessageCompleted) — our V2 listener picks those up and
        // pushes them to the webview via the bridge. NB: getMessages()
        // returns an unmodifiable defensive copy, so we cannot add directly.
        java.util.List<MessageBlock> history = JsonlHistoryLoader.load(sessionId);
        Activator.logInfo("[Webview] resumeSession " + sessionId + " loading " + history.size() + " blocks");
        try {
            // V2 uses Chromium scroll; no SWT 32767px limit, so render
            // ALL blocks (Integer.MAX_VALUE = no cap).
            model.loadHistory(history, Integer.MAX_VALUE);
        } catch (Exception e) {
            Activator.logError("[Webview] loadHistory failed: " + e.getClass().getName()
                + ": " + e.getMessage(), e);
        }

        // After bulk-replay, force the page to scroll to the latest bubble.
        // Each onAssistantMessageStarted/Completed already calls
        // scrollToBottom() in JS, but during a rapid 388-block replay the
        // browser hasn't finished laying out the late bubbles yet — by the
        // time the final scrollTop = scrollHeight runs, scrollHeight is
        // still increasing as bubbles paint. A double rAF after the queue
        // drains gives the browser two paint frames to settle dimensions
        // before we set the scroll position one last time.
        if (browser != null && !browser.isDisposed()) {
            browser.getDisplay().asyncExec(() -> {
                if (browser == null || browser.isDisposed()) return;
                try {
                    browser.execute(
                        "requestAnimationFrame(function(){ requestAnimationFrame(function(){"
                      + "  var m = document.getElementById('messages');"
                      + "  if (m) m.scrollTop = m.scrollHeight;"
                      + "}); });");
                } catch (Exception ignored) {}
            });
        }

        // Start a new CLI with --resume so the next message preserves memory.
        try {
            String cliPath = cliManager.getCliPath();
            if (cliPath == null) {
                if (!cliManager.detectCLI()) {
                    bridge.sendToWebview("error", "{\"message\":"
                        + JsonBuilder.jsonString("Claude CLI not found.") + "}");
                    return;
                }
                cliPath = cliManager.getCliPath();
            }
            String workDir = getDefaultWorkingDirectory();
            IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();
            int maxTurns = prefs.getInt(PreferenceConstants.MAX_TURNS);
            CliProcessConfig.Builder builder = new CliProcessConfig.Builder(cliPath, workDir)
                .model(mapModelName(prefs.getString(PreferenceConstants.MODEL)))
                .permissionMode(cliPermissionModeFor(currentMode))
                .effort(currentEffort)
                .resumeSessionId(sessionId);
            if (maxTurns > 0) builder.maxTurns(maxTurns);
            cliManager.start(builder.build());
        } catch (Exception e) {
            Activator.logWarning("[Webview] CLI start with --resume failed: " + e.getMessage());
            bridge.sendToWebview("error", "{\"message\":"
                + JsonBuilder.jsonString("Failed to resume CLI: " + e.getMessage()) + "}");
        }
    }

    // ====================== IConversationListener ======================

    @Override
    public void onSessionInitialized(SessionInfo info) {
        if (info != null) {
            // Remember the session id so saveState() can write it to the
            // memento even if model.getSessionInfo() returns null at close
            // time (e.g. after a CLI crash).
            if (info.getSessionId() != null && !info.getSessionId().isEmpty()) {
                stickySessionId = info.getSessionId();
            }
            bridge.sendToWebview("session_initialized", JsonBuilder.buildSessionInfoJson(info));
        }
    }

    @Override
    public void onUserMessageAdded(MessageBlock block) {
        bridge.sendToWebview("user_message_added", JsonBuilder.buildMessageBlockJson(block));
        maybeUpdateTabTitle(block);
    }

    /**
     * Update the view's tab title from the first user message. Matches V1
     * exactly — first set an immediate truncated-first-message title so the
     * tab is never empty, then (if the {@code TAB_TITLE_STRATEGY} preference
     * is "self_generated", the default) kick off a background {@code claude
     * -p} call that produces a 3-5 word topic title and upgrades the tab
     * name in place. Only fires once per session.
     */
    private void maybeUpdateTabTitle(MessageBlock block) {
        if (partNameSet) return;
        if (block == null || block.getRole() != MessageBlock.Role.USER) return;
        String text = block.getFullText();
        if (text == null) return;
        String firstMessage = text.trim();
        if (firstMessage.isEmpty()) return;

        // Immediate fallback: truncated first message
        String fallback = firstMessage.length() > 30
                ? firstMessage.substring(0, 30) + "…"
                : firstMessage;
        partNameSet = true;
        final String finalFallback = fallback;
        Display d = (browser != null) ? browser.getDisplay() : Display.getDefault();
        if (d != null && !d.isDisposed()) {
            d.asyncExec(() -> {
                try { setPartName(finalFallback); } catch (Exception ignored) {}
            });
        }

        // Optional upgrade: self-generated 3-5 word topic title
        String strategy = "self_generated";
        try {
            strategy = Activator.getDefault().getPreferenceStore()
                    .getString(PreferenceConstants.TAB_TITLE_STRATEGY);
            if (strategy == null || strategy.isBlank()) strategy = "self_generated";
        } catch (Exception ignored) {}
        if ("self_generated".equals(strategy)) {
            kickoffSelfGeneratedTitle(firstMessage);
        }
    }

    /**
     * Spawn a background {@code claude -p} that returns a 3-5 word topic
     * title for the conversation and updates the tab name in place. Best-
     * effort — the immediate first-message title remains if this fails or
     * times out. Mirrors V1's identical method.
     */
    private void kickoffSelfGeneratedTitle(final String firstMessage) {
        if (firstMessage == null || firstMessage.trim().isEmpty()) return;
        Thread t = new Thread(() -> {
            try {
                String cliPath = cliManager.getCliPath();
                if (cliPath == null) return;
                String prompt = "You are a title generator. Ignore any project context, "
                        + "CLAUDE.md, or files in the working directory — they are irrelevant. "
                        + "Read ONLY the user question below and output a 3-5 word topic title "
                        + "describing what THE QUESTION is about. Same language as the question. "
                        + "No surrounding quotes, no trailing punctuation, no preamble — output "
                        + "the title and nothing else.\n\n"
                        + "User question:\n" + firstMessage;
                ProcessBuilder pb = new ProcessBuilder(cliPath, "-p");
                // user.home cwd — avoid picking up the IDE project's CLAUDE.md /
                // settings as context (would produce off-topic titles).
                pb.directory(new java.io.File(System.getProperty("user.home")));
                pb.redirectErrorStream(false);
                Process p = pb.start();
                // Pipe prompt via STDIN as UTF-8 — Windows argv uses ANSI
                // code page and would mangle Hebrew/Unicode.
                try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                        p.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8)) {
                    w.write(prompt);
                    w.flush();
                }
                StringBuilder out = new StringBuilder();
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(),
                                java.nio.charset.StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = br.readLine()) != null) out.append(l).append('\n');
                }
                if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return;
                }
                if (p.exitValue() != 0) return;
                String title = out.toString().trim();
                title = title.replaceAll("^[\"'“”‘’]+", "")
                             .replaceAll("[\"'“”‘’\\.!?]+$", "")
                             .trim();
                int nl = title.indexOf('\n');
                if (nl >= 0) title = title.substring(0, nl).trim();
                if (title.isEmpty()) return;
                if (title.length() > 50) title = title.substring(0, 50) + "…";
                final String finalTitle = title;
                Display d2 = (browser != null) ? browser.getDisplay() : Display.getDefault();
                if (d2 != null && !d2.isDisposed()) {
                    d2.asyncExec(() -> {
                        try {
                            if (!isPartNameDisposed()) setPartName(finalTitle);
                        } catch (Exception ignored) {}
                    });
                }
            } catch (Exception ignored) {
                // Best-effort; fallback title stays.
            }
        }, "Claude-Title-Gen-V2");
        t.setDaemon(true);
        t.start();
    }

    private boolean isPartNameDisposed() {
        try {
            return getSite() == null || getSite().getShell() == null
                    || getSite().getShell().isDisposed();
        } catch (Exception e) {
            return true;
        }
    }

    @Override
    public void onAssistantMessageStarted(MessageBlock block) {
        bridge.sendToWebview("assistant_message_started", JsonBuilder.buildMessageBlockJson(block));
    }

    @Override
    public void onStreamingTextAppended(MessageBlock block, String delta) {
        bridge.sendToWebview("streaming_text_appended",
            "{\"delta\":" + JsonBuilder.jsonString(delta) + "}");
    }

    @Override
    public void onToolCallStarted(MessageBlock block, MessageBlock.ToolCallSegment toolCall) {
        Activator.logInfo("[Webview/Tool] STARTED  toolId=" + toolCall.getToolId()
            + " name=" + toolCall.getToolName());
        bridge.sendToWebview("tool_call_started", JsonBuilder.buildToolCallJson(toolCall));
    }

    @Override
    public void onToolCallInputComplete(MessageBlock block, MessageBlock.ToolCallSegment toolCall) {
        // BEFORE the tool runs: snapshot the target file so we can show
        // original-vs-modified diff and offer Revert after the edit
        // completes. Without this snapshot, post-edit "View Diff" would
        // compare the file to itself (CLI has already overwritten it).
        String toolName = toolCall.getToolName();
        if (toolName != null && ("Write".equals(toolName) || "Edit".equals(toolName)
                || "MultiEdit".equals(toolName))) {
            String filePath = extractFilePath(toolCall.getInput());
            if (filePath != null) {
                try {
                    Activator.getDefault().getCheckpointManager().snapshot(filePath);
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onToolCallInputDelta(MessageBlock block, MessageBlock.ToolCallSegment toolCall, String delta) {
        bridge.sendToWebview("tool_call_input_delta",
            "{\"toolId\":" + JsonBuilder.jsonString(toolCall.getToolId())
            + ",\"delta\":" + JsonBuilder.jsonString(delta) + "}");
    }

    @Override
    public void onToolCallCompleted(MessageBlock block, MessageBlock.ToolCallSegment toolCall) {
        Activator.logInfo("[Webview/Tool] COMPLETED toolId=" + toolCall.getToolId()
            + " name=" + toolCall.getToolName()
            + " status=" + toolCall.getStatus());
        bridge.sendToWebview("tool_call_completed", JsonBuilder.buildToolCallJson(toolCall));

        // Edit/Write/MultiEdit changed a file — stage the edit so the user
        // can View Diff / Accept / Reject via the inline widget in JS.
        String tn = toolCall.getToolName();
        if (tn != null && toolCall.getStatus() == MessageBlock.ToolStatus.COMPLETED
                && ("Edit".equals(tn) || "Write".equals(tn) || "MultiEdit".equals(tn))) {
            handleEditToolCompleted(toolCall);
        }
    }

    /**
     * After Edit/Write/MultiEdit completes the CLI has ALREADY written the
     * file. Compare what's now on disk against the snapshot taken in
     * {@link #onToolCallInputComplete}, register the pair with
     * {@link EditDecisionManager}, and emit {@code edit_staged} so the
     * webview shows the View Diff / Accept / Reject buttons.
     */
    private void handleEditToolCompleted(MessageBlock.ToolCallSegment toolCall) {
        if (editDecisionManager == null) return;
        try {
            String input = toolCall.getInput();
            String filePath = extractFilePath(input);
            if (filePath == null) return;

            com.anthropic.eclipse.claude.diff.CheckpointManager ckpt =
                Activator.getDefault().getCheckpointManager();
            String original = ckpt.getSnapshots().get(filePath);
            if (original == null) original = ""; // brand-new file (Write)
            String modified;
            try {
                modified = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(filePath)),
                    java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                Activator.logWarning("[Webview/Edit] could not read modified file: " + filePath
                    + " (" + e.getMessage() + ")");
                return;
            }
            if (original.equals(modified)) return; // nothing actually changed

            editDecisionManager.recordCompletedEdit(filePath, original, modified, toolCall);

            // Emit edit_staged event — JS renders the inline widget with
            // View Diff / Accept / Reject buttons keyed by editId.
            String fileName = java.nio.file.Paths.get(filePath).getFileName().toString();
            StringBuilder json = new StringBuilder("{");
            json.append("\"editId\":").append(JsonBuilder.jsonString(filePath));
            json.append(",\"filePath\":").append(JsonBuilder.jsonString(filePath));
            json.append(",\"fileName\":").append(JsonBuilder.jsonString(fileName));
            json.append(",\"toolName\":").append(JsonBuilder.jsonString(toolCall.getToolName()));
            json.append("}");
            bridge.sendToWebview("edit_staged", json.toString());
            Activator.logInfo("[Webview/Edit] STAGED file=" + fileName
                + " (original=" + original.length() + " bytes, modified=" + modified.length() + " bytes)");
        } catch (Exception e) {
            Activator.logError("[Webview/Edit] stage failed", e);
        }
    }

    /** Tool input JSON → {@code file_path} field. Null if absent / unparsable. */
    private String extractFilePath(String toolInputJson) {
        if (toolInputJson == null || toolInputJson.isBlank()) return null;
        try {
            Map<String, Object> parsed = JsonParser.parseObject(toolInputJson);
            return JsonParser.getString(parsed, "file_path");
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onAssistantMessageCompleted(MessageBlock block) {
        // Include a finishedAt timestamp so the JS can compute the bubble's
        // duration ("HH:MM:SS · 23s"). For LIVE messages, finishedAt is now
        // (the actual completion wall-clock). For REPLAYED messages,
        // block.timestamp IS the original JSONL write time = end of the
        // assistant turn, so we use that to preserve the historical
        // duration. This matches V1's MessageComposite.finishedAtMs logic.
        long finishedAt = block.isRestoredFromHistory()
                ? block.getTimestamp()
                : System.currentTimeMillis();
        String json = JsonBuilder.buildMessageBlockJson(block);
        // Splice finishedAt before the closing '}'
        if (json.endsWith("}")) {
            json = json.substring(0, json.length() - 1)
                 + ",\"finishedAt\":" + finishedAt + "}";
        }
        bridge.sendToWebview("assistant_message_completed", json);
    }

    @Override
    public void onResultReceived(UsageInfo usage) {
        Activator.logInfo("[Webview/Turn] result_received "
            + "tokens=" + usage.formatTokens()
            + " cost=" + usage.formatCost()
            + " duration=" + usage.formatDuration()
            + " turns=" + usage.getTotalTurns()
            + "  -- end of turn, JS will mark any still-running tools as Aborted.");
        bridge.sendToWebview("result_received", JsonBuilder.buildUsageJson(usage));
    }

    @Override
    public void onPermissionRequested(String toolUseId, String toolName, String description,
                                      String requestId, Object toolInput) {
        // Stash the input so we can echo it back as updatedInput when the
        // user accepts (CLI's control_response schema requires it). Key by
        // requestId for new-format requests, falling back to toolUseId.
        String key = (requestId != null && !requestId.isEmpty()) ? requestId : toolUseId;
        if (key != null && toolInput != null) {
            pendingToolInputs.put(key, toolInput);
        }

        // Auto-approve via preference: if the tool is in the AUTO_APPROVE_TOOLS
        // list, respond immediately and skip showing the banner. Mirrors V1.
        try {
            String autoApprove = Activator.getDefault().getPreferenceStore()
                    .getString(PreferenceConstants.AUTO_APPROVE_TOOLS);
            if (autoApprove != null && !autoApprove.isBlank() && toolName != null) {
                for (String tool : autoApprove.split(",")) {
                    if (tool.trim().equalsIgnoreCase(toolName)) {
                        sendPermissionResponse(requestId, toolUseId, true, toolInput);
                        if (key != null) pendingToolInputs.remove(key);
                        return;
                    }
                }
            }
        } catch (Exception ignored) {}

        StringBuilder json = new StringBuilder("{");
        json.append("\"toolUseId\":").append(JsonBuilder.jsonString(toolUseId));
        json.append(",\"toolName\":").append(JsonBuilder.jsonString(toolName));
        json.append(",\"description\":").append(JsonBuilder.jsonString(description));
        json.append(",\"requestId\":").append(JsonBuilder.jsonString(requestId));
        json.append(",\"toolInput\":").append(WebviewBridge.toJson(toolInput));
        json.append("}");
        bridge.sendToWebview("permission_requested", json.toString());
    }

    /**
     * Send the {@code control_response} (new format) or
     * {@code permission_response} (legacy format) back to the CLI so the
     * tool can either proceed or be cancelled. Without this, a permissioned
     * tool stays in "Running" status forever because the CLI is blocked
     * waiting for our response.
     */
    private void sendPermissionResponse(String requestId, String toolUseId,
                                        boolean allow, Object toolInput) {
        try {
            String ndjson;
            if (requestId != null && !requestId.isEmpty()) {
                // New format: control_response — needs the original tool input
                // echoed back as updatedInput when allowing.
                ndjson = com.anthropic.eclipse.claude.cli.CliMessage
                        .createControlResponse(requestId, allow, toolInput);
            } else {
                // Legacy format: permission_response keyed by tool_use_id.
                ndjson = com.anthropic.eclipse.claude.cli.CliMessage
                        .createPermissionResponse(toolUseId, allow);
            }
            cliManager.sendRawNdjson(ndjson);
            Activator.logInfo("[Webview] permission response sent: allow=" + allow
                + " requestId=" + requestId + " toolUseId=" + toolUseId);
        } catch (Exception e) {
            Activator.logError("[Webview] sendPermissionResponse failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void onExtendedThinkingStarted() {
        bridge.sendToWebview("extended_thinking_started", "{}");
    }

    @Override
    public void onExtendedThinkingEnded() {
        bridge.sendToWebview("extended_thinking_ended", "{}");
    }

    @Override
    public void onError(String error) {
        bridge.sendToWebview("error",
            "{\"message\":" + JsonBuilder.jsonString(error) + "}");
    }

    @Override
    public void onConversationCleared() {
        bridge.sendToWebview("conversation_cleared", "{}");
        partNameSet = false;
        Display d = (browser != null) ? browser.getDisplay() : Display.getDefault();
        if (d != null && !d.isDisposed()) {
            d.asyncExec(() -> {
                try { setPartName("Claude Code"); } catch (Exception ignored) {}
            });
        }
    }

    // ====================== ICliStateListener ======================

    @Override
    public void onStateChanged(ClaudeCliManager.ProcessState oldState, ClaudeCliManager.ProcessState newState) {
        String state;
        switch (newState) {
            case RUNNING:   state = "connected";    break;
            case STARTING:  state = "connecting";   break;
            case ERROR:     state = "error";        break;
            case NOT_STARTED:
            case STOPPING:
            case STOPPED:
            default:        state = "disconnected"; break;
        }
        bridge.sendToWebview("cli_state_changed", "{\"state\":\"" + state + "\"}");
    }

    // ====================== CLI startup ======================

    private void autoStartCli() {
        if (cliManager.isRunning()) return;

        try {
            IPreferenceStore modePrefs = Activator.getDefault().getPreferenceStore();
            if (!modePrefs.getBoolean(PreferenceConstants.USE_CLI_MODE)) {
                bridge.sendToWebview("error", "{\"message\":"
                    + JsonBuilder.jsonString("CLI mode is disabled. Enable 'Use CLI Mode' in Preferences > Claude AI.")
                    + "}");
                return;
            }
        } catch (Exception ignored) {}

        String apiKey = SecureApiKeyStore.getApiKey();
        if ((apiKey == null || apiKey.isBlank()) && !cliManager.isOAuthAuthenticated()) {
            bridge.sendToWebview("error", "{\"message\":"
                + JsonBuilder.jsonString("Anthropic API key not configured. "
                    + "Set it in Window > Preferences > Claude AI, or run 'claude auth login' in a terminal.")
                + "}");
            return;
        }

        String cliPath = cliManager.getCliPath();
        if (cliPath == null) {
            if (!cliManager.detectCLI()) {
                bridge.sendToWebview("error", "{\"message\":"
                    + JsonBuilder.jsonString("Claude CLI not found. " + ClaudeCliManager.getInstallInstructions())
                    + "}");
                return;
            }
            cliPath = cliManager.getCliPath();
        }

        String workDir = getDefaultWorkingDirectory();
        IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();
        String prefsModel = prefs.getString(PreferenceConstants.MODEL);
        String cliModel = mapModelName(prefsModel);
        int maxTurns = prefs.getInt(PreferenceConstants.MAX_TURNS);

        try {
            CliProcessConfig.Builder builder = new CliProcessConfig.Builder(cliPath, workDir)
                .model(cliModel)
                .permissionMode(cliPermissionModeFor(currentMode))
                .effort(currentEffort);
            if (maxTurns > 0) builder.maxTurns(maxTurns);
            cliManager.start(builder.build());
        } catch (ClaudeCliManager.CliException e) {
            bridge.sendToWebview("error", "{\"message\":"
                + JsonBuilder.jsonString("Failed to start Claude CLI: " + e.getMessage()) + "}");
        }
    }

    private String getDefaultWorkingDirectory() {
        try {
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            for (IProject p : projects) {
                if (p.isOpen() && p.getLocation() != null) {
                    return p.getLocation().toOSString();
                }
            }
        } catch (Exception ignored) {}
        return System.getProperty("user.dir");
    }

    private String cliPermissionModeFor(String uiMode) {
        if (uiMode == null) return "default";
        switch (uiMode) {
            case "acceptEdits": return "acceptEdits";
            case "plan":        return "plan";
            case "default":
            default:            return "default";
        }
    }

    private static String mapModelName(String pref) {
        if (pref == null || pref.isBlank()) return null;
        String lower = pref.toLowerCase();
        if (lower.contains("opus"))   return "opus";
        if (lower.contains("sonnet")) return "sonnet";
        if (lower.contains("haiku"))  return "haiku";
        return pref;
    }

    private void initModeFromPreferences() {
        try {
            IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();
            String mode = prefs.getString(PreferenceConstants.PERMISSION_MODE);
            if (mode != null && !mode.isEmpty()) currentMode = mode;
            String effort = prefs.getString(PreferenceConstants.EFFORT_LEVEL);
            if (effort != null) currentEffort = effort;
        } catch (Exception ignored) {}
    }

    // ====================== Theme detection ======================

    private boolean isDarkTheme() {
        try {
            Display d = (browser != null) ? browser.getDisplay() : Display.getDefault();
            org.eclipse.swt.graphics.Color bg = d.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND);
            int luminance = (bg.getRed() * 299 + bg.getGreen() * 587 + bg.getBlue() * 114) / 1000;
            return luminance < 128;
        } catch (Exception ignored) {
            return false;
        }
    }

    // ====================== Lifecycle ======================

    @Override
    public void setFocus() {
        if (browser != null && !browser.isDisposed()) {
            browser.setFocus();
        }
    }

    @Override
    public void dispose() {
        try {
            if (activeFilePartListener != null) {
                try { getSite().getPage().removePartListener(activeFilePartListener); }
                catch (Exception ignored) {}
                activeFilePartListener = null;
            }
            if (model != null) model.removeListener(this);
            if (cliManager != null) {
                cliManager.removeStateListener(this);
                if (model != null) cliManager.removeMessageListener(model);
                if (cliManager.isRunning()) {
                    try { cliManager.stop(); } catch (Exception ignored) {}
                }
            }
            if (bridge != null) bridge.dispose();
        } catch (Exception ignored) {}
        super.dispose();
    }

    private static String truncate(String s) {
        if (s == null) return "null";
        return s.length() <= 120 ? s : s.substring(0, 120) + "...";
    }
}
