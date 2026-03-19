package com.anthropic.eclipse.claude.views;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.*;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.part.ViewPart;
import com.anthropic.eclipse.claude.api.ClaudeApiClient;

/**
 * The main Claude AI Chat view panel.
 * Shows conversation history and input area.
 */
public class ClaudeChatView extends ViewPart {

    public static final String ID = "com.anthropic.eclipse.claude.views.ClaudeChatView";

    private StyledText chatDisplay;
    private Text inputText;
    private Button sendButton;
    private Button clearButton;
    private Label statusLabel;
    private ClaudeApiClient apiClient;
    private boolean isLoading = false;

    // Colors
    private Color userBgColor;
    private Color claudeBgColor;
    private Color userTextColor;
    private Color claudeTextColor;
    private Font codeFont;

    @Override
    public void createPartControl(Composite parent) {
        apiClient = new ClaudeApiClient();

        // Initialize colors
        Display display = parent.getDisplay();
        userBgColor = new Color(display, 230, 244, 255);
        claudeBgColor = new Color(display, 245, 245, 245);
        userTextColor = new Color(display, 0, 70, 140);
        claudeTextColor = new Color(display, 30, 30, 30);
        codeFont = new Font(display, "Courier New", 10, SWT.NORMAL);

        // Main layout
        parent.setLayout(new GridLayout(1, false));

        // Top toolbar
        createToolbar(parent);

        // SashForm: chat display + input area
        SashForm sash = new SashForm(parent, SWT.VERTICAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Chat display
        createChatDisplay(sash);

        // Input area
        createInputArea(sash);

        sash.setWeights(new int[]{75, 25});

        // Status bar
        statusLabel = new Label(parent, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
        statusLabel.setText("Ready. Type a message and press Enter or click Send.");

        // Welcome message
        appendMessage("Claude", "שלום! אני Claude, עוזר ה-AI שלך. אוכל לעזור לך עם:\n" +
            "• ניתוח קוד COBOL\n" +
            "• הסבר והבנת קוד\n" +
            "• איתור באגים ושיפורים\n" +
            "• שאלות כלליות\n\n" +
            "לשלוח קוד נבחר: לחץ ימני בעורך > Claude AI > Send to Claude\n" +
            "קיצור מקשים: Ctrl+Shift+S", false);

        // Dispose listener
        parent.addDisposeListener(e -> {
            if (userBgColor != null) userBgColor.dispose();
            if (claudeBgColor != null) claudeBgColor.dispose();
            if (userTextColor != null) userTextColor.dispose();
            if (claudeTextColor != null) claudeTextColor.dispose();
            if (codeFont != null) codeFont.dispose();
        });
    }

    private void createToolbar(Composite parent) {
        Composite toolbar = new Composite(parent, SWT.NONE);
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        toolbar.setLayout(new GridLayout(3, false));

        Label title = new Label(toolbar, SWT.NONE);
        title.setText("Claude AI Chat");
        FontData[] fds = title.getFont().getFontData();
        fds[0].setStyle(SWT.BOLD);
        fds[0].setHeight(11);
        title.setFont(new Font(parent.getDisplay(), fds));
        title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        clearButton = new Button(toolbar, SWT.PUSH);
        clearButton.setText("Clear Chat");
        clearButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                chatDisplay.setText("");
                apiClient.clearHistory();
                statusLabel.setText("Conversation cleared.");
                appendMessage("Claude", "שיחה חדשה. איך אוכל לעזור?", false);
            }
        });

        Button prefsButton = new Button(toolbar, SWT.PUSH);
        prefsButton.setText("⚙ Settings");
        prefsButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                org.eclipse.ui.dialogs.PreferencesUtil.createPreferenceDialogOn(
                    getSite().getShell(),
                    "com.anthropic.eclipse.claude.preferences.ClaudePreferencePage",
                    null, null).open();
            }
        });
    }

    private void createChatDisplay(Composite parent) {
        chatDisplay = new StyledText(parent, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        chatDisplay.setEditable(false);
        chatDisplay.setWordWrap(true);
        chatDisplay.setLeftMargin(8);
        chatDisplay.setRightMargin(8);
        chatDisplay.setTopMargin(8);
        chatDisplay.setLineSpacing(3);
    }

    private void createInputArea(Composite parent) {
        Composite inputArea = new Composite(parent, SWT.NONE);
        inputArea.setLayout(new GridLayout(2, false));

        // Input text field
        inputText = new Text(inputArea, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        GridData inputData = new GridData(SWT.FILL, SWT.FILL, true, true);
        inputText.setLayoutData(inputData);
        inputText.setMessage("Type your message here... (Enter to send, Shift+Enter for new line)");

        // Keyboard: Enter sends, Shift+Enter is newline
        inputText.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.keyCode == SWT.CR && (e.stateMask & SWT.SHIFT) == 0) {
                    e.doit = false;
                    sendMessage();
                }
            }
        });

        // Buttons panel
        Composite buttons = new Composite(inputArea, SWT.NONE);
        buttons.setLayout(new GridLayout(1, false));
        buttons.setLayoutData(new GridData(SWT.RIGHT, SWT.FILL, false, true));

        sendButton = new Button(buttons, SWT.PUSH);
        sendButton.setText("Send");
        sendButton.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        sendButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                sendMessage();
            }
        });
    }

    /**
     * Send the current input message to Claude.
     */
    private void sendMessage() {
        if (isLoading) return;
        String message = inputText.getText().trim();
        if (message.isEmpty()) return;

        inputText.setText("");
        appendMessage("You", message, true);
        setLoading(true);

        // Run API call in background thread
        Thread thread = new Thread(() -> {
            try {
                String response = apiClient.chat(message);
                Display.getDefault().asyncExec(() -> {
                    appendMessage("Claude", response, false);
                    setLoading(false);
                });
            } catch (Exception ex) {
                Display.getDefault().asyncExec(() -> {
                    appendMessage("Error", ex.getMessage(), false);
                    setLoading(false);
                });
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Public method to send code directly from handlers.
     */
    public void sendCode(String prompt, String code) {
        String fullMessage = prompt + "\n\n```\n" + code + "\n```";
        inputText.setText(fullMessage);
        sendMessage();
    }

    private void appendMessage(String sender, String message, boolean isUser) {
        if (chatDisplay.isDisposed()) return;

        String separator = chatDisplay.getText().isEmpty() ? "" : "\n\n";
        String header = sender + ":\n";
        String content = message + "\n";

        int startPos = chatDisplay.getText().length();
        chatDisplay.append(separator + header + content);

        // Style the header
        int headerStart = startPos + separator.length();
        org.eclipse.swt.custom.StyleRange headerStyle = new org.eclipse.swt.custom.StyleRange();
        headerStyle.start = headerStart;
        headerStyle.length = header.length();
        headerStyle.fontStyle = SWT.BOLD;
        headerStyle.foreground = isUser ? userTextColor : claudeTextColor;
        chatDisplay.setStyleRange(headerStyle);

        // Scroll to bottom
        chatDisplay.setTopIndex(chatDisplay.getLineCount() - 1);
    }

    private void setLoading(boolean loading) {
        isLoading = loading;
        sendButton.setEnabled(!loading);
        inputText.setEnabled(!loading);
        statusLabel.setText(loading ? "Claude is thinking..." : "Ready.");
    }

    @Override
    public void setFocus() {
        inputText.setFocus();
    }

    @Override
    public void dispose() {
        super.dispose();
    }
}
