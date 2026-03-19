package com.anthropic.eclipse.claude.views;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.ImageTransfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.dialogs.ElementTreeSelectionDialog;
import org.eclipse.ui.model.WorkbenchContentProvider;
import org.eclipse.ui.model.WorkbenchLabelProvider;
import org.eclipse.ui.part.ViewPart;

import com.anthropic.eclipse.claude.Activator;
import com.anthropic.eclipse.claude.cli.CliMessage;
import com.anthropic.eclipse.claude.util.JsonParser;
import com.anthropic.eclipse.claude.cli.CliProcessConfig;
import com.anthropic.eclipse.claude.cli.ClaudeCliManager;
import com.anthropic.eclipse.claude.cli.ICliStateListener;
import com.anthropic.eclipse.claude.diff.CheckpointManager;
import com.anthropic.eclipse.claude.diff.EditDecisionManager;
import com.anthropic.eclipse.claude.preferences.SecureApiKeyStore;
import com.anthropic.eclipse.claude.model.ConversationModel;
import com.anthropic.eclipse.claude.model.IConversationListener;
import com.anthropic.eclipse.claude.model.MessageBlock;
import com.anthropic.eclipse.claude.model.SessionInfo;
import com.anthropic.eclipse.claude.model.UsageInfo;
import com.anthropic.eclipse.claude.preferences.PreferenceConstants;
import com.anthropic.eclipse.claude.session.ClaudeSessionManager;
import com.anthropic.eclipse.claude.views.RulesDialog;
import com.anthropic.eclipse.claude.views.widgets.CostStatusBar;
import com.anthropic.eclipse.claude.views.widgets.MessageComposite;
import com.anthropic.eclipse.claude.views.widgets.PermissionBanner;
import com.anthropic.eclipse.claude.views.widgets.SlashCommandHandler;
import com.anthropic.eclipse.claude.views.widgets.StreamingTextWidget;
import com.anthropic.eclipse.claude.views.widgets.ThemeManager;

/**
 * Main unified conversation view - replaces both ClaudeChatView and ClaudeCodeView.
 * Provides a full Claude Code experience with streaming, tool visualization,
 * code blocks, permissions, session management, and slash commands.
 */
public class ClaudeConversationView extends ViewPart implements IConversationListener, ICliStateListener {

    public static final String ID = "com.anthropic.eclipse.claude.views.ClaudeConversationView";

    // Services
    private ClaudeCliManager cliManager;
    private ConversationModel model;
    private EditDecisionManager editDecisionManager;
    private ClaudeSessionManager sessionManager;

    // UI Components
    private ScrolledComposite scrolledMessages;
    private Composite messageContainer;
    private Text inputField;
    private CostStatusBar costBar;
    private Label connectionStatus;
    private Button sendButton;
    private Button stopButton;

    // Thinking indicator (shown while waiting for Claude's first response token)
    private Composite thinkingIndicator;
    private Label thinkingLabel;
    private int thinkingDotsState = 0;
    // Animation frames — may be swapped at runtime to show "Extended thinking…"
    private volatile String[] thinkingFrames = buildNormalThinkingFrames();

    // State
    private final Map<MessageBlock, MessageComposite> messageWidgetMap = new LinkedHashMap<>();
    // Direct toolId → ToolCallComposite map for reliable status updates
    private final Map<String, com.anthropic.eclipse.claude.views.widgets.ToolCallComposite> toolCallWidgetById = new java.util.concurrent.ConcurrentHashMap<>();
    private MessageBlock welcomeBlock; // tracked so we can dismiss it on first real message

    // Streaming timeout: if Claude doesn't respond within this many ms, show a warning.
    // 120 seconds — the model often needs >1 min to think between inter-turn tool calls
    // (e.g. after TaskOutput/TaskStop complete, before the next tool is invoked).
    private static final long STREAMING_TIMEOUT_MS = 120_000;
    private volatile long lastStreamActivityTime = 0;
    private volatile boolean streamingActive = false;
    // Tracks the error MessageBlock created when a timeout fires, so we can
    // dismiss it automatically if the stream recovers.
    private MessageBlock timeoutErrorBlock = null;

    // True after the first user message is sent — used to set dynamic tab title
    private boolean partNameSet = false;

    // Attachment state — managed by AttachmentManager (initialized after attachmentBar is created)
    private Composite attachmentBar;
    private AttachmentManager attachmentManager;

    // Cached Colors (avoid SWT resource leak)
    private Color inputBgColor;
    private Color viewBgColor;
    private Color connectedColor;
    private Color disconnectedColor;
    private Color errorColor;

    // Selection indicator
    private Label selectionIndicatorLabel;

    // Slash command autocomplete
    private org.eclipse.swt.widgets.Shell autocompletePopup;
    private org.eclipse.swt.widgets.Table autocompleteTable;

    @Override
    public void createPartControl(Composite parent) {
        // Get services
        cliManager = Activator.getDefault().getCliManager();
        editDecisionManager = Activator.getDefault().getEditDecisionManager();
        sessionManager = Activator.getDefault().getSessionManager();

        model = new ConversationModel();
        model.addListener(this);
        cliManager.addMessageListener(model);
        cliManager.addStateListener(this);

        // Make the model accessible to other components (e.g. status bar)
        Activator.getDefault().setConversationModel(model);

        initColors(parent.getDisplay());

        // Main layout
        parent.setLayout(new GridLayout(1, false));
        parent.setBackground(viewBgColor);

        createActionBar(parent);
        createMessageArea(parent);
        createInputArea(parent);
        costBar = new CostStatusBar(parent);

        // Check SHOW_COST preference
        IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();
        if (!prefs.getBoolean(PreferenceConstants.SHOW_COST)) {
            costBar.setVisible(false);
            ((GridData) costBar.getLayoutData()).exclude = true;
        }

        // Auto-start CLI if available
        autoStartCli();

        // Track editor selection to show "N lines selected" indicator
        registerSelectionListener();
    }

    // ==================== UI Creation ====================

    private void initColors(Display display) {
        // Use ThemeManager for theme-aware colors
        ThemeManager tm = ThemeManager.getInstance();
        inputBgColor = tm.getColor(tm.inputBg);
        viewBgColor = tm.getColor(tm.viewBg);
        connectedColor = tm.getColor(tm.connectedColor);
        disconnectedColor = tm.getColor(tm.disconnectedColor);
        errorColor = tm.getColor(tm.errorColor);
    }

    private void createActionBar(Composite parent) {
        Composite toolbar = new Composite(parent, SWT.NONE);
        GridLayout tbLayout = new GridLayout(8, false);
        tbLayout.marginWidth = 10;
        tbLayout.marginHeight = 6;
        tbLayout.horizontalSpacing = 6;
        toolbar.setLayout(tbLayout);
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        toolbar.setBackground(viewBgColor);

        ThemeManager tm = ThemeManager.getInstance();
        Color titleColor = tm.getColor(tm.titleColor);
        Color dimTextColor = tm.getColor(tm.dimTextColor);

        // Title
        Label title = new Label(toolbar, SWT.NONE);
        title.setText("\u2728 Claude Code");
        Font titleFont = new Font(parent.getDisplay(), tm.getUIFontName(), 13, SWT.BOLD);
        title.setFont(titleFont);
        title.setForeground(titleColor);
        title.setBackground(viewBgColor);
        title.addDisposeListener(e -> { titleFont.dispose(); titleColor.dispose(); dimTextColor.dispose(); });
        title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Connection status
        connectionStatus = new Label(toolbar, SWT.NONE);
        connectionStatus.setText("Connecting...");
        Font statusFont = new Font(parent.getDisplay(), tm.getUIFontName(), 9, SWT.NORMAL);
        connectionStatus.setFont(statusFont);
        connectionStatus.setForeground(dimTextColor);
        connectionStatus.setBackground(viewBgColor);
        connectionStatus.addDisposeListener(e -> statusFont.dispose());

        // Compact icon buttons
        Button newSessionBtn = new Button(toolbar, SWT.PUSH | SWT.FLAT);
        newSessionBtn.setText("+");
        setTooltip(newSessionBtn, "New session");
        newSessionBtn.addListener(SWT.Selection, e -> startNewSession());

        Button resumeBtn = new Button(toolbar, SWT.PUSH | SWT.FLAT);
        resumeBtn.setText("\u21BA");
        setTooltip(resumeBtn, "Resume session");
        resumeBtn.addListener(SWT.Selection, e -> showResumeDialog());

        stopButton = new Button(toolbar, SWT.PUSH | SWT.FLAT);
        stopButton.setText("\u25A0");
        setTooltip(stopButton, "Stop current query");
        stopButton.setEnabled(false);
        stopButton.addListener(SWT.Selection, e -> {
            cancelStreamingTimeout();
            cliManager.stop();
            stopButton.setEnabled(false);
        });

        Button clearBtn = new Button(toolbar, SWT.PUSH | SWT.FLAT);
        clearBtn.setText("\u2715");
        setTooltip(clearBtn, "Clear conversation");
        clearBtn.addListener(SWT.Selection, e -> clearConversation());

        Button settingsBtn = new Button(toolbar, SWT.PUSH | SWT.FLAT);
        settingsBtn.setText("\u2699");
        setTooltip(settingsBtn, "Settings & Configuration");
        settingsBtn.addListener(SWT.Selection, e -> showSettingsMenu(settingsBtn));

        Button newWindowBtn = new Button(toolbar, SWT.PUSH | SWT.FLAT);
        newWindowBtn.setText("\u29C9");  // ⧉ "two overlapping squares"
        setTooltip(newWindowBtn, "New conversation window");
        newWindowBtn.addListener(SWT.Selection, e -> openNewConversationWindow());
    }

    /** Open a new independent Claude Code conversation window (tab). */
    private void openNewConversationWindow() {
        try {
            String secondaryId = String.valueOf(System.currentTimeMillis());
            getSite().getPage().showView(ClaudeConversationView.ID, secondaryId,
                    org.eclipse.ui.IWorkbenchPage.VIEW_ACTIVATE);
        } catch (Exception ex) {
            Activator.logError("Could not open new conversation window", ex);
        }
    }

    /**
     * Attaches a reliable tooltip to a control using MouseTrackListener.
     * Replaces setToolTipText() which on macOS SWT only triggers at specific
     * pixel locations on small flat buttons.
     */
    private static void setTooltip(org.eclipse.swt.widgets.Control control, String text) {
        org.eclipse.swt.widgets.ToolTip tip =
            new org.eclipse.swt.widgets.ToolTip(control.getShell(), SWT.NONE);
        tip.setMessage(text);
        control.addMouseTrackListener(new org.eclipse.swt.events.MouseTrackAdapter() {
            @Override
            public void mouseHover(org.eclipse.swt.events.MouseEvent e) {
                if (control.isDisposed() || tip.isDisposed()) return;
                org.eclipse.swt.graphics.Point loc = control.toDisplay(e.x, e.y);
                tip.setLocation(loc.x + 12, loc.y + 16);
                tip.setVisible(true);
            }
            @Override
            public void mouseExit(org.eclipse.swt.events.MouseEvent e) {
                if (!tip.isDisposed()) tip.setVisible(false);
            }
        });
        control.addDisposeListener(e -> { if (!tip.isDisposed()) tip.dispose(); });
    }

    private void showSettingsMenu(Button anchor) {
        Menu menu = new Menu(anchor);

        MenuItem prefsItem = new MenuItem(menu, SWT.PUSH);
        prefsItem.setText("Preferences...");
        prefsItem.addListener(SWT.Selection, e -> {
            org.eclipse.ui.dialogs.PreferencesUtil.createPreferenceDialogOn(
                getViewSite().getShell(),
                "com.anthropic.eclipse.claude.preferences.ClaudePreferencePage",
                null, null).open();
        });

        new MenuItem(menu, SWT.SEPARATOR);

        MenuItem rulesItem = new MenuItem(menu, SWT.PUSH);
        rulesItem.setText("Rules && Permissions  (/rules)");
        rulesItem.addListener(SWT.Selection, e -> showRulesDialog());

        MenuItem mcpItem = new MenuItem(menu, SWT.PUSH);
        mcpItem.setText("MCP Servers  (/mcp)");
        mcpItem.addListener(SWT.Selection, e -> showMcpDialog());

        MenuItem hooksItem = new MenuItem(menu, SWT.PUSH);
        hooksItem.setText("Hooks  (/hooks)");
        hooksItem.addListener(SWT.Selection, e -> showHooksDialog());

        MenuItem memoryItem = new MenuItem(menu, SWT.PUSH);
        memoryItem.setText("Memory && Context  (/memory)");
        memoryItem.addListener(SWT.Selection, e -> showMemoryDialog());

        MenuItem skillsItem = new MenuItem(menu, SWT.PUSH);
        skillsItem.setText("Skills && Plugins  (/skills)");
        skillsItem.addListener(SWT.Selection, e -> showSkillsDialog());

        new MenuItem(menu, SWT.SEPARATOR);

        MenuItem historyItem = new MenuItem(menu, SWT.PUSH);
        historyItem.setText("Session History  (/history)");
        historyItem.addListener(SWT.Selection, e -> showHistoryDialog());

        org.eclipse.swt.graphics.Point loc = anchor.getParent().toDisplay(
            anchor.getLocation().x,
            anchor.getLocation().y + anchor.getSize().y);
        menu.setLocation(loc);
        menu.setVisible(true);
    }

    private void createMessageArea(Composite parent) {
        scrolledMessages = new ScrolledComposite(parent, SWT.V_SCROLL);
        scrolledMessages.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        scrolledMessages.setExpandHorizontal(true);
        scrolledMessages.setExpandVertical(true);
        scrolledMessages.setBackground(viewBgColor);

        messageContainer = new Composite(scrolledMessages, SWT.NONE);
        GridLayout mcLayout = new GridLayout(1, false);
        mcLayout.marginWidth = 0;
        mcLayout.marginHeight = 8;
        mcLayout.verticalSpacing = 8;
        messageContainer.setLayout(mcLayout);
        messageContainer.setBackground(viewBgColor);

        scrolledMessages.setContent(messageContainer);

        // Re-layout on resize so text wraps at the new panel width
        scrolledMessages.addControlListener(new org.eclipse.swt.events.ControlAdapter() {
            @Override
            public void controlResized(org.eclipse.swt.events.ControlEvent e) {
                if (!messageContainer.isDisposed()) {
                    int cw = scrolledMessages.getClientArea().width;
                    messageContainer.layout(true, true);
                    scrolledMessages.setMinSize(
                        messageContainer.computeSize(cw > 0 ? cw : SWT.DEFAULT, SWT.DEFAULT));
                }
            }
        });

        // Add welcome message
        addWelcomeMessage();
    }

    private void createInputArea(Composite parent) {
        Composite inputContainer = new Composite(parent, SWT.NONE);
        GridLayout icLayout = new GridLayout(1, false);
        icLayout.marginWidth = 10;
        icLayout.marginHeight = 6;
        icLayout.verticalSpacing = 4;
        inputContainer.setLayout(icLayout);
        inputContainer.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
        inputContainer.setBackground(viewBgColor);

        // Attachment chips bar - shown only when files/images are attached
        attachmentBar = new Composite(inputContainer, SWT.NONE);
        RowLayout rowLayout = new RowLayout(SWT.HORIZONTAL);
        rowLayout.wrap = true;
        rowLayout.spacing = 4;
        rowLayout.marginWidth = 0;
        rowLayout.marginHeight = 2;
        attachmentBar.setLayout(rowLayout);
        GridData abGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        abGd.exclude = true;
        attachmentBar.setLayoutData(abGd);
        attachmentBar.setVisible(false);
        attachmentBar.setBackground(viewBgColor);

        // Input box with border - VS Code style
        // NOTE: attachmentManager is initialized below, after inputField is created
        Composite inputBox = new Composite(inputContainer, SWT.BORDER);
        GridLayout boxLayout = new GridLayout(1, false);
        boxLayout.marginWidth = 6;
        boxLayout.marginHeight = 4;
        boxLayout.verticalSpacing = 4;
        inputBox.setLayout(boxLayout);
        inputBox.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
        inputBox.setBackground(inputBgColor);

        // Row 1: Text input area (full width)
        inputField = new Text(inputBox, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        GridData inputGd = new GridData(SWT.FILL, SWT.FILL, true, false);
        inputGd.heightHint = 48;
        inputField.setLayoutData(inputGd);
        inputField.setMessage("Ask Claude... (Enter = send, Shift+Enter = newline, / = commands)");
        inputField.setBackground(inputBgColor);
        ThemeManager tmInput = ThemeManager.getInstance();
        Color inputTextColor = tmInput.getColor(tmInput.inputText);
        inputField.setForeground(inputTextColor);
        inputField.addDisposeListener(e -> inputTextColor.dispose());

        // Initialize AttachmentManager now that both attachmentBar and inputField exist
        attachmentManager = new AttachmentManager(attachmentBar, parent.getShell(),
            inputField, msg -> showError(msg));

        // Row 2: Button bar inside input box [📎 attach] [/ commands] --- spacer --- [↑ send]
        Composite buttonBar = new Composite(inputBox, SWT.NONE);
        GridLayout bbLayout = new GridLayout(4, false);
        bbLayout.marginWidth = 0;
        bbLayout.marginHeight = 0;
        bbLayout.horizontalSpacing = 4;
        buttonBar.setLayout(bbLayout);
        buttonBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        buttonBar.setBackground(inputBgColor);

        // Attach button
        Button attachBtn = new Button(buttonBar, SWT.FLAT);
        attachBtn.setText("\uD83D\uDCCE");
        attachBtn.setToolTipText("Add file or image to context (Ctrl+@)");
        attachBtn.addListener(SWT.Selection, e -> showAttachMenu(attachBtn));

        // Slash commands button
        Button slashBtn = new Button(buttonBar, SWT.FLAT);
        slashBtn.setText("/");
        slashBtn.setToolTipText("Slash commands");
        slashBtn.addListener(SWT.Selection, e -> {
            inputField.setText("/");
            inputField.setSelection(1);
            inputField.setFocus();
            showAutocomplete("/");
        });

        // Spacer
        Label spacer = new Label(buttonBar, SWT.NONE);
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        spacer.setBackground(inputBgColor);

        // Send button (arrow up in colored circle, like VS Code)
        sendButton = new Button(buttonBar, SWT.PUSH);
        sendButton.setText("\u2191");  // ↑ arrow
        sendButton.setToolTipText("Send message (Enter)");
        sendButton.addListener(SWT.Selection, e -> handleInput());

        // Context info line below input (permission mode + active file)
        Composite contextLine = new Composite(inputContainer, SWT.NONE);
        GridLayout clLayout = new GridLayout(3, false);
        clLayout.marginWidth = 2;
        clLayout.marginHeight = 0;
        clLayout.horizontalSpacing = 10;
        contextLine.setLayout(clLayout);
        contextLine.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        contextLine.setBackground(viewBgColor);

        ThemeManager tmCtx = ThemeManager.getInstance();
        Color hintColor = tmCtx.getColor(tmCtx.hintText);
        Font hintFont = new Font(parent.getDisplay(), tmCtx.getUIFontName(), 9, SWT.NORMAL);

        Label permLabel = new Label(contextLine, SWT.NONE);
        IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();
        String perm = prefs.getString(PreferenceConstants.PERMISSION_MODE);
        permLabel.setText("\uD83D\uDD12 " + (perm != null && !perm.isBlank() ? perm : "acceptEdits"));
        permLabel.setForeground(hintColor);
        permLabel.setBackground(viewBgColor);
        permLabel.setFont(hintFont);

        // Selection indicator — shows "N lines selected" when editor has a text selection
        selectionIndicatorLabel = new Label(contextLine, SWT.NONE);
        selectionIndicatorLabel.setText("");
        selectionIndicatorLabel.setForeground(hintColor);
        selectionIndicatorLabel.setBackground(viewBgColor);
        selectionIndicatorLabel.setFont(hintFont);

        Label fileContextLabel = new Label(contextLine, SWT.NONE);
        fileContextLabel.setText("");
        fileContextLabel.setForeground(hintColor);
        fileContextLabel.setBackground(viewBgColor);
        fileContextLabel.setFont(hintFont);
        fileContextLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Shortcut hint — updated by preference
        Label shortcutLabel = new Label(contextLine, SWT.NONE);
        IPreferenceStore inputPrefs = Activator.getDefault().getPreferenceStore();
        boolean enterToSendDefault = inputPrefs.getBoolean(PreferenceConstants.ENTER_TO_SEND);
        shortcutLabel.setText(enterToSendDefault
                ? "Enter \u2191 | Shift+Enter \u21B5"
                : "Shift+Enter \u2191 | Enter \u21B5");
        shortcutLabel.setForeground(hintColor);
        shortcutLabel.setBackground(viewBgColor);
        shortcutLabel.setFont(hintFont);

        contextLine.addDisposeListener(e -> { hintColor.dispose(); hintFont.dispose(); });
        inputField.addListener(SWT.KeyDown, e -> {
            if (e.keyCode == SWT.CR || e.keyCode == SWT.LF) {
                boolean enterToSend = Activator.getDefault().getPreferenceStore()
                        .getBoolean(PreferenceConstants.ENTER_TO_SEND);
                boolean shiftDown = (e.stateMask & SWT.SHIFT) != 0;
                boolean shouldSend = enterToSend ? !shiftDown : shiftDown;
                if (!shouldSend) return;
                // Don't send if autocomplete is open - Enter there selects the command
                if (autocompletePopup != null && !autocompletePopup.isDisposed()
                        && autocompletePopup.isVisible()) {
                    return;
                }
                e.doit = false;
                handleInput();
            }
        });

        // @mention support - trigger file attachment when @ is typed
        inputField.addListener(SWT.Verify, e -> {
            if ("@".equals(e.text)) {
                // Schedule file picker to run after the @ is inserted
                Display.getDefault().asyncExec(() -> attachmentManager.handleAtMention());
            }
        });

        // Slash command autocomplete - show popup when input starts with /
        // Also auto-detect RTL for Hebrew/Arabic input
        inputField.addListener(SWT.Modify, e -> {
            String text = inputField.getText();
            if (text.startsWith("/") && !text.contains(" ") && !text.contains("\n")) {
                showAutocomplete(text);
            } else {
                dismissAutocomplete();
            }
            // RTL auto-detection: update both orientation and text direction on every change
            // setTextDirection is needed on macOS where setOrientation alone doesn't reflow
            int orientation = StreamingTextWidget.detectOrientation(text);
            inputField.setOrientation(orientation);
            inputField.setTextDirection(orientation);
            inputField.redraw();
        });

        // Handle keyboard navigation in autocomplete popup
        inputField.addListener(SWT.KeyDown, e2 -> {
            if (autocompletePopup != null && autocompletePopup.isVisible()) {
                if (e2.keyCode == SWT.ARROW_DOWN) {
                    int idx = autocompleteTable.getSelectionIndex();
                    if (idx < autocompleteTable.getItemCount() - 1) {
                        autocompleteTable.setSelection(idx + 1);
                    }
                    e2.doit = false;
                } else if (e2.keyCode == SWT.ARROW_UP) {
                    int idx = autocompleteTable.getSelectionIndex();
                    if (idx > 0) {
                        autocompleteTable.setSelection(idx - 1);
                    }
                    e2.doit = false;
                } else if (e2.keyCode == SWT.TAB || (e2.keyCode == SWT.CR && autocompleteTable.getSelectionIndex() >= 0)) {
                    int idx = autocompleteTable.getSelectionIndex();
                    if (idx >= 0) {
                        String selected = autocompleteTable.getItem(idx).getText(0);
                        dismissAutocomplete();
                        executeAutocompleteSelection(selected);
                        e2.doit = false;
                    }
                } else if (e2.keyCode == SWT.ESC) {
                    dismissAutocomplete();
                    e2.doit = false;
                }
            }
        });

        // Dismiss autocomplete when input loses focus
        inputField.addListener(SWT.FocusOut, e -> {
            Display.getDefault().timerExec(200, () -> dismissAutocomplete());
        });
    }

    // ==================== Message Handling ====================

    private void handleInput() {
        String text = inputField.getText().trim();
        boolean hasAttachments = attachmentManager != null && attachmentManager.hasAttachments();
        if (text.isEmpty() && !hasAttachments) return;

        dismissAutocomplete();
        inputField.setText("");

        // Check for slash commands (only when no attachments)
        if (!hasAttachments && text.startsWith("/")) {
            if (handleSlashCommand(text)) return;
        }

        // Ensure CLI is running
        if (!cliManager.isRunning()) {
            autoStartCli();
            if (!cliManager.isRunning()) {
                showError("Claude CLI is not running. Please check your configuration.");
                return;
            }
        }

        // Build message text - prepend active editor context + embed file contents inline
        StringBuilder fullMessage = new StringBuilder();

        // Inject active editor context so Claude knows which file the user is looking at
        String editorContext = getActiveEditorContext();
        if (editorContext != null && !editorContext.isEmpty()) {
            fullMessage.append(editorContext).append("\n\n");
        }

        if (attachmentManager != null) {
            fullMessage.append(attachmentManager.buildFileContext());
        }
        if (!text.isEmpty()) {
            fullMessage.append(text);
        }
        String finalText = fullMessage.toString().trim();

        // Collect images and build display text BEFORE clearing
        List<byte[]> images = attachmentManager != null ? new ArrayList<>(attachmentManager.getImages()) : new ArrayList<>();
        String displayText = buildDisplayText(text);

        // Clear attachment state
        if (attachmentManager != null) attachmentManager.clearAll();

        // Show in UI what was sent
        model.addUserMessage(displayText.isEmpty() ? finalText : displayText);

        // Update tab title with first user message (only once per conversation)
        if (!partNameSet && !text.trim().isEmpty()) {
            partNameSet = true;
            String tabTitle = text.trim();
            if (tabTitle.length() > 30) tabTitle = tabTitle.substring(0, 30) + "\u2026";
            setPartName(tabTitle);
        }

        // Send to CLI
        if (!images.isEmpty()) {
            cliManager.sendRawNdjson(CliMessage.createUserInputJsonRich(finalText, images));
        } else {
            cliManager.sendMessage(finalText);
        }

        stopButton.setEnabled(true);
        costBar.setStatus("Streaming...");
        // Queue AFTER the user message asyncExec so indicator appears below the user message
        Display.getDefault().asyncExec(this::showThinkingIndicator);
    }

    private boolean handleSlashCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String command = parts[0].toLowerCase();

        switch (command) {
            case "/new":
                startNewSession();
                return true;
            case "/clear":
                clearConversation();
                return true;
            case "/cost":
                showCostSummary();
                return true;
            case "/help":
                showHelp();
                return true;
            case "/stop":
                cliManager.stop();
                return true;
            case "/resume":
                showResumeDialog();
                return true;
            case "/model":
                if (parts.length > 1 && !parts[1].isBlank()) {
                    // Direct model switch: /model claude-sonnet-4-5
                    switchToModel(parts[1].trim());
                } else {
                    showModelInfo();
                }
                return true;
            case "/rules":
                showRulesDialog();
                return true;
            case "/mcp":
                showMcpDialog();
                return true;
            case "/hooks":
                showHooksDialog();
                return true;
            case "/memory":
                showMemoryDialog();
                return true;
            case "/history":
                showHistoryDialog();
                return true;
            case "/skills":
                showSkillsDialog();
                return true;
            default:
                // Forward unknown slash commands (like /commit, /review-pr) to CLI
                return false;
        }
    }

    // ==================== Attachment Handling ====================

    private void showAttachMenu(Button anchor) {
        Menu menu = new Menu(anchor);

        MenuItem fileItem = new MenuItem(menu, SWT.PUSH);
        fileItem.setText("Add workspace file...");
        fileItem.addListener(SWT.Selection, e -> attachmentManager.attachFile());

        MenuItem imageItem = new MenuItem(menu, SWT.PUSH);
        imageItem.setText("Paste image from clipboard");
        imageItem.addListener(SWT.Selection, e -> attachmentManager.pasteImage());

        org.eclipse.swt.graphics.Point loc = anchor.getParent().toDisplay(
            anchor.getLocation().x,
            anchor.getLocation().y + anchor.getSize().y
        );
        menu.setLocation(loc);
        menu.setVisible(true);
    }

    // ==================== Slash Command Autocomplete ====================

    /**
     * Show or update the slash command autocomplete popup.
     */
    private void showAutocomplete(String prefix) {
        List<SlashCommandHandler.CommandInfo> allCommands = SlashCommandHandler.getAllCommands();

        // Build list of rows: either matching commands, or model sub-options for /model
        List<String[]> rows = new ArrayList<>(); // [command, description]

        String lowerPrefix = prefix.toLowerCase().trim();

        if ("/model".equals(lowerPrefix)) {
            // Show model sub-options when user typed exactly "/model"
            SessionInfo info = model.getSessionInfo();
            String currentModel = info != null && info.getModel() != null ? info.getModel() : "";
            for (String[] m : AVAILABLE_MODELS) {
                String active = mapModelToShortName(currentModel).equals(mapModelToShortName(m[0]))
                    ? " \u2713" : "";
                rows.add(new String[]{"/model " + m[0], m[1] + active});
            }
        } else {
            for (SlashCommandHandler.CommandInfo cmd : allCommands) {
                if (cmd.name.startsWith(lowerPrefix)) {
                    rows.add(new String[]{cmd.name, cmd.description});
                }
            }
        }

        if (rows.isEmpty()) {
            dismissAutocomplete();
            return;
        }

        if (autocompletePopup == null || autocompletePopup.isDisposed()) {
            autocompletePopup = new org.eclipse.swt.widgets.Shell(
                inputField.getShell(), SWT.NO_TRIM | SWT.ON_TOP);
            autocompletePopup.setLayout(new GridLayout(1, false));

            autocompleteTable = new org.eclipse.swt.widgets.Table(
                autocompletePopup, SWT.SINGLE | SWT.FULL_SELECTION);
            autocompleteTable.setHeaderVisible(false);
            autocompleteTable.setLinesVisible(false);
            autocompleteTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

            org.eclipse.swt.widgets.TableColumn cmdCol = new org.eclipse.swt.widgets.TableColumn(
                autocompleteTable, SWT.LEFT);
            cmdCol.setWidth(150);

            org.eclipse.swt.widgets.TableColumn descCol = new org.eclipse.swt.widgets.TableColumn(
                autocompleteTable, SWT.LEFT);
            descCol.setWidth(250);

            // Handle selection via mouse double-click
            autocompleteTable.addListener(SWT.DefaultSelection, e -> {
                int idx = autocompleteTable.getSelectionIndex();
                if (idx >= 0) {
                    String selected = autocompleteTable.getItem(idx).getText(0);
                    dismissAutocomplete();
                    executeAutocompleteSelection(selected);
                }
            });

            // Handle single click - execute immediately
            autocompleteTable.addListener(SWT.Selection, e -> {
                int idx = autocompleteTable.getSelectionIndex();
                if (idx >= 0) {
                    String selected = autocompleteTable.getItem(idx).getText(0);
                    dismissAutocomplete();
                    executeAutocompleteSelection(selected);
                }
            });
        }

        // Update table items
        autocompleteTable.removeAll();
        for (String[] row : rows) {
            org.eclipse.swt.widgets.TableItem item =
                new org.eclipse.swt.widgets.TableItem(autocompleteTable, SWT.NONE);
            item.setText(0, row[0]);
            item.setText(1, row[1]);
        }

        // Auto-select first item
        if (autocompleteTable.getItemCount() > 0) {
            autocompleteTable.setSelection(0);
        }

        // Position popup above the input field
        org.eclipse.swt.graphics.Point inputLoc = inputField.toDisplay(0, 0);
        int popupHeight = Math.min(rows.size() * 24 + 4, 200);
        autocompletePopup.setBounds(inputLoc.x, inputLoc.y - popupHeight,
            inputField.getSize().x, popupHeight);
        autocompletePopup.setVisible(true);
    }

    /**
     * Dismiss the slash command autocomplete popup.
     */
    private void dismissAutocomplete() {
        if (autocompletePopup != null && !autocompletePopup.isDisposed()) {
            autocompletePopup.setVisible(false);
            autocompletePopup.dispose();
            autocompletePopup = null;
            autocompleteTable = null;
        }
    }

    /**
     * Execute a selected autocomplete item.
     * - /model → show model sub-options
     * - /model <id> → switch model directly
     * - Local commands (/new, /clear, etc.) → execute immediately
     * - CLI commands (/commit, /review-pr, etc.) → put in input for optional args
     */
    private void executeAutocompleteSelection(String selected) {
        String trimmed = selected.trim();

        // "/model" without a model id → show sub-options
        if ("/model".equals(trimmed)) {
            inputField.setText("/model");
            inputField.setSelection(6);
            showAutocomplete("/model");
            return;
        }

        // Local commands → execute immediately
        if (SlashCommandHandler.isLocalCommand(trimmed.split("\\s+")[0])) {
            inputField.setText(trimmed);
            handleInput();
            return;
        }

        // CLI-forwarded commands → put in input so user can add arguments
        inputField.setText(trimmed + " ");
        inputField.setSelection(inputField.getText().length());
        inputField.setFocus();
    }

    private String buildDisplayText(String userText) {
        StringBuilder sb = new StringBuilder();
        if (attachmentManager != null) {
            sb.append(attachmentManager.buildDisplayLabel());
        }
        if (!userText.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(userText);
        }
        return sb.toString().trim();
    }

    // ==================== Public API for Handlers ====================

    /**
     * Public method for external handlers to send code with a prompt.
     */
    public void sendCode(String prompt, String code) {
        String fullMessage = prompt + "\n\n```\n" + code + "\n```";
        model.addUserMessage(fullMessage);

        if (!cliManager.isRunning()) {
            autoStartCli();
        }
        if (cliManager.isRunning()) {
            cliManager.sendMessage(fullMessage);
            stopButton.setEnabled(true);
            costBar.setStatus("Streaming...");
        }
    }

    /**
     * Public method to start a new session (called by NewSessionHandler).
     */
    public void startNewSession() {
        // Save current session before starting new one
        saveCurrentSession();

        // Stop current process
        if (cliManager.isRunning()) {
            cliManager.stop();
        }

        // Clear model
        model.clear();

        // Clear checkpoints from the previous session
        Activator.getDefault().getCheckpointManager().clearCheckpoints();

        // Rebuild model and wire up again
        ConversationModel oldModel = model;
        model = new ConversationModel();
        model.addListener(this);
        cliManager.removeMessageListener(oldModel);
        cliManager.addMessageListener(model);
        Activator.getDefault().setConversationModel(model);

        // Reset tab title for the new session
        partNameSet = false;
        setPartName("Claude Code");

        // Start fresh
        autoStartCli();
    }

    /**
     * Public method to resume a session by ID (called by ResumeSessionHandler).
     */
    public void resumeSession(String sessionId) {
        // Save current session
        saveCurrentSession();

        // Stop current process
        if (cliManager.isRunning()) {
            cliManager.stop();
        }

        // Clear model (fires onConversationCleared → clears UI + adds welcome message)
        model.clear();

        // Rebuild model
        ConversationModel oldModel = model;
        model = new ConversationModel();
        model.addListener(this);
        cliManager.removeMessageListener(oldModel);
        cliManager.addMessageListener(model);

        // Start with resume flag
        String cliPath = cliManager.getCliPath();
        if (cliPath == null) {
            showError("CLI not found.");
            return;
        }
        String workDir = getDefaultWorkingDirectory();
        IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();
        String cliModel = mapModelName(prefs.getString(PreferenceConstants.MODEL));

        try {
            CliProcessConfig config = new CliProcessConfig.Builder(cliPath, workDir)
                .model(cliModel)
                .resumeSessionId(sessionId)
                .build();
            cliManager.start(config);
        } catch (ClaudeCliManager.CliException e) {
            showError("Failed to resume session: " + e.getMessage());
            return;
        }

        // Load conversation history from JSONL in background
        final ConversationModel modelRef = model;
        final String sessionIdFinal = sessionId;
        new Thread(() -> {
            List<MessageBlock> history = loadSessionHistoryFromJsonl(sessionIdFinal);
            if (!history.isEmpty()) {
                // Clear the welcome message widget on the UI thread first,
                // then queue the history events (which also go through asyncExec)
                Display.getDefault().asyncExec(() -> {
                    for (MessageComposite widget : new ArrayList<>(messageWidgetMap.values())) {
                        if (!widget.isDisposed()) widget.dispose();
                    }
                    messageWidgetMap.clear();
                    toolCallWidgetById.clear();
                    welcomeBlock = null; // will be re-shown only if history is empty
                    if (messageContainer != null && !messageContainer.isDisposed()) {
                        messageContainer.layout(true, true);
                    }
                });
                // loadHistory fires events → listeners do asyncExec → queued after the clear above
                modelRef.loadHistory(history);
            }
        }, "Claude-History-Loader").start();
    }

    /**
     * Reads ~/.claude/projects/{any}/SESSION_ID.jsonl and parses conversation history.
     * Returns MessageBlocks for plain user messages and final (stop_reason != null)
     * assistant messages. Skips intermediate streaming snapshots and tool-result turns.
     */
    @SuppressWarnings("unchecked")
    private List<MessageBlock> loadSessionHistoryFromJsonl(String sessionId) {
        List<MessageBlock> blocks = new ArrayList<>();
        try {
            File claudeProjects = new File(System.getProperty("user.home") + "/.claude/projects");
            if (!claudeProjects.exists()) return blocks;

            // Search across all project directories for the session file
            File jsonlFile = null;
            File[] projectDirs = claudeProjects.listFiles(File::isDirectory);
            if (projectDirs == null) return blocks;
            for (File dir : projectDirs) {
                File candidate = new File(dir, sessionId + ".jsonl");
                if (candidate.exists()) {
                    jsonlFile = candidate;
                    break;
                }
            }
            if (jsonlFile == null) return blocks;

            List<String> lines = Files.readAllLines(jsonlFile.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                try {
                    Map<String, Object> obj = JsonParser.parseObject(line);
                    String type = JsonParser.getString(obj, "type");
                    Map<String, Object> msg = JsonParser.getMap(obj, "message");
                    if (msg == null) continue;

                    String role = JsonParser.getString(msg, "role");

                    if ("user".equals(type) && "user".equals(role)) {
                        // User turn: skip tool_result arrays, keep plain text
                        Object content = msg.get("content");
                        String text = null;
                        if (content instanceof String) {
                            text = (String) content;
                        } else if (content instanceof List) {
                            List<Object> contentList = (List<Object>) content;
                            if (contentList.isEmpty()) continue;
                            // Skip if this is a tool_result array
                            Object first = contentList.get(0);
                            if (first instanceof Map &&
                                    "tool_result".equals(JsonParser.getString((Map<String, Object>) first, "type"))) {
                                continue;
                            }
                            // Extract text items
                            StringBuilder sb = new StringBuilder();
                            for (Object item : contentList) {
                                if (item instanceof Map) {
                                    Map<String, Object> itemMap = (Map<String, Object>) item;
                                    if ("text".equals(JsonParser.getString(itemMap, "type"))) {
                                        String t = JsonParser.getString(itemMap, "text");
                                        if (t != null) sb.append(t);
                                    }
                                }
                            }
                            text = sb.toString();
                        }
                        if (text != null && !text.trim().isEmpty()) {
                            MessageBlock block = new MessageBlock(MessageBlock.Role.USER);
                            MessageBlock.TextSegment seg = new MessageBlock.TextSegment();
                            seg.appendText(text);
                            block.addSegment(seg);
                            blocks.add(block);
                        }

                    } else if ("assistant".equals(type) && "assistant".equals(role)) {
                        // Only include final assistant messages (stop_reason is set)
                        String stopReason = JsonParser.getString(msg, "stop_reason");
                        if (stopReason == null) continue;

                        Object content = msg.get("content");
                        if (!(content instanceof List)) continue;
                        List<Object> contentList = (List<Object>) content;

                        MessageBlock block = new MessageBlock(MessageBlock.Role.ASSISTANT);
                        for (Object item : contentList) {
                            if (!(item instanceof Map)) continue;
                            Map<String, Object> itemMap = (Map<String, Object>) item;
                            String itemType = JsonParser.getString(itemMap, "type");

                            if ("text".equals(itemType)) {
                                String text = JsonParser.getString(itemMap, "text");
                                if (text != null && !text.isEmpty()) {
                                    MessageBlock.TextSegment seg = new MessageBlock.TextSegment();
                                    seg.appendText(text);
                                    block.addSegment(seg);
                                }
                            } else if ("tool_use".equals(itemType)) {
                                MessageBlock.ToolCallSegment toolSeg = new MessageBlock.ToolCallSegment();
                                toolSeg.setToolId(JsonParser.getString(itemMap, "id"));
                                toolSeg.setToolName(JsonParser.getString(itemMap, "name"));
                                Object inputObj = itemMap.get("input");
                                if (inputObj != null) {
                                    toolSeg.setInput(JsonParser.toJson(inputObj));
                                }
                                toolSeg.setStatus(MessageBlock.ToolStatus.COMPLETED);
                                block.addSegment(toolSeg);
                            }
                            // Skip "thinking" segments
                        }

                        if (!block.getSegments().isEmpty()) {
                            blocks.add(block);
                        }
                    }
                } catch (Exception lineEx) {
                    // Skip invalid lines silently (e.g. queue-operation entries)
                }
            }
        } catch (Exception e) {
            Activator.logError("Failed to load session history for " + sessionId + ": " + e.getMessage(), e);
        }
        return blocks;
    }

    // ==================== IConversationListener Implementation ====================

    @Override
    public void onSessionInitialized(SessionInfo info) {
        // Register this session with the session manager so save works
        if (sessionManager != null && sessionManager.getCurrentSession() == null) {
            String workDir = info.getWorkingDirectory() != null
                ? info.getWorkingDirectory() : getDefaultWorkingDirectory();
            sessionManager.startNewSession(workDir);
        }

        asyncExec(() -> {
            costBar.updateSession(info);
            connectionStatus.setText("\u2713 Connected (" + (info.getModel() != null ? info.getModel() : "claude") + ")");
            connectionStatus.setForeground(connectedColor);
        });
    }

    @Override
    public void onUserMessageAdded(MessageBlock block) {
        asyncExec(() -> {
            // Hide welcome message on first real interaction
            dismissWelcomeMessage();
            MessageComposite widget = new MessageComposite(messageContainer, block);
            messageWidgetMap.put(block, widget);
            scrollToBottom();
        });
    }

    @Override
    public void onExtendedThinkingStarted() {
        touchStreamActivity(); // thinking is activity — reset timeout clock
        asyncExec(() -> {
            // Switch the thinking indicator to "🧠 Extended thinking..." frames.
            // If the indicator isn't showing yet (shouldn't happen normally) start it now.
            if (thinkingIndicator == null || thinkingIndicator.isDisposed()) {
                showThinkingIndicator();
            }
            thinkingFrames = buildExtendedThinkingFrames();
            // Update label text immediately so the change is visible without waiting for next tick
            if (thinkingLabel != null && !thinkingLabel.isDisposed()) {
                thinkingLabel.setText(thinkingFrames[0]);
            }
        });
    }

    @Override
    public void onExtendedThinkingEnded() {
        // Nothing to do — onAssistantMessageStarted fires immediately after this
        // and will call hideThinkingIndicator(). No UI change needed here.
    }

    @Override
    public void onAssistantMessageStarted(MessageBlock block) {
        touchStreamActivity(); // streaming started — reset timeout clock
        asyncExec(() -> {
            hideThinkingIndicator();
            MessageComposite widget = new MessageComposite(messageContainer, block);
            messageWidgetMap.put(block, widget);
            scrollToBottom();
        });
    }

    @Override
    public void onStreamingTextAppended(MessageBlock block, String delta) {
        touchStreamActivity(); // each text token proves the stream is alive
        // If streaming display is disabled, skip real-time updates;
        // the full text will be rendered on onAssistantMessageCompleted
        boolean showStreaming = true;
        try {
            showStreaming = Activator.getDefault().getPreferenceStore()
                .getBoolean(PreferenceConstants.SHOW_STREAMING);
        } catch (Exception ignored) {}
        if (!showStreaming) return;

        asyncExec(() -> {
            MessageComposite widget = messageWidgetMap.get(block);
            if (widget != null && !widget.isDisposed()) {
                widget.appendStreamingText(delta);
                scrollToBottom();
            }
        });
    }

    @Override
    public void onToolCallStarted(MessageBlock block, MessageBlock.ToolCallSegment toolCall) {
        touchStreamActivity(); // tool started — stream is alive
        // Snapshot files about to be modified and auto-save dirty editors
        handleToolCallPrepare(toolCall);

        asyncExec(() -> {
            hideThinkingIndicator();
            MessageComposite widget = messageWidgetMap.get(block);
            if (widget != null && !widget.isDisposed()) {
                com.anthropic.eclipse.claude.views.widgets.ToolCallComposite tcw =
                    widget.addToolCallWidget(toolCall);
                // Register in direct map for reliable lookup by toolId
                if (tcw != null && toolCall.getToolId() != null) {
                    toolCallWidgetById.put(toolCall.getToolId(), tcw);
                }
                costBar.setStatus("Tool: " + toolCall.getDisplayName());
                scrollToBottom();
            }
        });
    }

    @Override
    public void onToolCallInputDelta(MessageBlock block, MessageBlock.ToolCallSegment toolCall, String delta) {
        // Tool input is streaming - update the tool call widget to show live input.
        // Skip if the tool already completed (bg thread may have set COMPLETED/FAILED
        // while these asyncExec items were queued — don't overwrite with stale RUNNING).
        if (toolCall.getStatus() != MessageBlock.ToolStatus.RUNNING) return;
        asyncExec(() -> {
            if (toolCall.getStatus() != MessageBlock.ToolStatus.RUNNING) return;
            MessageComposite widget = messageWidgetMap.get(block);
            if (widget != null && !widget.isDisposed()) {
                widget.updateToolCall(toolCall);
            }
        });
    }

    @Override
    public void onToolCallInputComplete(MessageBlock block, MessageBlock.ToolCallSegment toolCall) {
        // Full tool input is available now, BEFORE the tool executes.
        // Snapshot the target file so we can show original vs modified in diff/revert.
        String toolName = toolCall.getToolName();
        if (toolName != null && ("Write".equals(toolName) || "Edit".equals(toolName)
                || "MultiEdit".equals(toolName))) {
            String filePath = extractFilePath(toolCall.getInput());
            if (filePath != null) {
                Activator.getDefault().getCheckpointManager().snapshot(filePath);
            }
        }
        // Also auto-save dirty editor for this file before CLI reads/writes
        handleToolCallPrepare(toolCall);
    }

    @Override
    public void onToolCallCompleted(MessageBlock block, MessageBlock.ToolCallSegment toolCall) {
        touchStreamActivity(); // tool finished — reset clock for the post-tool thinking phase
        // Capture status values NOW on the bg thread (avoids stale reads on UI thread)
        final MessageBlock.ToolStatus completedStatus = toolCall.getStatus();
        final String completedOutput = toolCall.getOutput();
        asyncExec(() -> {
            updateToolCallWidget(toolCall, completedStatus, completedOutput, 0);
            showThinkingIndicator();
            costBar.setStatus("Processing...");
            if ("Edit".equals(toolCall.getToolName()) || "Write".equals(toolCall.getToolName())) {
                handleEditToolCompleted(toolCall);
                refreshWorkspace();
            }
            scrollToBottom();
        });
    }

    /**
     * Update a tool call widget with its completed status and output.
     * If the widget hasn't been created yet (asyncExec race), retry up to 3 times
     * with a short delay.
     */
    private void updateToolCallWidget(MessageBlock.ToolCallSegment toolCall,
                                       MessageBlock.ToolStatus status, String output, int attempt) {
        String toolId = toolCall.getToolId();

        // Try direct map first
        com.anthropic.eclipse.claude.views.widgets.ToolCallComposite tcw =
            (toolId != null) ? toolCallWidgetById.get(toolId) : null;

        // Fallback: scan all MessageComposites
        if (tcw == null || tcw.isDisposed()) {
            for (MessageComposite mc : messageWidgetMap.values()) {
                if (mc.isDisposed()) continue;
                tcw = mc.findToolCallWidget(toolCall);
                if (tcw != null) break;
            }
        }

        if (tcw != null && !tcw.isDisposed()) {
            tcw.setStatus(status);
            if (output != null) {
                tcw.setOutput(output);
            }
        } else if (attempt < 5) {
            // Widget not created yet — retry after a short delay
            Display.getDefault().timerExec(100, () -> {
                if (!scrolledMessages.isDisposed()) {
                    updateToolCallWidget(toolCall, status, output, attempt + 1);
                }
            });
        }
    }

    @Override
    public void onAssistantMessageCompleted(MessageBlock block) {
        asyncExec(() -> {
            // Just finalize the widget content (apply markdown etc.).
            // Do NOT set "Ready" or disable stop here — this event fires after every
            // assistant turn including intermediate tool_use turns. The final "Ready"
            // state is set in onResultReceived once the full conversation turn ends.
            MessageComposite widget = messageWidgetMap.get(block);
            if (widget != null && !widget.isDisposed()) {
                widget.finalizeContent();
            }
            // Sync all tool call widgets with their model status.
            // This is a safety net: the asyncExec from onToolCallCompleted should have
            // already updated each widget, but input-delta asyncExec items may have
            // re-set the status to RUNNING. This sweep ensures everything is correct.
            syncAllToolCallStatuses();
            scrollToBottom();
        });
    }

    /**
     * Force-sync every ToolCallComposite widget with its model's actual status.
     * Called as a safety net after each assistant turn completes.
     */
    private void syncAllToolCallStatuses() {
        for (MessageComposite mc : messageWidgetMap.values()) {
            if (mc.isDisposed()) continue;
            mc.syncToolCallStatuses();
        }
    }

    @Override
    public void onResultReceived(UsageInfo usage) {
        cancelStreamingTimeout(); // full result arrived — no timeout needed
        asyncExec(() -> {
            hideThinkingIndicator();
            costBar.updateUsage(usage);
            stopButton.setEnabled(false);
            costBar.setStatus("Ready");

            // Save session state
            saveCurrentSession();

            // Refresh workspace to show any files created/modified during this turn
            refreshWorkspace();
        });
    }

    @Override
    public void onError(String error) {
        cancelStreamingTimeout();
        asyncExec(() -> {
            hideThinkingIndicator();
            showError(error);
            stopButton.setEnabled(false);
            costBar.setStatus("Error");
        });
    }

    @Override
    public void onConversationCleared() {
        asyncExec(() -> {
            for (MessageComposite widget : messageWidgetMap.values()) {
                if (!widget.isDisposed()) widget.dispose();
            }
            messageWidgetMap.clear();
            toolCallWidgetById.clear();
            welcomeBlock = null; // reset before re-adding
            addWelcomeMessage();
            scrollToBottom();
            costBar.reset();
        });
    }

    @Override
    public void onPermissionRequested(String toolUseId, String toolName, String description,
                                      String requestId, Object toolInput) {
        // Snapshot file BEFORE tool executes — input is fully available here (unlike onToolCallStarted)
        if (("Write".equals(toolName) || "Edit".equals(toolName) || "MultiEdit".equals(toolName))
                && toolInput != null) {
            String filePath = extractFilePath(toolInput.toString());
            if (filePath != null) {
                Activator.getDefault().getCheckpointManager().snapshot(filePath);
            }
        }

        asyncExec(() -> {
            // Helper to build the correct response JSON (control_response vs permission_response)
            final java.util.function.Function<Boolean, String> buildResponse = allow ->
                (requestId != null)
                    ? CliMessage.createControlResponse(requestId, allow, toolInput)
                    : CliMessage.createPermissionResponse(toolUseId, allow);

            // Check if auto-approve is configured for this tool
            IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();
            String autoApprove = prefs.getString(PreferenceConstants.AUTO_APPROVE_TOOLS);
            if (autoApprove != null && !autoApprove.isBlank()) {
                String[] approved = autoApprove.split(",");
                for (String tool : approved) {
                    if (tool.trim().equalsIgnoreCase(toolName)) {
                        // Auto-approve this tool
                        cliManager.sendRawNdjson(buildResponse.apply(true));
                        return;
                    }
                }
            }

            // Show permission banner for user to decide
            String bannerText = description != null ? description : "Claude wants to use: " + toolName;
            String bannerId = requestId != null ? requestId : toolUseId;
            Activator.logInfo("[PermissionBanner] Showing banner for tool=" + toolName
                + " requestId=" + bannerId);
            PermissionBanner banner = new PermissionBanner(
                messageContainer,
                bannerText,
                toolName + (bannerId != null ? " (" + bannerId + ")" : ""),
                new PermissionBanner.PermissionCallback() {
                    @Override
                    public void onAccepted(boolean alwaysAllow) {
                        Activator.logInfo("[PermissionBanner] ACCEPTED tool=" + toolName
                            + " requestId=" + bannerId + " alwaysAllow=" + alwaysAllow);
                        cliManager.sendRawNdjson(buildResponse.apply(true));
                        if (alwaysAllow && toolName != null) {
                            // Add this tool to auto-approve list
                            String current = prefs.getString(PreferenceConstants.AUTO_APPROVE_TOOLS);
                            if (current == null || current.isBlank()) {
                                prefs.setValue(PreferenceConstants.AUTO_APPROVE_TOOLS, toolName);
                            } else if (!current.contains(toolName)) {
                                prefs.setValue(PreferenceConstants.AUTO_APPROVE_TOOLS,
                                    current + "," + toolName);
                            }
                        }
                    }

                    @Override
                    public void onRejected() {
                        Activator.logInfo("[PermissionBanner] REJECTED tool=" + toolName
                            + " requestId=" + bannerId);
                        cliManager.sendRawNdjson(buildResponse.apply(false));
                    }
                }
            );
            // Force layout so the new banner widget is actually rendered,
            // then use double-asyncExec to scroll after the layout is painted.
            messageContainer.layout(true, true);
            int w1 = scrolledMessages.getClientArea().width;
            scrolledMessages.setMinSize(
                messageContainer.computeSize(w1 > 0 ? w1 : SWT.DEFAULT, SWT.DEFAULT));
            Display.getDefault().asyncExec(() -> {
                if (!scrolledMessages.isDisposed()) {
                    scrolledMessages.setOrigin(0, messageContainer.getSize().y);
                }
            });
        });
    }

    // ==================== Edit Tool Integration ====================

    /**
     * Handle Edit/Write tool completion - stage the edit for review.
     */
    /**
     * Called when Edit or Write tool call completes (file already written by CLI).
     * Uses CheckpointManager to get the pre-edit snapshot, then stages the diff
     * and shows a compact "file changed" notification with View Diff / Revert buttons.
     */
    private void handleEditToolCompleted(MessageBlock.ToolCallSegment toolCall) {
        if (editDecisionManager == null) return;

        String input = toolCall.getInput();
        if (input == null) return;

        // Extract file_path from tool input JSON (use robust JsonParser-based method)
        String filePath = extractFilePath(input);
        if (filePath == null) return;

        // ── ORIGINAL content: from CheckpointManager snapshot taken before this edit ──
        // The CLI has ALREADY written the file, so reading from disk gives the NEW content.
        com.anthropic.eclipse.claude.diff.CheckpointManager checkpointMgr =
            Activator.getDefault().getCheckpointManager();
        String originalContent = checkpointMgr.getSnapshots().get(filePath);
        if (originalContent == null) {
            // File was brand-new (Write tool on new file) — treat original as empty
            originalContent = "";
        }

        // ── MODIFIED content: read current (already-written) file from disk ──
        String modifiedContent;
        try {
            modifiedContent = new String(Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Activator.logError("[ConversationView] Could not read modified file: " + filePath, e);
            return;
        }

        if (originalContent.equals(modifiedContent)) return; // nothing changed

        // Record the completed edit so diff/revert actions can access it.
        // (File is already written — use recordCompletedEdit to avoid stale annotations.)
        editDecisionManager.recordCompletedEdit(filePath, originalContent, modifiedContent, toolCall);

        // Show a "file changed" notification row (NOT a permission banner — edit is done)
        showFileChangedNotification(filePath, toolCall);
    }

    /**
     * Shows a compact inline notification after Claude has edited a file.
     * Provides quick access to View Diff, Compare Editor, and Revert actions.
     * This is informational — permission was already granted before the edit.
     */
    private void showFileChangedNotification(String filePath, MessageBlock.ToolCallSegment toolCall) {
        String fileName = Paths.get(filePath).getFileName().toString();
        ThemeManager tm = ThemeManager.getInstance();

        // Container
        Composite row = new Composite(messageContainer, SWT.NONE);
        GridLayout rl = new GridLayout(1, false);
        rl.marginWidth = 10;
        rl.marginHeight = 6;
        row.setLayout(rl);
        row.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        Color rowBg = tm.getColor(tm.toolBg);
        row.setBackground(rowBg);
        row.addDisposeListener(e -> rowBg.dispose());

        // Label: "✏ Edited: filename.java"
        Label info = new Label(row, SWT.NONE);
        info.setText("\u270F Edited: " + fileName);
        info.setBackground(rowBg);
        Color dimColor = tm.getColor(tm.dimTextColor);
        info.setForeground(dimColor);
        info.addDisposeListener(e -> dimColor.dispose());

        // Buttons
        Composite btns = new Composite(row, SWT.NONE);
        btns.setLayout(new org.eclipse.swt.layout.RowLayout(SWT.HORIZONTAL));
        btns.setBackground(rowBg);

        Button viewDiffBtn = new Button(btns, SWT.PUSH | SWT.FLAT);
        viewDiffBtn.setText("\u21C4 View Diff");
        viewDiffBtn.setToolTipText("Open side-by-side diff in Eclipse Compare editor");
        viewDiffBtn.addListener(SWT.Selection, e -> {
            EditDecisionManager.PendingEdit edit = editDecisionManager.getEdit(filePath);
            if (edit != null) {
                com.anthropic.eclipse.claude.diff.ClaudeCompareInput.open(
                    getSite().getPage(),
                    edit.getOriginalContent(),
                    edit.getModifiedContent(),
                    fileName
                );
            }
        });

        Button revertBtn = new Button(btns, SWT.PUSH | SWT.FLAT);
        revertBtn.setText("\u21A9 Revert");
        revertBtn.setToolTipText("Restore the file to its pre-edit state");
        revertBtn.addListener(SWT.Selection, e -> {
            Activator.getDefault().getCheckpointManager().revert(filePath);
            refreshWorkspace();
            revertBtn.setEnabled(false);
            revertBtn.setText("\u21A9 Reverted");
            info.setText("\u21A9 Reverted: " + fileName);
        });

        scrollToBottom();
    }

    // ==================== ICliStateListener Implementation ====================

    @Override
    public void onStateChanged(ClaudeCliManager.ProcessState oldState, ClaudeCliManager.ProcessState newState) {
        asyncExec(() -> {
            switch (newState) {
                case STARTING:
                    connectionStatus.setText("Starting...");
                    break;
                case RUNNING:
                    connectionStatus.setText("\u2713 Connected");
                    connectionStatus.setForeground(connectedColor);
                    costBar.setStatus("Ready");
                    if (oldState != ClaudeCliManager.ProcessState.STARTING) {
                        costBar.showToast("\u2713 Claude reconnected", 2500);
                    }
                    break;
                case STOPPING:
                    connectionStatus.setText("Stopping...");
                    break;
                case STOPPED:
                    connectionStatus.setText("\u25CF Disconnected");
                    connectionStatus.setForeground(disconnectedColor);
                    costBar.setStatus("Stopped");
                    cancelStreamingTimeout();
                    if (model != null) model.markActiveToolCallsFailed("CLI stopped");
                    break;
                case ERROR:
                    connectionStatus.setText("\u2717 Error - CLI process crashed");
                    connectionStatus.setForeground(errorColor);
                    costBar.setStatus("Error");
                    cancelStreamingTimeout();
                    hideThinkingIndicator(); // CLI died mid-response — clear the indicator
                    // Mark any "Running..." tool calls as FAILED so they don't stay stuck
                    if (model != null) model.markActiveToolCallsFailed("CLI process crashed");
                    showRestartBanner();
                    break;
                default:
                    break;
            }
        });
    }

    // ==================== Session Management ====================

    private void autoStartCli() {
        // Check if CLI mode is enabled in preferences
        try {
            IPreferenceStore modePrefs = Activator.getDefault().getPreferenceStore();
            if (!modePrefs.getBoolean(PreferenceConstants.USE_CLI_MODE)) {
                showInfoMessage("CLI mode is disabled in preferences. "
                    + "Enable 'Use CLI Mode' in Preferences > Claude AI, or use the legacy Claude Chat view.");
                return;
            }
        } catch (Exception ignored) {}

        if (cliManager.isRunning()) return;

        // API key OR OAuth login is required — check both before blocking startup
        String apiKey = SecureApiKeyStore.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            // No API key — check if the CLI is authenticated via OAuth (claude auth login)
            if (!cliManager.isOAuthAuthenticated()) {
                showInfoMessage("\u26A0 Anthropic API Key not configured.\n"
                    + "Go to Window \u2192 Preferences \u2192 Claude AI to set your API key.\n"
                    + "You can also set ANTHROPIC_API_KEY as an environment variable.\n"
                    + "Or run 'claude auth login' in a terminal to authenticate via claude.ai.");
                return;
            }
            // OAuth is active — proceed without API key (CLI will use its stored token)
        }

        String cliPath = cliManager.getCliPath();
        if (cliPath == null) {
            if (!cliManager.detectCLI()) {
                showCliNotInstalled();
                return;
            }
            cliPath = cliManager.getCliPath();
        }

        String workDir = getDefaultWorkingDirectory();
        IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();
        String prefsModel = prefs.getString(PreferenceConstants.MODEL);

        // If model was not explicitly changed from the default, fall back to the
        // model configured in ~/.claude/settings.json (so CLI and Eclipse stay in sync)
        if (prefsModel == null || prefsModel.isBlank()
                || prefsModel.equals(prefs.getDefaultString(PreferenceConstants.MODEL))) {
            String cliSettingsModel = Activator.getDefault().getSettingsReader()
                    .getUserSetting("model", null);
            if (cliSettingsModel != null && !cliSettingsModel.isBlank()) {
                prefsModel = cliSettingsModel;
            }
        }
        String cliModel = mapModelName(prefsModel);

        // Get permission mode from preferences.
        // In stream-json mode, "default" would try interactive terminal prompts which don't
        // work, so fall back to "acceptEdits" which auto-approves file edits.
        String permMode = prefs.getString(PreferenceConstants.PERMISSION_MODE);
        if (permMode == null || permMode.isBlank() || "default".equals(permMode)) {
            permMode = "acceptEdits";
        }

        // Get max turns from preferences
        int maxTurns = prefs.getInt(PreferenceConstants.MAX_TURNS);

        try {
            CliProcessConfig.Builder builder = new CliProcessConfig.Builder(cliPath, workDir)
                .model(cliModel)
                .permissionMode(permMode);

            if (maxTurns > 0) {
                builder.maxTurns(maxTurns);
            }

            // Detect Eclipse project structure and append context to system prompt
            String projectContext = detectProjectContext(workDir);
            if (projectContext != null && !projectContext.isBlank()) {
                builder.appendSystemPrompt(projectContext);
            }

            cliManager.start(builder.build());
        } catch (ClaudeCliManager.CliException e) {
            showError("Failed to start Claude CLI: " + e.getMessage());
        }
    }

    /**
     * Detect project structure (any language/framework) and return a system prompt
     * supplement that helps Claude place files in the correct directories.
     */
    private String detectProjectContext(String workDir) {
        try {
            java.io.File projectDir = new java.io.File(workDir);
            if (!projectDir.isDirectory()) return null;

            StringBuilder context = new StringBuilder();
            context.append("You are working inside a project at: ").append(workDir).append("\n");

            // Scan what config files exist to detect project type(s)
            boolean hasEclipseProject = new java.io.File(projectDir, ".project").exists();
            boolean hasClasspath = new java.io.File(projectDir, ".classpath").exists();
            boolean hasPom = new java.io.File(projectDir, "pom.xml").exists();
            boolean hasGradleGroovy = new java.io.File(projectDir, "build.gradle").exists();
            boolean hasGradleKotlin = new java.io.File(projectDir, "build.gradle.kts").exists();
            boolean hasPackageJson = new java.io.File(projectDir, "package.json").exists();
            boolean hasTsConfig = new java.io.File(projectDir, "tsconfig.json").exists();
            boolean hasPyProjectToml = new java.io.File(projectDir, "pyproject.toml").exists();
            boolean hasSetupPy = new java.io.File(projectDir, "setup.py").exists();
            boolean hasRequirementsTxt = new java.io.File(projectDir, "requirements.txt").exists();
            boolean hasGoMod = new java.io.File(projectDir, "go.mod").exists();
            boolean hasCargoToml = new java.io.File(projectDir, "Cargo.toml").exists();
            boolean hasCMakeLists = new java.io.File(projectDir, "CMakeLists.txt").exists();
            boolean hasMakefile = new java.io.File(projectDir, "Makefile").exists();
            boolean hasDotNet = new java.io.File(projectDir, "*.csproj").exists()
                || new java.io.File(projectDir, "*.sln").exists();

            // Also scan for .csproj / .sln by listing (glob won't work on File)
            if (!hasDotNet) {
                String[] files = projectDir.list();
                if (files != null) {
                    for (String f : files) {
                        if (f.endsWith(".csproj") || f.endsWith(".sln") || f.endsWith(".fsproj")) {
                            hasDotNet = true;
                            break;
                        }
                    }
                }
            }

            // ==================== Java / JVM ====================
            if (hasPom || hasGradleGroovy || hasGradleKotlin || hasClasspath) {
                context.append("Project type: Java/JVM.\n");

                if (hasPom) {
                    context.append("Build system: Maven (pom.xml found).\n");
                    context.append("Convention: Place source files in 'src/main/java/<package>/', ");
                    context.append("test files in 'src/test/java/<package>/', ");
                    context.append("resources in 'src/main/resources/'.\n");
                } else if (hasGradleGroovy || hasGradleKotlin) {
                    context.append("Build system: Gradle.\n");
                    context.append("Convention: Place source files in 'src/main/java/<package>/' ");
                    context.append("(or 'src/main/kotlin/<package>/'), ");
                    context.append("test files in 'src/test/java/<package>/'.\n");
                } else if (hasClasspath) {
                    // Plain Eclipse Java project — parse source dirs from .classpath
                    List<String> sourceDirs = parseClasspathSourceDirs(projectDir);
                    if (!sourceDirs.isEmpty()) {
                        context.append("Source directories (from .classpath): ")
                            .append(String.join(", ", sourceDirs)).append("\n");
                        context.append("Convention: Place source files in '")
                            .append(sourceDirs.get(0))
                            .append("/<package>/' with proper package subdirectories.\n");
                    }
                }
                context.append("IMPORTANT: Never create .java/.kt/.scala files at the project root. ")
                    .append("Always use the correct source directory with package structure.\n");
            }

            // ==================== Node.js / TypeScript ====================
            if (hasPackageJson) {
                context.append("Project type: Node.js");
                if (hasTsConfig) context.append(" + TypeScript");
                context.append(" (package.json found).\n");

                // Detect common source layout
                boolean hasSrcDir = new java.io.File(projectDir, "src").isDirectory();
                boolean hasLibDir = new java.io.File(projectDir, "lib").isDirectory();
                boolean hasAppDir = new java.io.File(projectDir, "app").isDirectory();
                boolean hasPagesDir = new java.io.File(projectDir, "pages").isDirectory();

                if (hasSrcDir) {
                    context.append("Convention: Source files go in 'src/'.");
                    if (hasTsConfig) context.append(" Use .ts/.tsx extensions.");
                    context.append("\n");
                } else if (hasAppDir) {
                    context.append("Convention: This appears to be a Next.js/Remix app. ")
                        .append("Source files go in 'app/'.\n");
                } else if (hasPagesDir) {
                    context.append("Convention: This appears to be a Next.js pages-router app. ")
                        .append("Page files go in 'pages/'.\n");
                } else if (hasLibDir) {
                    context.append("Convention: Source files go in 'lib/'.\n");
                }

                context.append("Tests typically go in '__tests__/', 'test/', or 'tests/'.\n");
                context.append("IMPORTANT: Do NOT create source files at the project root ")
                    .append("unless it's a config file. Follow the existing directory structure.\n");
            }

            // ==================== Python ====================
            if (hasPyProjectToml || hasSetupPy || hasRequirementsTxt) {
                context.append("Project type: Python");
                if (hasPyProjectToml) context.append(" (pyproject.toml found)");
                else if (hasSetupPy) context.append(" (setup.py found)");
                context.append(".\n");

                // Detect common Python layouts
                boolean hasSrcDir = new java.io.File(projectDir, "src").isDirectory();
                String[] dirs = projectDir.list();
                String packageDir = null;
                if (dirs != null) {
                    for (String d : dirs) {
                        java.io.File initPy = new java.io.File(new java.io.File(projectDir, d), "__init__.py");
                        if (initPy.exists() && !d.startsWith(".") && !d.equals("test")
                                && !d.equals("tests") && !d.equals("venv") && !d.equals("env")) {
                            packageDir = d;
                            break;
                        }
                    }
                }

                if (hasSrcDir) {
                    context.append("Layout: src-layout. Place modules in 'src/<package_name>/'.\n");
                } else if (packageDir != null) {
                    context.append("Layout: flat-layout. Main package is '").append(packageDir).append("/'.\n");
                    context.append("Place new modules inside '").append(packageDir).append("/'.\n");
                }

                context.append("Tests typically go in 'tests/' or 'test/'.\n");
                context.append("IMPORTANT: New Python modules should go inside the existing package directory, ")
                    .append("not at the project root. Follow the existing structure.\n");
            }

            // ==================== Go ====================
            if (hasGoMod) {
                context.append("Project type: Go (go.mod found).\n");

                boolean hasCmdDir = new java.io.File(projectDir, "cmd").isDirectory();
                boolean hasInternalDir = new java.io.File(projectDir, "internal").isDirectory();
                boolean hasPkgDir = new java.io.File(projectDir, "pkg").isDirectory();

                if (hasCmdDir) {
                    context.append("Layout: Multi-command Go project. ")
                        .append("Executables in 'cmd/<name>/', libraries in ");
                    if (hasInternalDir) context.append("'internal/'");
                    else if (hasPkgDir) context.append("'pkg/'");
                    else context.append("separate packages");
                    context.append(".\n");
                }
                context.append("Convention: Place .go files in appropriate package directories. ")
                    .append("Test files use the '_test.go' suffix in the same package directory.\n");
            }

            // ==================== Rust ====================
            if (hasCargoToml) {
                context.append("Project type: Rust (Cargo.toml found).\n");
                context.append("Convention: Source files go in 'src/'. Entry point is 'src/main.rs' ")
                    .append("(binary) or 'src/lib.rs' (library). Tests go in 'tests/' or inline.\n");
            }

            // ==================== C/C++ ====================
            if (hasCMakeLists || hasMakefile) {
                boolean hasSrcDir = new java.io.File(projectDir, "src").isDirectory();
                boolean hasIncludeDir = new java.io.File(projectDir, "include").isDirectory();

                context.append("Project type: C/C++");
                if (hasCMakeLists) context.append(" (CMake)");
                context.append(".\n");

                if (hasSrcDir && hasIncludeDir) {
                    context.append("Convention: Source files (.c/.cpp) in 'src/', ")
                        .append("headers (.h/.hpp) in 'include/'.\n");
                } else if (hasSrcDir) {
                    context.append("Convention: Source files in 'src/'.\n");
                }
            }

            // ==================== .NET / C# ====================
            if (hasDotNet) {
                context.append("Project type: .NET/C#.\n");
                context.append("Convention: Follow the existing namespace-to-folder mapping. ")
                    .append("Place new .cs files in the appropriate namespace directory.\n");
            }

            // ==================== Existing directory structure ====================
            // Always show top-level structure so Claude can see the layout
            String[] topLevel = projectDir.list();
            if (topLevel != null) {
                List<String> visibleDirs = new ArrayList<>();
                List<String> visibleFiles = new ArrayList<>();
                for (String entry : topLevel) {
                    if (entry.startsWith(".")) continue; // skip hidden
                    java.io.File f = new java.io.File(projectDir, entry);
                    if (f.isDirectory()) {
                        // Skip common non-source dirs
                        if (!"node_modules".equals(entry) && !"bin".equals(entry)
                                && !"build".equals(entry) && !"target".equals(entry)
                                && !"dist".equals(entry) && !"out".equals(entry)
                                && !"__pycache__".equals(entry) && !"venv".equals(entry)
                                && !"env".equals(entry) && !".git".equals(entry)) {
                            visibleDirs.add(entry + "/");
                        }
                    } else {
                        visibleFiles.add(entry);
                    }
                }

                if (!visibleDirs.isEmpty() || !visibleFiles.isEmpty()) {
                    context.append("Top-level structure: ");
                    List<String> all = new ArrayList<>(visibleDirs);
                    // Only show key config files, not all files
                    for (String file : visibleFiles) {
                        if (file.endsWith(".json") || file.endsWith(".xml") || file.endsWith(".toml")
                                || file.endsWith(".yaml") || file.endsWith(".yml") || file.endsWith(".gradle")
                                || file.endsWith(".gradle.kts") || file.endsWith(".mod")
                                || file.equals("Makefile") || file.equals("Dockerfile")
                                || file.equals("README.md")) {
                            all.add(file);
                        }
                    }
                    context.append(String.join(", ", all)).append("\n");
                }
            }

            // General rule
            context.append("RULE: Always follow the existing project conventions. ")
                .append("Place new source files in the correct source directory for this project type, ")
                .append("never at the project root unless it's a config/build file.\n");

            return context.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse source directory entries from Eclipse .classpath file.
     */
    private List<String> parseClasspathSourceDirs(java.io.File projectDir) {
        List<String> sourceDirs = new ArrayList<>();
        try {
            java.io.File dotClasspath = new java.io.File(projectDir, ".classpath");
            if (!dotClasspath.exists()) return sourceDirs;

            String xml = new String(Files.readAllBytes(dotClasspath.toPath()), StandardCharsets.UTF_8);
            int idx = 0;
            while ((idx = xml.indexOf("kind=\"src\"", idx)) >= 0) {
                int pathStart = xml.indexOf("path=\"", idx);
                if (pathStart >= 0 && pathStart < idx + 80) {
                    pathStart += 6;
                    int pathEnd = xml.indexOf("\"", pathStart);
                    if (pathEnd > pathStart) {
                        String srcPath = xml.substring(pathStart, pathEnd);
                        if (!srcPath.isEmpty() && !srcPath.startsWith("/")) {
                            sourceDirs.add(srcPath);
                        }
                    }
                }
                idx++;
            }
        } catch (Exception ignored) {}
        return sourceDirs;
    }

    private void saveCurrentSession() {
        if (sessionManager != null && model != null) {
            try {
                sessionManager.saveCurrentSession(model);
                asyncExec(() -> {
                    if (costBar != null && !costBar.isDisposed()) {
                        costBar.showToast("\u2713 Session saved", 2000);
                    }
                });
            } catch (Exception e) {
                Activator.logError("[ConversationView] Failed to save session: " + e.getMessage(), e);
            }
        }
    }

    public void showResumeDialog() {
        final List<SessionInfo> sessions = sessionManager.listSessions();
        if (sessions.isEmpty()) {
            showInfoMessage("No previous sessions found.");
            return;
        }

        // TitleAreaDialog with plain SWT List — preserves newest-first insertion order
        // (ElementListSelectionDialog auto-sorts alphabetically, so we avoid it here)
        TitleAreaDialog dialog = new TitleAreaDialog(getViewSite().getShell()) {
            private org.eclipse.swt.widgets.List sessionList;
            private final List<SessionInfo> displayed = new ArrayList<>();

            @Override
            public void create() {
                super.create();
                setTitle("Resume Session");
                setMessage("Select a session to resume:");
                getShell().setText("Resume Session");
            }

            @Override
            protected Control createDialogArea(Composite parent) {
                Composite area = (Composite) super.createDialogArea(parent);
                Composite container = new Composite(area, SWT.NONE);
                GridLayout gl = new GridLayout(1, false);
                gl.marginWidth = 10;
                gl.marginHeight = 6;
                container.setLayout(gl);
                container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

                Text filterText = new Text(container, SWT.BORDER | SWT.SEARCH);
                filterText.setMessage("type filter text");
                filterText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

                sessionList = new org.eclipse.swt.widgets.List(
                    container, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
                GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
                gd.heightHint = 280;
                sessionList.setLayoutData(gd);

                rebuildList(sessions, "");
                filterText.addModifyListener(
                    e -> rebuildList(sessions, filterText.getText().toLowerCase()));
                sessionList.addListener(SWT.DefaultSelection, e -> okPressed());
                return area;
            }

            private void rebuildList(List<SessionInfo> all, String filter) {
                displayed.clear();
                sessionList.removeAll();
                for (SessionInfo s : all) {
                    String label = s.getDisplayLabel();
                    if (filter.isEmpty() || label.toLowerCase().contains(filter)) {
                        sessionList.add(label);
                        displayed.add(s);
                    }
                }
                if (sessionList.getItemCount() > 0) sessionList.setSelection(0);
            }

            @Override
            protected void okPressed() {
                int idx = sessionList.getSelectionIndex();
                if (idx >= 0 && idx < displayed.size()) {
                    resumeSession(displayed.get(idx).getSessionId());
                }
                super.okPressed();
            }
        };
        dialog.open();
    }

    /**
     * Get context about the file currently open in the active editor.
     * This helps Claude know which file the user is referring to.
     */
    private String getActiveEditorContext() {
        try {
            org.eclipse.ui.IWorkbenchPage page = getSite().getPage();
            if (page == null) return null;

            org.eclipse.ui.IEditorPart activeEditor = page.getActiveEditor();
            if (activeEditor == null) return null;

            org.eclipse.ui.IEditorInput input = activeEditor.getEditorInput();
            IFile file = input.getAdapter(IFile.class);
            if (file == null) return null;

            String filePath = file.getLocation().toOSString();
            String fileName = file.getName();
            String projectName = file.getProject().getName();

            StringBuilder ctx = new StringBuilder();
            ctx.append("[Active editor context: The user currently has '")
               .append(fileName).append("' open (project: ").append(projectName)
               .append(", full path: ").append(filePath).append(")");

            // If there's a text selection, include it
            if (activeEditor instanceof org.eclipse.ui.texteditor.ITextEditor) {
                org.eclipse.ui.texteditor.ITextEditor textEditor =
                    (org.eclipse.ui.texteditor.ITextEditor) activeEditor;
                org.eclipse.jface.viewers.ISelectionProvider sp = textEditor.getSelectionProvider();
                if (sp != null) {
                    org.eclipse.jface.viewers.ISelection sel = sp.getSelection();
                    if (sel instanceof org.eclipse.jface.text.ITextSelection) {
                        org.eclipse.jface.text.ITextSelection textSel =
                            (org.eclipse.jface.text.ITextSelection) sel;
                        String selectedText = textSel.getText();
                        if (selectedText != null && !selectedText.isBlank() && selectedText.length() < 5000) {
                            ctx.append("\nSelected text (lines ").append(textSel.getStartLine() + 1)
                               .append("-").append(textSel.getEndLine() + 1).append("):\n```\n")
                               .append(selectedText).append("\n```");
                        } else {
                            // Include cursor position
                            int line = textSel.getStartLine() + 1;
                            ctx.append(", cursor at line ").append(line);
                        }
                    }
                }
            }
            ctx.append("]");
            return ctx.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String getDefaultWorkingDirectory() {
        // 1. Check preferences first
        IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();
        String configuredDir = prefs.getString(PreferenceConstants.DEFAULT_WORKING_DIR);
        if (configuredDir != null && !configuredDir.isBlank()) {
            if (new java.io.File(configuredDir).isDirectory()) {
                return configuredDir;
            }
        }

        // 2. Try to get the project from the active editor
        try {
            org.eclipse.ui.IWorkbenchPage page = getSite().getPage();
            if (page != null) {
                org.eclipse.ui.IEditorPart activeEditor = page.getActiveEditor();
                if (activeEditor != null) {
                    org.eclipse.ui.IEditorInput input = activeEditor.getEditorInput();
                    org.eclipse.core.resources.IFile file =
                        input.getAdapter(org.eclipse.core.resources.IFile.class);
                    if (file != null && file.getProject() != null
                            && file.getProject().getLocation() != null) {
                        return file.getProject().getLocation().toOSString();
                    }
                }

                // 3. Try to get the project from the current selection (e.g., in Package Explorer)
                org.eclipse.jface.viewers.ISelection selection = page.getSelection();
                if (selection instanceof org.eclipse.jface.viewers.IStructuredSelection) {
                    Object firstElement =
                        ((org.eclipse.jface.viewers.IStructuredSelection) selection).getFirstElement();
                    if (firstElement instanceof org.eclipse.core.resources.IResource) {
                        IProject project = ((org.eclipse.core.resources.IResource) firstElement).getProject();
                        if (project != null && project.getLocation() != null) {
                            return project.getLocation().toOSString();
                        }
                    } else if (firstElement instanceof org.eclipse.core.runtime.IAdaptable) {
                        org.eclipse.core.resources.IResource res =
                            ((org.eclipse.core.runtime.IAdaptable) firstElement)
                                .getAdapter(org.eclipse.core.resources.IResource.class);
                        if (res != null && res.getProject() != null
                                && res.getProject().getLocation() != null) {
                            return res.getProject().getLocation().toOSString();
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Not critical - fall through to other heuristics
        }

        // 4. Fall back: pick the first open project that ISN'T this plugin project
        String pluginBundleId = "com.anthropic.eclipse.claude";
        IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        for (IProject project : projects) {
            if (project.isOpen() && project.getLocation() != null
                    && !pluginBundleId.equals(project.getName())) {
                return project.getLocation().toOSString();
            }
        }

        // 5. Last resort: workspace root itself (not a specific project)
        String workspaceRoot = ResourcesPlugin.getWorkspace().getRoot()
            .getLocation().toOSString();
        if (new java.io.File(workspaceRoot).isDirectory()) {
            return workspaceRoot;
        }

        return System.getProperty("user.home");
    }

    private String mapModelName(String prefsModel) {
        if (prefsModel == null || prefsModel.isBlank()) return null;
        if (prefsModel.contains("sonnet")) return "sonnet";
        if (prefsModel.contains("opus")) return "opus";
        if (prefsModel.contains("haiku")) return "haiku";
        return prefsModel;
    }

    // ==================== UI Helpers ====================

    private void addWelcomeMessage() {
        welcomeBlock = new MessageBlock(MessageBlock.Role.SYSTEM);
        MessageBlock.TextSegment textSeg = new MessageBlock.TextSegment();
        textSeg.appendText("Welcome to Claude Code for Eclipse!\n\n" +
            "Features:\n" +
            "- Real-time streaming responses\n" +
            "- Agentic tool use (file read/write, search, commands)\n" +
            "- Code blocks with Copy/Apply/Insert\n" +
            "- File & image attachments\n" +
            "- Inline edit accept/reject\n" +
            "- Session save/resume\n\n" +
            "Commands: /new, /clear, /cost, /help, /stop, /resume, /model\n" +
            "CLI Commands: /commit, /review-pr, /explain, /fix, /test, /refactor\n\n" +
            "Type a message below to get started.");
        welcomeBlock.addSegment(textSeg);

        MessageComposite widget = new MessageComposite(messageContainer, welcomeBlock);
        messageWidgetMap.put(welcomeBlock, widget);
        scrollToBottom();
    }

    /**
     * Dismiss the welcome message widget. Called when the first real message is sent
     * or when session history is loaded, so the chat area is uncluttered.
     * Must be called on the UI thread.
     */
    private void dismissWelcomeMessage() {
        if (welcomeBlock == null) return;
        MessageComposite w = messageWidgetMap.remove(welcomeBlock);
        if (w != null && !w.isDisposed()) {
            w.dispose();
            messageContainer.layout(true, true);
        }
        welcomeBlock = null;
    }

    private void showError(String error) {
        MessageBlock errorBlock = new MessageBlock(MessageBlock.Role.ERROR);
        MessageBlock.TextSegment textSeg = new MessageBlock.TextSegment();
        textSeg.appendText(error);
        errorBlock.addSegment(textSeg);

        MessageComposite widget = new MessageComposite(messageContainer, errorBlock);
        messageWidgetMap.put(errorBlock, widget);
        scrollToBottom();
    }

    /**
     * Called when a tool call starts: snapshots the target file (for revert) and
     * optionally saves dirty editors (so Claude reads the latest content).
     */
    private void handleToolCallPrepare(MessageBlock.ToolCallSegment toolCall) {
        String toolName = toolCall.getToolName();
        if (toolName == null) return;

        boolean isWriteTool = "Write".equals(toolName) || "Edit".equals(toolName)
                || "MultiEdit".equals(toolName);
        boolean needsFilePath = isWriteTool || "Read".equals(toolName);
        if (!needsFilePath) return;

        String filePath = extractFilePath(toolCall.getInput());
        if (filePath == null) return;

        // Snapshot before modification so the user can revert
        if (isWriteTool) {
            Activator.getDefault().getCheckpointManager().snapshot(filePath);
        }

        // Auto-save the dirty editor for this file (so Claude reads saved content)
        IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();
        if (prefs.getBoolean(PreferenceConstants.AUTO_SAVE_BEFORE_TOOLS)) {
            final String fp = filePath;
            Display.getDefault().asyncExec(() -> autoSaveEditorForFile(fp));
        }
    }

    /**
     * Saves the dirty editor for a specific file path, if open.
     */
    private void autoSaveEditorForFile(String filePath) {
        try {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) return;
            IWorkbenchPage page = window.getActivePage();
            if (page == null) return;

            for (IEditorReference ref : page.getEditorReferences()) {
                if (!ref.isDirty()) continue;
                IEditorPart editor = ref.getEditor(false);
                if (editor == null) continue;
                if (editor.getEditorInput() instanceof org.eclipse.ui.IFileEditorInput) {
                    org.eclipse.ui.IFileEditorInput fileInput =
                            (org.eclipse.ui.IFileEditorInput) editor.getEditorInput();
                    String editorPath = fileInput.getFile().getLocation().toOSString();
                    if (filePath.equals(editorPath)) {
                        editor.doSave(null);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Activator.logError("[ConversationView] Auto-save failed for " + filePath, e);
        }
    }

    /**
     * Extract the {@code file_path} field from a tool's JSON input string.
     * Returns null if not found or input is null/blank.
     */
    private String extractFilePath(String toolInputJson) {
        if (toolInputJson == null || toolInputJson.isBlank()) return null;
        try {
            Map<String, Object> parsed =
                    com.anthropic.eclipse.claude.util.JsonParser.parseObject(toolInputJson);
            return com.anthropic.eclipse.claude.util.JsonParser.getString(parsed, "file_path");
        } catch (Exception e) {
            return null; // non-critical
        }
    }

    /**
     * Refresh the Eclipse workspace so newly created/modified files appear
     * in the Package Explorer without manual refresh.
     */
    private void refreshWorkspace() {
        try {
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            for (IProject project : projects) {
                if (project.isOpen()) {
                    project.refreshLocal(
                        org.eclipse.core.resources.IResource.DEPTH_INFINITE,
                        null  // no progress monitor
                    );
                }
            }
        } catch (Exception e) {
            Activator.logError("[ConversationView] Workspace refresh failed: " + e.getMessage(), e);
        }
    }

    private void showInfoMessage(String info) {
        MessageBlock infoBlock = new MessageBlock(MessageBlock.Role.SYSTEM);
        MessageBlock.TextSegment textSeg = new MessageBlock.TextSegment();
        textSeg.appendText(info);
        infoBlock.addSegment(textSeg);

        MessageComposite widget = new MessageComposite(messageContainer, infoBlock);
        messageWidgetMap.put(infoBlock, widget);
        scrollToBottom();
    }

    private void showRestartBanner() {
        ThemeManager tmBanner = ThemeManager.getInstance();
        Color bannerBg = tmBanner.getColor(tmBanner.permissionBg);
        Composite banner = new Composite(messageContainer, SWT.BORDER);
        GridLayout bannerLayout = new GridLayout(2, false);
        bannerLayout.marginWidth = 12;
        bannerLayout.marginHeight = 8;
        banner.setLayout(bannerLayout);
        banner.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        banner.setBackground(bannerBg);
        banner.addDisposeListener(e -> bannerBg.dispose());

        Label msg = new Label(banner, SWT.WRAP);
        int exitCode = cliManager.getLastExitCode();
        String diagnosis = diagnoseCrash(exitCode);
        msg.setText("\u26A0 Claude CLI terminated" + (exitCode >= 0 ? " (exit " + exitCode + ")" : "") + ".\n" + diagnosis);
        msg.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        msg.setBackground(banner.getBackground());

        Button restartBtn = new Button(banner, SWT.PUSH);
        restartBtn.setText("Reconnect");
        restartBtn.addListener(SWT.Selection, e -> {
            banner.dispose();
            messageContainer.layout(true, true);
            autoStartCli();
        });

        scrollToBottom();
    }

    /**
     * Translate a CLI exit code into a human-readable hint.
     */
    private String diagnoseCrash(int exitCode) {
        switch (exitCode) {
            case 0:  return "Session ended normally.";
            case 1:  return "Check your API key in Preferences > Claude AI.";
            case 2:  return "Invalid arguments — try starting a new session.";
            case 127:return "CLI not found. Check the CLI path in Preferences.";
            case 130:return "Interrupted by user (Ctrl+C).";
            default: return exitCode > 0
                ? "Unexpected exit. Check the Error Log for details."
                : "Unknown cause.";
        }
    }

    private void showCliNotInstalled() {
        showError("Claude Code CLI is not installed.\n\n" + ClaudeCliManager.getInstallInstructions());
    }

    private void showCostSummary() {
        UsageInfo usage = model.getCumulativeUsage();
        showInfoMessage("Session Cost Summary:\n" + usage.toString());
    }

    private void showHelp() {
        showInfoMessage(SlashCommandHandler.formatHelp() +
            "\nKeyboard:\n" +
            "  Enter          Send message\n" +
            "  Shift+Enter    New line\n" +
            "  Ctrl+Shift+C   Open this view");
    }

    // Model definitions: { modelId, displayLabel }
    private static final String[][] AVAILABLE_MODELS = {
        {"claude-sonnet-4-5", "Sonnet 4.5 — Fast, balanced"},
        {"claude-opus-4", "Opus 4 — Most capable"},
        {"claude-haiku-3-5", "Haiku 3.5 — Fastest, lightweight"},
    };

    private void showModelInfo() {
        SessionInfo info = model.getSessionInfo();
        String currentModel = info != null && info.getModel() != null ? info.getModel() : "unknown";

        // Build display strings (the dialog element is a plain String, easy filtering)
        String[] displayLabels = new String[AVAILABLE_MODELS.length];
        for (int i = 0; i < AVAILABLE_MODELS.length; i++) {
            String id = AVAILABLE_MODELS[i][0];
            String desc = AVAILABLE_MODELS[i][1];
            String shortCurrent = mapModelToShortName(currentModel);
            String shortCandidate = mapModelToShortName(id);
            String active = shortCandidate.equals(shortCurrent) ? "  \u2713 active" : "";
            displayLabels[i] = id + "  (" + desc + ")" + active;
        }

        ElementListSelectionDialog dialog = new ElementListSelectionDialog(
            getViewSite().getShell(), new LabelProvider());
        dialog.setTitle("Switch Model");
        dialog.setMessage("Current model: " + currentModel + "\nSelect a model to switch to:");
        dialog.setElements(displayLabels);
        dialog.setMultipleSelection(false);
        // Show all items by default (empty filter matches everything)
        dialog.setInitialSelections(new Object[0]);

        if (dialog.open() == Window.OK && dialog.getResult().length > 0) {
            String selected = (String) dialog.getResult()[0];
            // Extract model ID from the display string (text before first double-space)
            String newModelId = selected.split("\\s{2}")[0].trim();
            switchToModel(newModelId);
        }
    }

    private void showRulesDialog() {
        String workDir = getDefaultWorkingDirectory();
        RulesDialog dialog = new RulesDialog(getViewSite().getShell(), workDir);
        dialog.open();
    }

    private void showMcpDialog() {
        String workDir = getDefaultWorkingDirectory();
        McpServersDialog dialog = new McpServersDialog(getViewSite().getShell(), workDir);
        dialog.open();
    }

    private void showHooksDialog() {
        String workDir = getDefaultWorkingDirectory();
        HooksDialog dialog = new HooksDialog(getViewSite().getShell(), workDir);
        dialog.open();
    }

    private void showMemoryDialog() {
        String workDir = getDefaultWorkingDirectory();
        MemoryDialog dialog = new MemoryDialog(getViewSite().getShell(), workDir);
        dialog.open();
    }

    private void showHistoryDialog() {
        String workDir = getDefaultWorkingDirectory();
        SessionHistoryDialog dialog = new SessionHistoryDialog(
            getViewSite().getShell(), workDir, sessionManager);
        if (dialog.open() == Window.OK) {
            String sessionId = dialog.getSelectedSessionId();
            if (sessionId != null) {
                resumeSession(sessionId);
            }
        }
    }

    private void showSkillsDialog() {
        SkillsDialog dialog = new SkillsDialog(getViewSite().getShell());
        dialog.open();
    }

    private void switchToModel(String newModelId) {
        // Validate it's a known model
        boolean valid = false;
        for (String[] m : AVAILABLE_MODELS) {
            if (m[0].equals(newModelId)) {
                valid = true;
                break;
            }
        }
        if (!valid) {
            showInfoMessage("Unknown model: " + newModelId
                + ". Available: claude-sonnet-4-5, claude-opus-4, claude-haiku-3-5");
            return;
        }

        // Update preferences
        IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();
        prefs.setValue(PreferenceConstants.MODEL, newModelId);

        showInfoMessage("Switching model to: " + newModelId + "...");

        // Restart CLI with new model
        saveCurrentSession();
        if (cliManager.isRunning()) {
            cliManager.stop();
        }

        // Rebuild model
        ConversationModel oldModel = model;
        model = new ConversationModel();
        model.addListener(this);
        cliManager.removeMessageListener(oldModel);
        cliManager.addMessageListener(model);

        autoStartCli();
    }

    private String mapModelToShortName(String modelId) {
        if (modelId == null) return "";
        if (modelId.contains("sonnet")) return "sonnet";
        if (modelId.contains("opus")) return "opus";
        if (modelId.contains("haiku")) return "haiku";
        return modelId;
    }

    private void clearConversation() {
        hideThinkingIndicator();
        model.clear();
    }

    private void scrollToBottom() {
        messageContainer.layout(true, true);
        int cw = scrolledMessages.getClientArea().width;
        scrolledMessages.setMinSize(messageContainer.computeSize(cw > 0 ? cw : SWT.DEFAULT, SWT.DEFAULT));
        scrolledMessages.layout(true, true);

        // Double asyncExec: first pass allows SWT to finish layout painting,
        // second pass sets the origin after the new size is fully reflected.
        Display.getDefault().asyncExec(() -> {
            if (scrolledMessages.isDisposed()) return;
            messageContainer.layout(true, true);
            int cw2 = scrolledMessages.getClientArea().width;
            scrolledMessages.setMinSize(messageContainer.computeSize(cw2 > 0 ? cw2 : SWT.DEFAULT, SWT.DEFAULT));
            Display.getDefault().asyncExec(() -> {
                if (!scrolledMessages.isDisposed()) {
                    scrolledMessages.setOrigin(0, messageContainer.getSize().y);
                }
            });
        });
    }

    /**
     * Shows an animated "Claude is thinking..." indicator in the chat.
     * Called after sending a user message, hidden when the first response token arrives.
     */
    private static String[] buildNormalThinkingFrames() {
        return new String[]{
            "\u2728 Claude is thinking\u2026",
            "\u2728 Claude is thinking\u00B7",
            "\u2728 Claude is thinking\u00B7\u00B7",
            "\u2728 Claude is thinking\u00B7\u00B7\u00B7"
        };
    }

    private static String[] buildExtendedThinkingFrames() {
        return new String[]{
            "\uD83E\uDDE0 Extended thinking\u2026",
            "\uD83E\uDDE0 Extended thinking\u00B7",
            "\uD83E\uDDE0 Extended thinking\u00B7\u00B7",
            "\uD83E\uDDE0 Extended thinking\u00B7\u00B7\u00B7"
        };
    }

    private void showThinkingIndicator() {
        if (thinkingIndicator != null && !thinkingIndicator.isDisposed()) return;

        // Reset to normal frames when a new indicator is created
        thinkingFrames = buildNormalThinkingFrames();

        thinkingIndicator = new Composite(messageContainer, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginLeft = 12;
        layout.marginTop = 4;
        layout.marginBottom = 4;
        thinkingIndicator.setLayout(layout);
        thinkingIndicator.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));
        thinkingIndicator.setBackground(messageContainer.getBackground());

        thinkingLabel = new Label(thinkingIndicator, SWT.NONE);
        thinkingLabel.setBackground(thinkingIndicator.getBackground());

        ThemeManager tm = ThemeManager.getInstance();
        Color dimColor = tm.getColor(tm.dimTextColor);
        thinkingLabel.setForeground(dimColor);
        thinkingLabel.setText(thinkingFrames[0]);

        thinkingDotsState = 0;
        Runnable animator = new Runnable() {
            @Override
            public void run() {
                if (thinkingLabel == null || thinkingLabel.isDisposed()) return;
                String[] frames = thinkingFrames; // read volatile field each tick
                thinkingDotsState = (thinkingDotsState + 1) % frames.length;
                thinkingLabel.setText(frames[thinkingDotsState]);
                Display.getDefault().timerExec(350, this);
            }
        };
        Display.getDefault().timerExec(350, animator);

        scrollToBottom();
    }

    /**
     * Removes the thinking indicator. Called when Claude starts responding.
     */
    private void hideThinkingIndicator() {
        if (thinkingIndicator != null && !thinkingIndicator.isDisposed()) {
            thinkingIndicator.dispose();
            thinkingIndicator = null;
            thinkingLabel = null;
            messageContainer.layout(true, true);
        }
    }

    // ==================== Streaming Timeout ====================

    /**
     * Called whenever a streaming event arrives from the CLI.
     * Resets the inactivity timer and starts the periodic check if needed.
     * Thread-safe: may be called from the NDJSON reader background thread.
     */
    private void touchStreamActivity() {
        lastStreamActivityTime = System.currentTimeMillis();
        if (!streamingActive) {
            streamingActive = true;
            // timerExec() requires the SWT UI thread (calls checkDevice() internally).
            // This method may be called from the background NDJSON reader thread,
            // so we must schedule the timerExec via asyncExec to get onto the UI thread.
            Display display = Display.getDefault();
            if (display != null) {
                display.asyncExec(() -> {
                    // If a previous timeout banner was shown, dismiss it — the stream recovered.
                    if (timeoutErrorBlock != null) {
                        MessageComposite staleWidget = messageWidgetMap.remove(timeoutErrorBlock);
                        if (staleWidget != null && !staleWidget.isDisposed()) {
                            staleWidget.dispose();
                            messageContainer.layout(true, true);
                        }
                        timeoutErrorBlock = null;
                        costBar.setStatus("Streaming...");
                        if (!stopButton.isDisposed()) stopButton.setEnabled(true);
                    }
                    if (!display.isDisposed() && streamingActive) {
                        display.timerExec((int) STREAMING_TIMEOUT_MS, this::checkStreamingTimeout);
                    }
                });
            }
        }
    }

    /**
     * Periodic check that fires STREAMING_TIMEOUT_MS after the last stream activity.
     * Runs on the SWT Display thread (scheduled via timerExec).
     *
     * • If a tool is still actively RUNNING, we extend the deadline — long tool
     *   executions (slow Maven builds, etc.) are expected to have silent periods.
     * • If no activity AND no running tool for the full timeout period, we mark
     *   every stuck tool call as FAILED and display an error message.
     */
    private void checkStreamingTimeout() {
        if (!streamingActive) return; // already cancelled (result received, CLI died, etc.)
        if (messageContainer == null || messageContainer.isDisposed()) return;

        // While a tool is actively executing, extend the timeout window indefinitely.
        // We only want to timeout during the "Claude is thinking" phase (no tool running).
        if (model != null && model.hasRunningToolCalls()) {
            Display.getDefault().timerExec((int) STREAMING_TIMEOUT_MS, this::checkStreamingTimeout);
            return;
        }

        long elapsed = System.currentTimeMillis() - lastStreamActivityTime;
        if (elapsed >= STREAMING_TIMEOUT_MS) {
            // Genuine timeout: no activity and no running tool for the full timeout period
            streamingActive = false;
            model.markActiveToolCallsFailed("No response for " + (elapsed / 1000) + "s — stream timed out");
            hideThinkingIndicator();
            // Create a tracked error block so we can dismiss it if the stream recovers
            timeoutErrorBlock = new MessageBlock(MessageBlock.Role.ERROR);
            MessageBlock.TextSegment textSeg = new MessageBlock.TextSegment();
            textSeg.appendText("\u23F1 Claude stopped responding after "
                + (elapsed / 1000)
                + " seconds with no activity.\nClick \u21BA to reconnect.");
            timeoutErrorBlock.addSegment(textSeg);
            MessageComposite widget = new MessageComposite(messageContainer, timeoutErrorBlock);
            messageWidgetMap.put(timeoutErrorBlock, widget);
            scrollToBottom();
            if (!stopButton.isDisposed()) stopButton.setEnabled(false);
            costBar.setStatus("Timeout");
            costBar.showToast("\u26A0 Response timed out", 4000);
        } else {
            // Activity happened after the last check — reschedule for the remaining time
            long remaining = STREAMING_TIMEOUT_MS - elapsed;
            Display.getDefault().timerExec((int) remaining, this::checkStreamingTimeout);
        }
    }

    /**
     * Cancels any pending streaming timeout (called when a full result arrives,
     * when the CLI process stops, or when the user stops the session).
     * Thread-safe: volatile write.
     */
    private void cancelStreamingTimeout() {
        streamingActive = false;
    }

    private Display getDisplay() {
        return Display.getDefault();
    }

    private void asyncExec(Runnable runnable) {
        Display display = Display.getDefault();
        if (!display.isDisposed()) {
            display.asyncExec(() -> {
                if (messageContainer != null && !messageContainer.isDisposed()) {
                    runnable.run();
                }
            });
        }
    }

    // ==================== JSON Utility ====================

    /**
     * Extract a string value from a JSON string by key.
     * Simple extraction for tool input parsing.
     */
    private String extractJsonStringValue(String json, String key) {
        if (json == null) return null;
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            marker = "\"" + key + "\": \"";
            start = json.indexOf(marker);
        }
        if (start < 0) return null;
        start += marker.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end)
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\");
    }

    @Override
    public void setFocus() {
        if (inputField != null && !inputField.isDisposed()) {
            inputField.setFocus();
        }
    }

    /**
     * Registers a workbench selection listener that updates the selection indicator
     * label whenever the active editor's text selection changes.
     */
    private void registerSelectionListener() {
        IWorkbenchPage page = getSite().getPage();
        ISelectionListener selectionListener = new ISelectionListener() {
            @Override
            public void selectionChanged(IWorkbenchPart part, ISelection selection) {
                if (selectionIndicatorLabel == null || selectionIndicatorLabel.isDisposed()) return;
                if (!(part instanceof ITextEditor)) {
                    selectionIndicatorLabel.setText("");
                    selectionIndicatorLabel.getParent().layout(true);
                    return;
                }
                if (selection instanceof ITextSelection) {
                    ITextSelection textSel = (ITextSelection) selection;
                    int lines = textSel.getEndLine() - textSel.getStartLine() + 1;
                    if (textSel.getLength() <= 0 || lines <= 0) {
                        selectionIndicatorLabel.setText("");
                    } else {
                        selectionIndicatorLabel.setText(lines + " line" + (lines > 1 ? "s" : "") + " selected");
                    }
                    selectionIndicatorLabel.getParent().layout(true);
                }
            }
        };
        page.addSelectionListener(selectionListener);
        // Remove listener when view is disposed
        selectionIndicatorLabel.addDisposeListener(e -> page.removeSelectionListener(selectionListener));
    }

    @Override
    public void dispose() {
        // Save session before disposing
        saveCurrentSession();
        dismissAutocomplete();

        if (model != null) {
            model.removeListener(this);
        }
        if (cliManager != null) {
            cliManager.removeMessageListener(model);
            cliManager.removeStateListener(this);
        }

        // Clear the shared model reference so status bar shows "Disconnected"
        if (Activator.getDefault() != null) {
            Activator.getDefault().setConversationModel(null);
        }
        if (inputBgColor != null) inputBgColor.dispose();
        if (viewBgColor != null) viewBgColor.dispose();
        if (connectedColor != null) connectedColor.dispose();
        if (disconnectedColor != null) disconnectedColor.dispose();
        if (errorColor != null) errorColor.dispose();
        super.dispose();
    }
}
