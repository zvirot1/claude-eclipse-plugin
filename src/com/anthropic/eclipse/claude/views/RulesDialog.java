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
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.StyledText;
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
 * Dialog for managing Claude Code rules and permissions.
 * Provides tabs for editing CLAUDE.md files and managing permission rules.
 */
public class RulesDialog extends TitleAreaDialog {

    private final String projectDir;

    // Tab folder
    private CTabFolder tabFolder;

    // Text editors for markdown rules
    private StyledText projectRulesText;   // CLAUDE.md
    private StyledText localRulesText;     // .claude.local.md
    private StyledText globalRulesText;    // ~/.claude/CLAUDE.md

    // Permissions tab widgets
    private Table permissionsTable;
    private Combo permissionTypeCombo;     // allow / deny / ask
    private Text newPermissionText;
    private Combo settingsFileCombo;       // settings.json / settings.local.json

    // File paths
    private final Path projectRulesPath;
    private final Path localRulesPath;
    private final Path globalRulesPath;
    private final Path settingsPath;
    private final Path settingsLocalPath;

    // Resources to dispose
    private Font monoFont;

    public RulesDialog(Shell parentShell, String projectDir) {
        super(parentShell);
        this.projectDir = projectDir != null ? projectDir : System.getProperty("user.home");

        this.projectRulesPath = Paths.get(this.projectDir, "CLAUDE.md");
        this.localRulesPath = Paths.get(this.projectDir, ".claude.local.md");
        this.globalRulesPath = Paths.get(
            System.getProperty("user.home"), ".claude", "CLAUDE.md");
        this.settingsPath = Paths.get(this.projectDir, ".claude", "settings.json");
        this.settingsLocalPath = Paths.get(this.projectDir, ".claude", "settings.local.json");

        setShellStyle(SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MAX | SWT.APPLICATION_MODAL);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Claude Code - Rules Management");
        shell.setSize(800, 650);
        Rectangle screen = shell.getDisplay().getPrimaryMonitor().getBounds();
        shell.setLocation((screen.width - 800) / 2, (screen.height - 650) / 2);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle("Rules Management");
        setMessage("Edit Claude Code rules (CLAUDE.md) and permissions. "
            + "Changes are saved when you click Save or Apply.");

        Composite area = (Composite) super.createDialogArea(parent);

        ThemeManager tm = ThemeManager.getInstance();
        monoFont = new Font(parent.getDisplay(), tm.getMonoFontName(), 11, SWT.NORMAL);

        tabFolder = new CTabFolder(area, SWT.BORDER | SWT.FLAT);
        tabFolder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        tabFolder.setSimple(false);

        createMarkdownTab(tabFolder, "Project Rules (CLAUDE.md)", projectRulesPath, 0);
        createMarkdownTab(tabFolder, "Local Rules (.claude.local.md)", localRulesPath, 1);
        createMarkdownTab(tabFolder, "Global Rules (~/.claude/CLAUDE.md)", globalRulesPath, 2);
        createPermissionsTab(tabFolder);

        tabFolder.setSelection(0);

        loadAllContent();

        return area;
    }

    // ==================== Tab Creation ====================

    private void createMarkdownTab(CTabFolder folder, String title, Path filePath, int index) {
        CTabItem tab = new CTabItem(folder, SWT.NONE);
        tab.setText(title);
        tab.setToolTipText(filePath.toString());

        Composite content = new Composite(folder, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 8;
        layout.marginHeight = 8;
        content.setLayout(layout);

        // File path label
        Label pathLabel = new Label(content, SWT.NONE);
        pathLabel.setText("File: " + filePath.toString());
        pathLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        // Status label
        Label statusLabel = new Label(content, SWT.NONE);
        boolean exists = Files.exists(filePath);
        statusLabel.setText(exists ? "\u2713 File exists" : "\u26A0 Will be created on save");
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        // Text editor
        StyledText textWidget = new StyledText(content,
            SWT.MULTI | SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL | SWT.WRAP);
        textWidget.setFont(monoFont);
        GridData textGd = new GridData(SWT.FILL, SWT.FILL, true, true);
        textGd.heightHint = 350;
        textWidget.setLayoutData(textGd);

        tab.setControl(content);

        // Store reference
        switch (index) {
            case 0: projectRulesText = textWidget; break;
            case 1: localRulesText = textWidget; break;
            case 2: globalRulesText = textWidget; break;
        }
    }

    private void createPermissionsTab(CTabFolder folder) {
        CTabItem tab = new CTabItem(folder, SWT.NONE);
        tab.setText("Permissions");

        Composite content = new Composite(folder, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 8;
        layout.marginHeight = 8;
        content.setLayout(layout);

        // Description
        Label desc = new Label(content, SWT.WRAP);
        desc.setText("Permission rules from .claude/settings.json and .claude/settings.local.json. "
            + "These control which tools Claude can use without asking.");
        GridData descGd = new GridData(SWT.FILL, SWT.TOP, true, false);
        descGd.widthHint = 700;
        desc.setLayoutData(descGd);

        // Table
        permissionsTable = new Table(content,
            SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI | SWT.V_SCROLL);
        permissionsTable.setHeaderVisible(true);
        permissionsTable.setLinesVisible(true);
        GridData tableGd = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableGd.heightHint = 250;
        permissionsTable.setLayoutData(tableGd);

        TableColumn typeCol = new TableColumn(permissionsTable, SWT.NONE);
        typeCol.setText("Type");
        typeCol.setWidth(80);

        TableColumn patternCol = new TableColumn(permissionsTable, SWT.NONE);
        patternCol.setText("Rule Pattern");
        patternCol.setWidth(420);

        TableColumn sourceCol = new TableColumn(permissionsTable, SWT.NONE);
        sourceCol.setText("Source");
        sourceCol.setWidth(170);

        // Add controls row
        Composite addRow = new Composite(content, SWT.NONE);
        addRow.setLayout(new GridLayout(5, false));
        addRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        settingsFileCombo = new Combo(addRow, SWT.READ_ONLY);
        settingsFileCombo.setItems(new String[]{"settings.local.json", "settings.json"});
        settingsFileCombo.select(0);

        permissionTypeCombo = new Combo(addRow, SWT.READ_ONLY);
        permissionTypeCombo.setItems(new String[]{"allow", "deny", "ask"});
        permissionTypeCombo.select(0);

        newPermissionText = new Text(addRow, SWT.BORDER);
        newPermissionText.setMessage("e.g., Bash(npm test)  or  Edit  or  Read");
        newPermissionText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        // Allow Enter to add
        newPermissionText.addListener(SWT.KeyDown, e -> {
            if (e.keyCode == SWT.CR || e.keyCode == SWT.LF) {
                addPermission();
            }
        });

        Button addBtn = new Button(addRow, SWT.PUSH);
        addBtn.setText("Add");
        addBtn.addListener(SWT.Selection, e -> addPermission());

        Button removeBtn = new Button(addRow, SWT.PUSH);
        removeBtn.setText("Remove");
        removeBtn.addListener(SWT.Selection, e -> removeSelectedPermissions());

        tab.setControl(content);
    }

    // ==================== Load Content ====================

    private void loadAllContent() {
        projectRulesText.setText(readFileOrEmpty(projectRulesPath));
        localRulesText.setText(readFileOrEmpty(localRulesPath));
        globalRulesText.setText(readFileOrEmpty(globalRulesPath));
        loadPermissions();
    }

    private String readFileOrEmpty(Path path) {
        try {
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            Activator.logError("[RulesDialog] Failed to read " + path + ": " + e.getMessage(), e);
        }
        return "";
    }

    private void loadPermissions() {
        permissionsTable.removeAll();
        loadPermissionsFromFile(settingsPath, "settings.json");
        loadPermissionsFromFile(settingsLocalPath, "settings.local.json");
    }

    @SuppressWarnings("unchecked")
    private void loadPermissionsFromFile(Path path, String displayName) {
        if (!Files.exists(path)) return;
        try {
            String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            Map<String, Object> root = JsonParser.parseObject(json);
            Map<String, Object> permissions = JsonParser.getMap(root, "permissions");
            if (permissions == null) return;

            for (String type : new String[]{"allow", "deny", "ask"}) {
                List<Object> rules = JsonParser.getList(permissions, type);
                if (rules == null) continue;
                for (Object rule : rules) {
                    TableItem item = new TableItem(permissionsTable, SWT.NONE);
                    item.setText(0, type);
                    item.setText(1, rule.toString());
                    item.setText(2, displayName);
                }
            }
        } catch (Exception e) {
            Activator.logError("[RulesDialog] Failed to parse " + path + ": " + e.getMessage(), e);
        }
    }

    // ==================== Permission Actions ====================

    private void addPermission() {
        String pattern = newPermissionText.getText().trim();
        if (pattern.isEmpty()) return;

        String type = permissionTypeCombo.getText();
        String file = settingsFileCombo.getText();

        TableItem item = new TableItem(permissionsTable, SWT.NONE);
        item.setText(0, type);
        item.setText(1, pattern);
        item.setText(2, file);

        newPermissionText.setText("");
        newPermissionText.setFocus();
    }

    private void removeSelectedPermissions() {
        int[] indices = permissionsTable.getSelectionIndices();
        if (indices.length == 0) return;
        permissionsTable.remove(indices);
    }

    // ==================== Save Content ====================

    private void saveAllContent() {
        int errors = 0;
        errors += saveTextToFile(projectRulesPath, projectRulesText.getText()) ? 0 : 1;
        errors += saveTextToFile(localRulesPath, localRulesText.getText()) ? 0 : 1;
        errors += saveTextToFile(globalRulesPath, globalRulesText.getText()) ? 0 : 1;
        errors += savePermissions() ? 0 : 1;

        if (errors == 0) {
            setMessage("All changes saved successfully.");
        }
    }

    private boolean saveTextToFile(Path path, String content) {
        try {
            if (content.isEmpty() && !Files.exists(path)) {
                return true; // Don't create empty files
            }
            if (content.isEmpty() && Files.exists(path)) {
                // File exists but content cleared - delete it
                Files.delete(path);
                return true;
            }
            Files.createDirectories(path.getParent());
            Files.write(path, content.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            MessageDialog.openError(getShell(), "Save Error",
                "Failed to save " + path + ": " + e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean savePermissions() {
        // Group table items by source file
        Map<String, Map<String, List<String>>> fileMap = new LinkedHashMap<>();
        fileMap.put("settings.json", new LinkedHashMap<>());
        fileMap.put("settings.local.json", new LinkedHashMap<>());

        for (TableItem item : permissionsTable.getItems()) {
            String type = item.getText(0);
            String pattern = item.getText(1);
            String source = item.getText(2);

            Map<String, List<String>> perms = fileMap.get(source);
            if (perms == null) continue;
            perms.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
        }

        boolean ok = true;
        ok &= savePermissionsToFile(settingsPath, fileMap.get("settings.json"));
        ok &= savePermissionsToFile(settingsLocalPath, fileMap.get("settings.local.json"));
        return ok;
    }

    private boolean savePermissionsToFile(Path path, Map<String, List<String>> permissions) {
        try {
            // Read existing file to preserve non-permission fields
            Map<String, Object> root;
            if (Files.exists(path)) {
                String existing = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                root = JsonParser.parseObject(existing);
            } else {
                root = new LinkedHashMap<>();
            }

            if (permissions == null || permissions.isEmpty()) {
                root.remove("permissions");
            } else {
                Map<String, Object> permsMap = new LinkedHashMap<>();
                for (Map.Entry<String, List<String>> entry : permissions.entrySet()) {
                    permsMap.put(entry.getKey(), new ArrayList<>(entry.getValue()));
                }
                root.put("permissions", permsMap);
            }

            if (root.isEmpty() && !Files.exists(path)) {
                return true; // Don't create empty settings files
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
            saveAllContent();
            super.okPressed();
        } else if (buttonId == IDialogConstants.CLIENT_ID) {
            saveAllContent();
            // Don't close - just apply
        } else {
            super.cancelPressed();
        }
    }

    // ==================== Cleanup ====================

    @Override
    public boolean close() {
        if (monoFont != null && !monoFont.isDisposed()) monoFont.dispose();
        return super.close();
    }

    // prettyPrintJson moved to JsonParser.prettyPrint()
}
