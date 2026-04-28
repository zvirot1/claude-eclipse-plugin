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
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
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
 * Dialog for managing MCP (Model Context Protocol) server configurations.
 * Supports project-level (.mcp.json) and global (~/.claude.json) servers.
 */
public class McpServersDialog extends TitleAreaDialog {

    private final String projectDir;

    // Tab folder
    private CTabFolder tabFolder;

    // Project servers tab
    private Table projectServersTable;

    // Global servers tab
    private Table globalServersTable;

    // File paths
    private final Path projectMcpPath;   // {projectDir}/.mcp.json
    private final Path globalClaudePath; // ~/.claude.json

    private Font monoFont;

    public McpServersDialog(Shell parentShell, String projectDir) {
        super(parentShell);
        this.projectDir = projectDir != null ? projectDir : System.getProperty("user.home");
        this.projectMcpPath = Paths.get(this.projectDir, ".mcp.json");
        this.globalClaudePath = Paths.get(System.getProperty("user.home"), ".claude.json");
        setShellStyle(SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MAX | SWT.APPLICATION_MODAL);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Claude Code - MCP Servers");
        shell.setSize(900, 600);
        Rectangle screen = shell.getDisplay().getPrimaryMonitor().getBounds();
        shell.setLocation((screen.width - 900) / 2, (screen.height - 600) / 2);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle("MCP Server Configuration");
        setMessage("Manage Model Context Protocol servers. Project servers are stored in .mcp.json, "
            + "global servers in ~/.claude.json.");

        Composite area = (Composite) super.createDialogArea(parent);
        ThemeManager tm = ThemeManager.getInstance();
        monoFont = new Font(parent.getDisplay(), tm.getMonoFontName(), 10, SWT.NORMAL);

        tabFolder = new CTabFolder(area, SWT.BORDER | SWT.FLAT);
        tabFolder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        tabFolder.setSimple(false);

        createServersTab(tabFolder, "Project Servers (.mcp.json)", true);
        createServersTab(tabFolder, "Global Servers (~/.claude.json)", false);

        tabFolder.setSelection(0);
        loadAllServers();

        return area;
    }

    private void createServersTab(CTabFolder folder, String title, boolean isProject) {
        CTabItem tab = new CTabItem(folder, SWT.NONE);
        tab.setText(title);

        Composite content = new Composite(folder, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 10;
        layout.marginHeight = 10;
        content.setLayout(layout);

        // Path label
        Label pathLabel = new Label(content, SWT.NONE);
        pathLabel.setText("File: " + (isProject ? projectMcpPath : globalClaudePath));
        pathLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        // Table
        Table table = new Table(content,
            SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI | SWT.V_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridData tableGd = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableGd.heightHint = 280;
        table.setLayoutData(tableGd);

        TableColumn nameCol = new TableColumn(table, SWT.NONE);
        nameCol.setText("Name");
        nameCol.setWidth(150);

        TableColumn cmdCol = new TableColumn(table, SWT.NONE);
        cmdCol.setText("Command");
        cmdCol.setWidth(180);

        TableColumn argsCol = new TableColumn(table, SWT.NONE);
        argsCol.setText("Args");
        argsCol.setWidth(300);

        TableColumn envCol = new TableColumn(table, SWT.NONE);
        envCol.setText("Env Vars");
        envCol.setWidth(200);

        // Button row
        Composite btnRow = new Composite(content, SWT.NONE);
        btnRow.setLayout(new GridLayout(3, false));
        btnRow.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        Button addBtn = new Button(btnRow, SWT.PUSH);
        addBtn.setText("Add Server...");
        addBtn.addListener(SWT.Selection, e -> addServer(table));

        Button editBtn = new Button(btnRow, SWT.PUSH);
        editBtn.setText("Edit...");
        editBtn.addListener(SWT.Selection, e -> editServer(table));

        Button removeBtn = new Button(btnRow, SWT.PUSH);
        removeBtn.setText("Remove");
        removeBtn.addListener(SWT.Selection, e -> {
            int[] indices = table.getSelectionIndices();
            if (indices.length > 0) table.remove(indices);
        });

        tab.setControl(content);

        if (isProject) {
            projectServersTable = table;
        } else {
            globalServersTable = table;
        }
    }

    // ==================== Add / Edit Server ====================

    private void addServer(Table table) {
        McpServerEditDialog editDialog = new McpServerEditDialog(getShell(), monoFont,
            null, null, null, null);
        if (editDialog.open() == TitleAreaDialog.OK) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(0, editDialog.getServerName());
            item.setText(1, editDialog.getCommand());
            item.setText(2, String.join(", ", editDialog.getArgs()));
            item.setText(3, formatEnvVars(editDialog.getEnvVars()));
            // Store structured data
            item.setData("args", editDialog.getArgs());
            item.setData("env", editDialog.getEnvVars());
        }
    }

    private void editServer(Table table) {
        int idx = table.getSelectionIndex();
        if (idx < 0) return;

        TableItem item = table.getItem(idx);
        String name = item.getText(0);
        String command = item.getText(1);

        @SuppressWarnings("unchecked")
        List<String> args = (List<String>) item.getData("args");
        if (args == null) {
            // Parse from display text
            args = parseArgsList(item.getText(2));
        }

        @SuppressWarnings("unchecked")
        Map<String, String> env = (Map<String, String>) item.getData("env");
        if (env == null) {
            env = new LinkedHashMap<>();
        }

        McpServerEditDialog editDialog = new McpServerEditDialog(getShell(), monoFont,
            name, command, args, env);
        if (editDialog.open() == TitleAreaDialog.OK) {
            item.setText(0, editDialog.getServerName());
            item.setText(1, editDialog.getCommand());
            item.setText(2, String.join(", ", editDialog.getArgs()));
            item.setText(3, formatEnvVars(editDialog.getEnvVars()));
            item.setData("args", editDialog.getArgs());
            item.setData("env", editDialog.getEnvVars());
        }
    }

    // ==================== Load ====================

    private void loadAllServers() {
        loadProjectServers();
        loadGlobalServers();
    }

    @SuppressWarnings("unchecked")
    private void loadProjectServers() {
        projectServersTable.removeAll();
        if (!Files.exists(projectMcpPath)) return;

        try {
            String json = new String(Files.readAllBytes(projectMcpPath), StandardCharsets.UTF_8);
            Map<String, Object> root = JsonParser.parseObject(json);
            Map<String, Object> servers = JsonParser.getMap(root, "mcpServers");
            if (servers == null) return;

            for (Map.Entry<String, Object> entry : servers.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    addServerToTable(projectServersTable, entry.getKey(),
                        (Map<String, Object>) entry.getValue());
                }
            }
        } catch (Exception e) {
            Activator.logError("[McpServersDialog] Failed to parse " + projectMcpPath + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private void loadGlobalServers() {
        globalServersTable.removeAll();
        if (!Files.exists(globalClaudePath)) return;

        try {
            String json = new String(Files.readAllBytes(globalClaudePath), StandardCharsets.UTF_8);
            Map<String, Object> root = JsonParser.parseObject(json);

            // User-scope servers (added via `claude mcp add --scope user`) live at
            // the ROOT level of ~/.claude.json under "mcpServers". The CLI also
            // mirrors them per-project under projects[dir].mcpServers but the
            // root level is the authoritative location for global/user-scope.
            Map<String, Object> rootServers = JsonParser.getMap(root, "mcpServers");
            if (rootServers != null) {
                for (Map.Entry<String, Object> entry : rootServers.entrySet()) {
                    if (entry.getValue() instanceof Map) {
                        addServerToTable(globalServersTable, entry.getKey(),
                            (Map<String, Object>) entry.getValue());
                    }
                }
            }

            // Also include any per-project mcpServers stored in the same file
            // (legacy / project-local additions) — these are still global in the
            // sense that they live in the user's home file.
            Map<String, Object> projects = JsonParser.getMap(root, "projects");
            if (projects != null) {
                Map<String, Object> project = JsonParser.getMap(projects, projectDir);
                if (project != null) {
                    Map<String, Object> projServers = JsonParser.getMap(project, "mcpServers");
                    if (projServers != null) {
                        for (Map.Entry<String, Object> entry : projServers.entrySet()) {
                            // Avoid duplicating root-level entries with the same name
                            if (rootServers != null && rootServers.containsKey(entry.getKey())) continue;
                            if (entry.getValue() instanceof Map) {
                                addServerToTable(globalServersTable, entry.getKey(),
                                    (Map<String, Object>) entry.getValue());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Activator.logError("[McpServersDialog] Failed to parse " + globalClaudePath + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private void addServerToTable(Table table, String name, Map<String, Object> serverConfig) {
        String command = JsonParser.getString(serverConfig, "command", "");
        List<Object> argsList = JsonParser.getList(serverConfig, "args");
        Map<String, Object> envMap = JsonParser.getMap(serverConfig, "env");

        List<String> args = new ArrayList<>();
        if (argsList != null) {
            for (Object a : argsList) {
                args.add(a.toString());
            }
        }

        Map<String, String> env = new LinkedHashMap<>();
        if (envMap != null) {
            for (Map.Entry<String, Object> e : envMap.entrySet()) {
                env.put(e.getKey(), e.getValue() != null ? e.getValue().toString() : "");
            }
        }

        TableItem item = new TableItem(table, SWT.NONE);
        item.setText(0, name);
        item.setText(1, command);
        item.setText(2, String.join(", ", args));
        item.setText(3, formatEnvVars(env));
        item.setData("args", args);
        item.setData("env", env);
    }

    // ==================== Save ====================

    private void saveAll() {
        boolean ok = true;
        ok &= saveProjectServers();
        ok &= saveGlobalServers();
        if (ok) {
            setMessage("MCP server configurations saved successfully.");
        }
    }

    @SuppressWarnings("unchecked")
    private boolean saveProjectServers() {
        try {
            Map<String, Object> root;
            if (Files.exists(projectMcpPath)) {
                String existing = new String(Files.readAllBytes(projectMcpPath), StandardCharsets.UTF_8);
                root = JsonParser.parseObject(existing);
            } else {
                root = new LinkedHashMap<>();
            }

            Map<String, Object> servers = buildServersMap(projectServersTable);
            if (servers.isEmpty()) {
                root.remove("mcpServers");
            } else {
                root.put("mcpServers", servers);
            }

            if (root.isEmpty() && !Files.exists(projectMcpPath)) {
                return true;
            }

            Files.createDirectories(projectMcpPath.getParent());
            String json = JsonParser.prettyPrint(JsonParser.toJson(root));
            Files.write(projectMcpPath, json.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            MessageDialog.openError(getShell(), "Save Error",
                "Failed to save " + projectMcpPath + ": " + e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean saveGlobalServers() {
        try {
            Map<String, Object> root;
            if (Files.exists(globalClaudePath)) {
                String existing = new String(Files.readAllBytes(globalClaudePath), StandardCharsets.UTF_8);
                root = JsonParser.parseObject(existing);
            } else {
                root = new LinkedHashMap<>();
            }

            Map<String, Object> servers = buildServersMap(globalServersTable);

            // Save to ROOT-level mcpServers (matches `claude mcp add --scope user`).
            // The CLI also reads this location for global/user-scope servers.
            if (servers.isEmpty()) {
                root.remove("mcpServers");
            } else {
                root.put("mcpServers", servers);
            }

            // Also clean up any legacy project-scoped duplicates of these names
            // so the same server isn't listed twice in different scopes.
            Map<String, Object> projects = JsonParser.getMap(root, "projects");
            if (projects != null) {
                Map<String, Object> project = JsonParser.getMap(projects, projectDir);
                if (project != null) {
                    Map<String, Object> projServers = JsonParser.getMap(project, "mcpServers");
                    if (projServers != null) {
                        for (String name : servers.keySet()) {
                            projServers.remove(name);
                        }
                        if (projServers.isEmpty()) {
                            project.remove("mcpServers");
                        }
                    }
                    if (project.isEmpty()) {
                        projects.remove(projectDir);
                    }
                }
                if (projects.isEmpty()) {
                    root.remove("projects");
                }
            }

            if (root.isEmpty() && !Files.exists(globalClaudePath)) {
                return true;
            }

            String json = JsonParser.prettyPrint(JsonParser.toJson(root));
            Files.write(globalClaudePath, json.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            MessageDialog.openError(getShell(), "Save Error",
                "Failed to save " + globalClaudePath + ": " + e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildServersMap(Table table) {
        Map<String, Object> servers = new LinkedHashMap<>();
        for (TableItem item : table.getItems()) {
            String name = item.getText(0);
            String command = item.getText(1);

            List<String> args = (List<String>) item.getData("args");
            Map<String, String> env = (Map<String, String>) item.getData("env");

            Map<String, Object> serverConfig = new LinkedHashMap<>();
            serverConfig.put("command", command);

            List<Object> argList = new ArrayList<>();
            if (args != null) {
                argList.addAll(args);
            }
            serverConfig.put("args", argList);

            if (env != null && !env.isEmpty()) {
                Map<String, Object> envObj = new LinkedHashMap<>(env);
                serverConfig.put("env", envObj);
            }

            servers.put(name, serverConfig);
        }
        return servers;
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
            saveAll();
            super.okPressed();
        } else if (buttonId == IDialogConstants.CLIENT_ID) {
            saveAll();
        } else {
            super.cancelPressed();
        }
    }

    @Override
    public boolean close() {
        if (monoFont != null && !monoFont.isDisposed()) monoFont.dispose();
        return super.close();
    }

    // ==================== Utilities ====================

    private String formatEnvVars(Map<String, String> env) {
        if (env == null || env.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        return sb.toString();
    }

    private List<String> parseArgsList(String display) {
        List<String> args = new ArrayList<>();
        if (display == null || display.trim().isEmpty()) return args;
        for (String arg : display.split(",\\s*")) {
            String trimmed = arg.trim();
            if (!trimmed.isEmpty()) args.add(trimmed);
        }
        return args;
    }

    // ==================== Inner Edit Dialog ====================

    /**
     * Sub-dialog for adding/editing a single MCP server.
     */
    static class McpServerEditDialog extends TitleAreaDialog {

        private Text nameField;
        private Text commandField;
        private Text argsField;
        private Table envTable;

        private final Font monoFont;
        private final String initialName;
        private final String initialCommand;
        private final List<String> initialArgs;
        private final Map<String, String> initialEnv;

        // Results
        private String serverName;
        private String command;
        private List<String> args;
        private Map<String, String> envVars;

        McpServerEditDialog(Shell parent, Font monoFont,
                           String name, String command,
                           List<String> args, Map<String, String> env) {
            super(parent);
            this.monoFont = monoFont;
            this.initialName = name != null ? name : "";
            this.initialCommand = command != null ? command : "";
            this.initialArgs = args != null ? args : new ArrayList<>();
            this.initialEnv = env != null ? env : new LinkedHashMap<>();
            setShellStyle(SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
        }

        @Override
        protected void configureShell(Shell shell) {
            super.configureShell(shell);
            shell.setText(initialName.isEmpty() ? "Add MCP Server" : "Edit MCP Server");
            shell.setSize(600, 500);
            Rectangle screen = shell.getDisplay().getPrimaryMonitor().getBounds();
            shell.setLocation((screen.width - 600) / 2, (screen.height - 500) / 2);
        }

        @Override
        protected Control createDialogArea(Composite parent) {
            setTitle(initialName.isEmpty() ? "Add MCP Server" : "Edit MCP Server");
            setMessage("Configure the MCP server connection details.");

            Composite area = (Composite) super.createDialogArea(parent);
            Composite content = new Composite(area, SWT.NONE);
            GridLayout layout = new GridLayout(2, false);
            layout.marginWidth = 12;
            layout.marginHeight = 10;
            content.setLayout(layout);
            content.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

            // Name
            new Label(content, SWT.NONE).setText("Server Name:");
            nameField = new Text(content, SWT.BORDER);
            nameField.setText(initialName);
            nameField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

            // Command
            new Label(content, SWT.NONE).setText("Command:");
            commandField = new Text(content, SWT.BORDER);
            commandField.setText(initialCommand);
            commandField.setFont(monoFont);
            commandField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

            // Args (multi-line, one per line)
            Label argsLabel = new Label(content, SWT.NONE);
            argsLabel.setText("Args (one per line):");
            argsLabel.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));

            argsField = new Text(content, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL);
            argsField.setText(String.join("\n", initialArgs));
            argsField.setFont(monoFont);
            GridData argsGd = new GridData(SWT.FILL, SWT.FILL, true, false);
            argsGd.heightHint = 80;
            argsField.setLayoutData(argsGd);

            // Environment variables
            Label envLabel = new Label(content, SWT.NONE);
            envLabel.setText("Env Variables:");
            envLabel.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));

            Composite envPanel = new Composite(content, SWT.NONE);
            envPanel.setLayout(new GridLayout(1, false));
            envPanel.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

            envTable = new Table(envPanel, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
            envTable.setHeaderVisible(true);
            envTable.setLinesVisible(true);
            GridData envTableGd = new GridData(SWT.FILL, SWT.FILL, true, true);
            envTableGd.heightHint = 100;
            envTable.setLayoutData(envTableGd);

            TableColumn keyCol = new TableColumn(envTable, SWT.NONE);
            keyCol.setText("Key");
            keyCol.setWidth(200);

            TableColumn valCol = new TableColumn(envTable, SWT.NONE);
            valCol.setText("Value");
            valCol.setWidth(250);

            // Populate env table
            for (Map.Entry<String, String> e : initialEnv.entrySet()) {
                TableItem item = new TableItem(envTable, SWT.NONE);
                item.setText(0, e.getKey());
                item.setText(1, e.getValue());
            }

            // Env add/remove buttons
            Composite envBtns = new Composite(envPanel, SWT.NONE);
            envBtns.setLayout(new GridLayout(4, false));

            Text envKeyText = new Text(envBtns, SWT.BORDER);
            envKeyText.setMessage("KEY");
            GridData keyGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
            keyGd.widthHint = 120;
            envKeyText.setLayoutData(keyGd);

            Text envValText = new Text(envBtns, SWT.BORDER);
            envValText.setMessage("value");
            GridData valGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
            valGd.widthHint = 150;
            envValText.setLayoutData(valGd);

            Button envAddBtn = new Button(envBtns, SWT.PUSH);
            envAddBtn.setText("Add");
            envAddBtn.addListener(SWT.Selection, e -> {
                String key = envKeyText.getText().trim();
                String val = envValText.getText().trim();
                if (!key.isEmpty()) {
                    TableItem item = new TableItem(envTable, SWT.NONE);
                    item.setText(0, key);
                    item.setText(1, val);
                    envKeyText.setText("");
                    envValText.setText("");
                }
            });

            Button envRemoveBtn = new Button(envBtns, SWT.PUSH);
            envRemoveBtn.setText("Remove");
            envRemoveBtn.addListener(SWT.Selection, e -> {
                int[] indices = envTable.getSelectionIndices();
                if (indices.length > 0) envTable.remove(indices);
            });

            return area;
        }

        @Override
        protected void okPressed() {
            serverName = nameField.getText().trim();
            command = commandField.getText().trim();

            if (serverName.isEmpty() || command.isEmpty()) {
                setErrorMessage("Server name and command are required.");
                return;
            }

            // Parse args
            args = new ArrayList<>();
            for (String line : argsField.getText().split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) args.add(trimmed);
            }

            // Parse env
            envVars = new LinkedHashMap<>();
            for (TableItem item : envTable.getItems()) {
                String key = item.getText(0).trim();
                if (!key.isEmpty()) {
                    envVars.put(key, item.getText(1));
                }
            }

            super.okPressed();
        }

        String getServerName() { return serverName; }
        String getCommand() { return command; }
        List<String> getArgs() { return args; }
        Map<String, String> getEnvVars() { return envVars; }
    }
}
