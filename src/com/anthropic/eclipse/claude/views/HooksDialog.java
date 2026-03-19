package com.anthropic.eclipse.claude.views;

import com.anthropic.eclipse.claude.Activator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.anthropic.eclipse.claude.util.JsonParser;
import com.anthropic.eclipse.claude.views.widgets.ThemeManager;

/**
 * Dialog for managing Claude Code hooks.
 * Hooks run before/after tool calls and on session events.
 */
public class HooksDialog extends TitleAreaDialog {

    private static final String[] EVENT_TYPES = {
        "PreToolUse", "PostToolUse", "SessionStart", "Stop"
    };

    private final String projectDir;

    // Widgets
    private Table hooksTable;
    private Combo eventTypeCombo;
    private Text matcherText;
    private Text commandText;
    private Combo settingsFileCombo;

    // File paths
    private final Path settingsPath;
    private final Path settingsLocalPath;

    private Font monoFont;

    public HooksDialog(Shell parentShell, String projectDir) {
        super(parentShell);
        this.projectDir = projectDir != null ? projectDir : System.getProperty("user.home");
        this.settingsPath = Paths.get(this.projectDir, ".claude", "settings.json");
        this.settingsLocalPath = Paths.get(this.projectDir, ".claude", "settings.local.json");
        setShellStyle(SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MAX | SWT.APPLICATION_MODAL);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Claude Code - Hooks Management");
        shell.setSize(850, 550);
        Rectangle screen = shell.getDisplay().getPrimaryMonitor().getBounds();
        shell.setLocation((screen.width - 850) / 2, (screen.height - 550) / 2);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle("Hooks Management");
        setMessage("Configure hooks that run before/after tool calls and on session events. "
            + "Hooks execute shell commands when triggered.");

        Composite area = (Composite) super.createDialogArea(parent);
        ThemeManager tm = ThemeManager.getInstance();
        monoFont = new Font(parent.getDisplay(), tm.getMonoFontName(), 10, SWT.NORMAL);

        Composite content = new Composite(area, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        layout.marginHeight = 10;
        content.setLayout(layout);
        content.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Description
        Label desc = new Label(content, SWT.WRAP);
        desc.setText("Hooks from .claude/settings.json and .claude/settings.local.json. "
            + "Each hook runs a shell command when the specified event occurs. "
            + "Use a matcher (regex) to filter which tools trigger the hook.");
        GridData descGd = new GridData(SWT.FILL, SWT.TOP, true, false);
        descGd.widthHint = 750;
        desc.setLayoutData(descGd);

        // Table
        hooksTable = new Table(content,
            SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI | SWT.V_SCROLL);
        hooksTable.setHeaderVisible(true);
        hooksTable.setLinesVisible(true);
        GridData tableGd = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableGd.heightHint = 250;
        hooksTable.setLayoutData(tableGd);

        TableColumn eventCol = new TableColumn(hooksTable, SWT.NONE);
        eventCol.setText("Event");
        eventCol.setWidth(120);

        TableColumn matcherCol = new TableColumn(hooksTable, SWT.NONE);
        matcherCol.setText("Matcher");
        matcherCol.setWidth(140);

        TableColumn commandCol = new TableColumn(hooksTable, SWT.NONE);
        commandCol.setText("Command");
        commandCol.setWidth(350);

        TableColumn sourceCol = new TableColumn(hooksTable, SWT.NONE);
        sourceCol.setText("Source");
        sourceCol.setWidth(160);

        // Add controls row
        Composite addRow = new Composite(content, SWT.NONE);
        addRow.setLayout(new GridLayout(6, false));
        addRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        settingsFileCombo = new Combo(addRow, SWT.READ_ONLY);
        settingsFileCombo.setItems(new String[]{"settings.local.json", "settings.json"});
        settingsFileCombo.select(0);

        eventTypeCombo = new Combo(addRow, SWT.READ_ONLY);
        eventTypeCombo.setItems(EVENT_TYPES);
        eventTypeCombo.select(0);

        matcherText = new Text(addRow, SWT.BORDER);
        matcherText.setMessage("Matcher (optional, e.g. Bash|Edit)");
        GridData matcherGd = new GridData(SWT.FILL, SWT.CENTER, false, false);
        matcherGd.widthHint = 150;
        matcherText.setLayoutData(matcherGd);

        commandText = new Text(addRow, SWT.BORDER);
        commandText.setMessage("Shell command");
        commandText.setFont(monoFont);
        commandText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        commandText.addListener(SWT.KeyDown, e -> {
            if (e.keyCode == SWT.CR || e.keyCode == SWT.LF) {
                addHook();
            }
        });

        Button addBtn = new Button(addRow, SWT.PUSH);
        addBtn.setText("Add");
        addBtn.addListener(SWT.Selection, e -> addHook());

        Button removeBtn = new Button(addRow, SWT.PUSH);
        removeBtn.setText("Remove");
        removeBtn.addListener(SWT.Selection, e -> removeSelectedHooks());

        loadHooks();

        return area;
    }

    // ==================== Load ====================

    private void loadHooks() {
        hooksTable.removeAll();
        loadHooksFromFile(settingsPath, "settings.json");
        loadHooksFromFile(settingsLocalPath, "settings.local.json");
    }

    @SuppressWarnings("unchecked")
    private void loadHooksFromFile(Path path, String displayName) {
        if (!Files.exists(path)) return;
        try {
            String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            Map<String, Object> root = JsonParser.parseObject(json);
            Map<String, Object> hooks = JsonParser.getMap(root, "hooks");
            if (hooks == null) return;

            for (String eventType : EVENT_TYPES) {
                List<Object> hookList = JsonParser.getList(hooks, eventType);
                if (hookList == null) continue;
                for (Object hookObj : hookList) {
                    if (hookObj instanceof Map) {
                        Map<String, Object> hook = (Map<String, Object>) hookObj;
                        String command = JsonParser.getString(hook, "command", "");
                        String matcher = JsonParser.getString(hook, "matcher", "");

                        TableItem item = new TableItem(hooksTable, SWT.NONE);
                        item.setText(0, eventType);
                        item.setText(1, matcher);
                        item.setText(2, command);
                        item.setText(3, displayName);
                    }
                }
            }
        } catch (Exception e) {
            Activator.logError("[HooksDialog] Failed to parse " + path + ": " + e.getMessage(), e);
        }
    }

    // ==================== Actions ====================

    private void addHook() {
        String command = commandText.getText().trim();
        if (command.isEmpty()) return;

        String event = eventTypeCombo.getText();
        String matcher = matcherText.getText().trim();
        String file = settingsFileCombo.getText();

        TableItem item = new TableItem(hooksTable, SWT.NONE);
        item.setText(0, event);
        item.setText(1, matcher);
        item.setText(2, command);
        item.setText(3, file);

        commandText.setText("");
        matcherText.setText("");
        commandText.setFocus();
    }

    private void removeSelectedHooks() {
        int[] indices = hooksTable.getSelectionIndices();
        if (indices.length == 0) return;
        hooksTable.remove(indices);
    }

    // ==================== Save ====================

    private void saveAllHooks() {
        // Group table items by source file, then by event type
        Map<String, Map<String, List<Map<String, Object>>>> fileMap = new LinkedHashMap<>();
        fileMap.put("settings.json", new LinkedHashMap<>());
        fileMap.put("settings.local.json", new LinkedHashMap<>());

        for (TableItem item : hooksTable.getItems()) {
            String event = item.getText(0);
            String matcher = item.getText(1);
            String command = item.getText(2);
            String source = item.getText(3);

            Map<String, List<Map<String, Object>>> hooks = fileMap.get(source);
            if (hooks == null) continue;

            hooks.computeIfAbsent(event, k -> new ArrayList<>());
            Map<String, Object> hookEntry = new LinkedHashMap<>();
            hookEntry.put("type", "command");
            hookEntry.put("command", command);
            if (!matcher.isEmpty()) {
                hookEntry.put("matcher", matcher);
            }
            hooks.get(event).add(hookEntry);
        }

        boolean ok = true;
        ok &= saveHooksToFile(settingsPath, fileMap.get("settings.json"));
        ok &= saveHooksToFile(settingsLocalPath, fileMap.get("settings.local.json"));

        if (ok) {
            setMessage("Hooks saved successfully.");
        }
    }

    private boolean saveHooksToFile(Path path, Map<String, List<Map<String, Object>>> hooks) {
        try {
            // Read existing file to preserve non-hooks fields
            Map<String, Object> root;
            if (Files.exists(path)) {
                String existing = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                root = JsonParser.parseObject(existing);
            } else {
                root = new LinkedHashMap<>();
            }

            if (hooks == null || hooks.isEmpty()) {
                root.remove("hooks");
            } else {
                Map<String, Object> hooksMap = new LinkedHashMap<>();
                for (Map.Entry<String, List<Map<String, Object>>> entry : hooks.entrySet()) {
                    hooksMap.put(entry.getKey(), new ArrayList<>(entry.getValue()));
                }
                root.put("hooks", hooksMap);
            }

            if (root.isEmpty() && !Files.exists(path)) {
                return true;
            }

            Files.createDirectories(path.getParent());
            String json = JsonParser.prettyPrint(JsonParser.toJson(root));
            Files.write(path, json.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            MessageDialog.openError(getShell(), "Save Error",
                "Failed to save " + path + ": " + e.getMessage());
            return false;
        }
    }

    // ==================== Buttons ====================

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "Save && Close", true);
        createButton(parent, IDialogConstants.CLIENT_ID, "Apply", false);
        createButton(parent, IDialogConstants.CANCEL_ID, "Cancel", false);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == IDialogConstants.OK_ID) {
            saveAllHooks();
            super.okPressed();
        } else if (buttonId == IDialogConstants.CLIENT_ID) {
            saveAllHooks();
        } else {
            super.cancelPressed();
        }
    }

    @Override
    public boolean close() {
        if (monoFont != null && !monoFont.isDisposed()) monoFont.dispose();
        return super.close();
    }
}
