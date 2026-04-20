package com.anthropic.eclipse.claude.views;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.anthropic.eclipse.claude.model.SessionInfo;
import com.anthropic.eclipse.claude.session.ClaudeSessionManager;
import com.anthropic.eclipse.claude.util.JsonParser;
import com.anthropic.eclipse.claude.views.widgets.ThemeManager;

/**
 * Dialog for browsing, searching, and resuming past sessions.
 * Shows session history with preview of conversation content.
 */
public class SessionHistoryDialog extends TitleAreaDialog {

    private final String projectDir;
    private final ClaudeSessionManager sessionManager;

    // Widgets
    private Text searchText;
    private Table sessionsTable;
    private StyledText previewText;

    // Data
    private List<SessionInfo> allSessions;
    private String selectedSessionId;

    private Font monoFont;
    private Font uiFont;

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");
    private static final int MAX_PREVIEW_LINES = 15;

    public SessionHistoryDialog(Shell parentShell, String projectDir,
                                 ClaudeSessionManager sessionManager) {
        super(parentShell);
        this.projectDir = projectDir != null ? projectDir : System.getProperty("user.home");
        this.sessionManager = sessionManager;
        setShellStyle(SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MAX | SWT.APPLICATION_MODAL);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Claude Code - Session History");
        shell.setSize(950, 650);
        Rectangle screen = shell.getDisplay().getPrimaryMonitor().getBounds();
        shell.setLocation((screen.width - 950) / 2, (screen.height - 650) / 2);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle("Session History");
        setMessage("Browse past sessions. Select a session to preview its content, "
            + "then click Resume to continue the conversation.");

        Composite area = (Composite) super.createDialogArea(parent);
        ThemeManager tm = ThemeManager.getInstance();
        monoFont = new Font(parent.getDisplay(), tm.getMonoFontName(), 10, SWT.NORMAL);
        uiFont = new Font(parent.getDisplay(), tm.getUIFontName(), 10, SWT.NORMAL);

        Composite content = new Composite(area, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 10;
        layout.marginHeight = 8;
        content.setLayout(layout);
        content.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Search bar
        Composite searchRow = new Composite(content, SWT.NONE);
        searchRow.setLayout(new GridLayout(2, false));
        searchRow.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Label searchLabel = new Label(searchRow, SWT.NONE);
        searchLabel.setText("\uD83D\uDD0D Search:");

        searchText = new Text(searchRow, SWT.BORDER | SWT.SEARCH);
        searchText.setMessage("Filter by summary...");
        searchText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        searchText.addListener(SWT.Modify, e -> filterSessions());

        // SashForm: sessions table (left) + preview (right)
        SashForm sash = new SashForm(content, SWT.HORIZONTAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Left: sessions table
        Composite leftPanel = new Composite(sash, SWT.NONE);
        leftPanel.setLayout(new GridLayout(1, false));

        sessionsTable = new Table(leftPanel,
            SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.SINGLE);
        sessionsTable.setHeaderVisible(true);
        sessionsTable.setLinesVisible(true);
        sessionsTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        TableColumn dateCol = new TableColumn(sessionsTable, SWT.NONE);
        dateCol.setText("Date");
        dateCol.setWidth(130);

        TableColumn summaryCol = new TableColumn(sessionsTable, SWT.NONE);
        summaryCol.setText("Summary");
        summaryCol.setWidth(250);

        TableColumn modelCol = new TableColumn(sessionsTable, SWT.NONE);
        modelCol.setText("Model");
        modelCol.setWidth(100);

        TableColumn msgsCol = new TableColumn(sessionsTable, SWT.NONE);
        msgsCol.setText("Messages");
        msgsCol.setWidth(70);

        // Selection listener - show preview
        sessionsTable.addListener(SWT.Selection, e -> {
            int idx = sessionsTable.getSelectionIndex();
            if (idx >= 0) {
                TableItem item = sessionsTable.getItem(idx);
                String sessionId = (String) item.getData("sessionId");
                showPreview(sessionId);
            }
        });

        // Double-click to resume
        sessionsTable.addListener(SWT.DefaultSelection, e -> {
            int idx = sessionsTable.getSelectionIndex();
            if (idx >= 0) {
                TableItem item = sessionsTable.getItem(idx);
                selectedSessionId = (String) item.getData("sessionId");
                okPressed();
            }
        });

        // Right: preview panel
        Composite rightPanel = new Composite(sash, SWT.NONE);
        rightPanel.setLayout(new GridLayout(1, false));

        Label previewLabel = new Label(rightPanel, SWT.NONE);
        previewLabel.setText("Preview");
        previewLabel.setFont(uiFont);

        previewText = new StyledText(rightPanel,
            SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        previewText.setFont(monoFont);
        previewText.setEditable(false);
        previewText.setCaret(null);
        previewText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        previewText.setText("Select a session to preview its content.");

        sash.setWeights(new int[]{55, 45});

        // Load sessions
        loadSessions();

        return area;
    }

    // ==================== Load Sessions ====================

    private void loadSessions() {
        allSessions = sessionManager.listSessions();
        populateTable(allSessions);
    }

    private void populateTable(List<SessionInfo> sessions) {
        sessionsTable.removeAll();
        for (SessionInfo info : sessions) {
            TableItem item = new TableItem(sessionsTable, SWT.NONE);

            // Date
            String dateStr = info.getStartTime() > 0
                ? DATE_FORMAT.format(new Date(info.getStartTime()))
                : "Unknown";
            item.setText(0, dateStr);

            // Summary
            item.setText(1, info.getSummary() != null ? info.getSummary() : "(no summary)");

            // Model
            item.setText(2, info.getModel() != null ? info.getModel() : "");

            // Message count
            item.setText(3, String.valueOf(info.getMessageCount()));

            // Store session ID
            item.setData("sessionId", info.getSessionId());
        }

        if (!sessions.isEmpty()) {
            sessionsTable.select(0);
            showPreview(sessions.get(0).getSessionId());
        }
    }

    // ==================== Filter ====================

    private void filterSessions() {
        String query = searchText.getText().trim().toLowerCase();
        if (query.isEmpty()) {
            populateTable(allSessions);
            return;
        }

        List<SessionInfo> filtered = new ArrayList<>();
        for (SessionInfo info : allSessions) {
            String summary = info.getSummary() != null ? info.getSummary().toLowerCase() : "";
            String model = info.getModel() != null ? info.getModel().toLowerCase() : "";
            if (summary.contains(query) || model.contains(query)) {
                filtered.add(info);
            }
        }
        populateTable(filtered);
    }

    // ==================== Preview ====================

    private void showPreview(String sessionId) {
        if (sessionId == null) {
            previewText.setText("No session selected.");
            return;
        }

        // Locate the JSONL file by scanning all project directories — the CLI's
        // project encoding (drive-letter colon + separators all collapse to '-')
        // is lossy, so we cannot deterministically reconstruct the directory
        // from `projectDir`. A scan is cheap (one readdir per project) and
        // works regardless of how the path was originally normalized.
        Path jsonlPath = null;
        try {
            Path projectsRoot = Paths.get(
                System.getProperty("user.home"), ".claude", "projects");
            if (Files.isDirectory(projectsRoot)) {
                try (java.util.stream.Stream<Path> dirs = Files.list(projectsRoot)) {
                    java.util.Iterator<Path> it = dirs.iterator();
                    while (it.hasNext()) {
                        Path candidate = it.next().resolve(sessionId + ".jsonl");
                        if (Files.exists(candidate)) {
                            jsonlPath = candidate;
                            break;
                        }
                    }
                }
            }
        } catch (Exception ex) {
            // fall through — jsonlPath stays null
        }

        if (jsonlPath == null) {
            previewText.setText("Session: " + sessionId + "\n\n"
                + "(Session JSONL file not found in ~/.claude/projects/.\n\n"
                + "The session metadata is available but the conversation\n"
                + "content may have been cleaned up.)");
            return;
        }

        try {
            StringBuilder preview = new StringBuilder();
            int lineCount = 0;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(Files.newInputStream(jsonlPath), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && lineCount < MAX_PREVIEW_LINES) {
                    try {
                        Map<String, Object> json = JsonParser.parseObject(line);
                        String type = JsonParser.getString(json, "type");

                        if ("user".equals(type)) {
                            String text = extractMessageText(json);
                            if (!text.isEmpty()) {
                                preview.append("\u25B6 User: ").append(truncate(text, 200)).append("\n\n");
                                lineCount++;
                            }
                        } else if ("assistant".equals(type)) {
                            String text = extractMessageText(json);
                            if (!text.isEmpty()) {
                                preview.append("\u25C0 Assistant: ").append(truncate(text, 300)).append("\n\n");
                                lineCount++;
                            }
                        }
                    } catch (Exception ignored) {
                        // Skip unparseable lines
                    }
                }
            }

            if (preview.length() == 0) {
                preview.append("(No message content found in session file)");
            }

            previewText.setText(preview.toString());

        } catch (Exception e) {
            previewText.setText("Error reading session: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String extractMessageText(Map<String, Object> json) {
        // Try message.content[].text or message.content (string)
        Map<String, Object> message = JsonParser.getMap(json, "message");
        if (message == null) {
            // Some formats have content directly
            Object content = json.get("content");
            if (content instanceof String) return (String) content;
            return "";
        }

        Object content = message.get("content");
        if (content instanceof String) {
            return (String) content;
        }
        if (content instanceof List) {
            List<Object> blocks = (List<Object>) content;
            StringBuilder sb = new StringBuilder();
            for (Object block : blocks) {
                if (block instanceof Map) {
                    Map<String, Object> blockMap = (Map<String, Object>) block;
                    String type = JsonParser.getString(blockMap, "type", "");
                    if ("text".equals(type)) {
                        String text = JsonParser.getString(blockMap, "text", "");
                        if (sb.length() > 0) sb.append(" ");
                        sb.append(text);
                    }
                } else if (block instanceof String) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(block);
                }
            }
            return sb.toString();
        }
        return "";
    }

    private String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }

    // ==================== Actions ====================

    private void deleteSelectedSession() {
        int idx = sessionsTable.getSelectionIndex();
        if (idx < 0) return;

        TableItem item = sessionsTable.getItem(idx);
        String sessionId = (String) item.getData("sessionId");
        String summary = item.getText(1);

        boolean confirm = MessageDialog.openConfirm(getShell(),
            "Delete Session",
            "Delete session \"" + summary + "\"?\n\n"
            + "This will remove the session metadata. "
            + "The CLI session file may remain in ~/.claude/.");

        if (confirm) {
            sessionManager.deleteSession(sessionId);
            allSessions.removeIf(s -> s.getSessionId().equals(sessionId));
            filterSessions();
            previewText.setText("Session deleted.");
        }
    }

    /**
     * Get the session ID selected for resumption.
     */
    public String getSelectedSessionId() {
        return selectedSessionId;
    }

    // ==================== Buttons ====================

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "Resume Selected", true);
        createButton(parent, IDialogConstants.DETAILS_ID, "Delete", false);
        createButton(parent, IDialogConstants.CANCEL_ID, "Close", false);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == IDialogConstants.OK_ID) {
            int idx = sessionsTable.getSelectionIndex();
            if (idx >= 0) {
                TableItem item = sessionsTable.getItem(idx);
                selectedSessionId = (String) item.getData("sessionId");
                super.okPressed();
            } else {
                setErrorMessage("Please select a session to resume.");
            }
        } else if (buttonId == IDialogConstants.DETAILS_ID) {
            deleteSelectedSession();
        } else {
            super.cancelPressed();
        }
    }

    @Override
    public boolean close() {
        if (monoFont != null && !monoFont.isDisposed()) monoFont.dispose();
        if (uiFont != null && !uiFont.isDisposed()) uiFont.dispose();
        return super.close();
    }
}
