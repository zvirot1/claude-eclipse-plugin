package com.anthropic.eclipse.claude.views;

import com.anthropic.eclipse.claude.Activator;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
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
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
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
 * Dialog for managing Claude Code skills and plugins.
 * Split-view: plugin list on left, rich details on right.
 * Two tabs: Installed (with enable/disable) and Available (with install option).
 */
public class SkillsDialog extends TitleAreaDialog {

    // ==================== Plugin Data Model ====================

    private static class PluginInfo {
        String id;
        String marketplace;
        String qualifiedId;
        String description;
        String author;
        boolean enabled;
        boolean installed;
        String installPath;
        // Components found in the plugin
        boolean hasSkills;
        boolean hasCommands;
        boolean hasAgents;
        boolean hasHooks;
        boolean hasMcp;
        // Skill details
        List<SkillInfo> skills = new ArrayList<>();

        String getDisplayName() {
            String spaced = id.replace("-", " ");
            StringBuilder sb = new StringBuilder();
            boolean capitalize = true;
            for (char c : spaced.toCharArray()) {
                if (capitalize && Character.isLetter(c)) {
                    sb.append(Character.toUpperCase(c));
                    capitalize = false;
                } else {
                    sb.append(c);
                }
                if (c == ' ') capitalize = true;
            }
            return sb.toString();
        }

        String getComponentTags() {
            List<String> tags = new ArrayList<>();
            if (hasSkills) tags.add("skills");
            if (hasCommands) tags.add("commands");
            if (hasAgents) tags.add("agents");
            if (hasHooks) tags.add("hooks");
            if (hasMcp) tags.add("mcp");
            return tags.isEmpty() ? "" : String.join(" \u2022 ", tags);
        }
    }

    private static class SkillInfo {
        String name;
        String description;
    }

    // ==================== Paths ====================

    private final Path settingsPath;
    private final Path marketplacesDir;
    private final Path installedPluginsPath;
    private final Path localSkillsDir;          // custom user skills

    // ==================== Widgets ====================

    private CTabFolder tabFolder;

    // Local skills tab
    private Table localSkillsTable;
    private CTabFolder localFileTabFolder;

    // Installed tab
    private Table installedTable;
    private CTabFolder installedFileTabFolder;

    // Available tab
    private Table availableTable;
    private CTabFolder availableFileTabFolder;
    private Text searchText;

    // ==================== Data ====================

    private final List<PluginInfo> allPlugins = new ArrayList<>();
    private final List<PluginInfo> localSkills = new ArrayList<>();
    private Map<String, Object> enabledPlugins = new LinkedHashMap<>();

    // ==================== Resources ====================

    private Font monoFont;
    private Font uiBoldFont;
    private Color enabledColor;
    private Color disabledColor;
    private Color accentColor;

    public SkillsDialog(Shell parentShell) {
        super(parentShell);
        String home = System.getProperty("user.home");
        this.settingsPath = Paths.get(home, ".claude", "settings.json");
        this.marketplacesDir = Paths.get(home, ".claude", "plugins", "marketplaces");
        this.installedPluginsPath = Paths.get(home, ".claude", "plugins", "installed_plugins.json");
        this.localSkillsDir = Paths.get(home, "skills", "skills");
        setShellStyle(SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MAX | SWT.APPLICATION_MODAL);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Claude Code - Skills & Plugins");
        shell.setSize(1000, 700);
        Rectangle screen = shell.getDisplay().getPrimaryMonitor().getBounds();
        shell.setLocation((screen.width - 1000) / 2, (screen.height - 700) / 2);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle("Skills & Plugins");
        setMessage("Browse, enable, and manage Claude Code plugins and skills.");

        Composite area = (Composite) super.createDialogArea(parent);
        ThemeManager tm = ThemeManager.getInstance();
        monoFont = new Font(parent.getDisplay(), tm.getMonoFontName(), 10, SWT.NORMAL);
        uiBoldFont = new Font(parent.getDisplay(), tm.getUIFontName(), 11, SWT.BOLD);
        enabledColor = new Color(parent.getDisplay(), 40, 140, 70);
        disabledColor = new Color(parent.getDisplay(), 150, 150, 150);
        accentColor = new Color(parent.getDisplay(), 70, 120, 200);

        tabFolder = new CTabFolder(area, SWT.BORDER | SWT.FLAT);
        tabFolder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        tabFolder.setSimple(false);

        createLocalSkillsTab(tabFolder);
        createInstalledTab(tabFolder);
        createAvailableTab(tabFolder);

        tabFolder.setSelection(0);

        loadData();
        refreshLocalSkillsTable();
        refreshInstalledTable();
        refreshAvailableTable(null);

        return area;
    }

    // ==================== Local Skills Tab ====================

    private void createLocalSkillsTab(CTabFolder folder) {
        CTabItem tab = new CTabItem(folder, SWT.NONE);
        tab.setText("  \uD83D\uDCC1 Local Skills  ");

        SashForm sash = new SashForm(folder, SWT.HORIZONTAL);

        // Left: skills list
        Composite leftPanel = new Composite(sash, SWT.NONE);
        leftPanel.setLayout(new GridLayout(1, false));

        Label listLabel = new Label(leftPanel, SWT.NONE);
        listLabel.setText("Custom skills from ~/skills/skills/");
        listLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        localSkillsTable = new Table(leftPanel,
            SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.SINGLE);
        localSkillsTable.setHeaderVisible(true);
        localSkillsTable.setLinesVisible(true);
        localSkillsTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        TableColumn nameCol = new TableColumn(localSkillsTable, SWT.NONE);
        nameCol.setText("Skill");
        nameCol.setWidth(170);

        TableColumn descCol = new TableColumn(localSkillsTable, SWT.NONE);
        descCol.setText("Description");
        descCol.setWidth(260);

        localSkillsTable.addListener(SWT.Selection, e -> showLocalSkillDetail());

        // Buttons
        Composite btnRow = new Composite(leftPanel, SWT.NONE);
        btnRow.setLayout(new GridLayout(2, false));
        btnRow.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));

        Button openFolderBtn = new Button(btnRow, SWT.PUSH);
        openFolderBtn.setText("\uD83D\uDCC2 Open Folder");
        openFolderBtn.addListener(SWT.Selection, e -> {
            try {
                Runtime.getRuntime().exec(new String[]{"open", localSkillsDir.toString()});
            } catch (Exception ignored) {}
        });

        Button refreshBtn = new Button(btnRow, SWT.PUSH);
        refreshBtn.setText("\u21BB Refresh");
        refreshBtn.addListener(SWT.Selection, e -> {
            scanLocalSkills();
            refreshLocalSkillsTable();
        });

        // Right: file tabs panel
        localFileTabFolder = new CTabFolder(sash, SWT.BORDER | SWT.FLAT | SWT.BOTTOM);
        localFileTabFolder.setSimple(false);
        localFileTabFolder.setTabHeight(22);

        // Placeholder tab shown before any skill is selected
        CTabItem placeholder = new CTabItem(localFileTabFolder, SWT.NONE);
        placeholder.setText("\u2139 Info");
        StyledText placeholderText = new StyledText(localFileTabFolder,
            SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        placeholderText.setText("Select a skill to see its details.");
        placeholderText.setFont(monoFont);
        placeholderText.setEditable(false);
        placeholderText.setCaret(null);
        placeholder.setControl(placeholderText);
        localFileTabFolder.setSelection(0);

        sash.setWeights(new int[]{40, 60});
        tab.setControl(sash);
    }

    // ==================== Installed Tab ====================

    private void createInstalledTab(CTabFolder folder) {
        CTabItem tab = new CTabItem(folder, SWT.NONE);
        tab.setText("  Installed Plugins  ");

        SashForm sash = new SashForm(folder, SWT.HORIZONTAL);

        // Left: table with checkboxes
        Composite leftPanel = new Composite(sash, SWT.NONE);
        leftPanel.setLayout(new GridLayout(1, false));

        Label listLabel = new Label(leftPanel, SWT.NONE);
        listLabel.setText("Toggle checkbox to enable/disable:");
        listLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        installedTable = new Table(leftPanel,
            SWT.BORDER | SWT.CHECK | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.SINGLE);
        installedTable.setHeaderVisible(true);
        installedTable.setLinesVisible(true);
        installedTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        TableColumn nameCol = new TableColumn(installedTable, SWT.NONE);
        nameCol.setText("Plugin");
        nameCol.setWidth(170);

        TableColumn statusCol = new TableColumn(installedTable, SWT.NONE);
        statusCol.setText("Status");
        statusCol.setWidth(80);

        TableColumn componentsCol = new TableColumn(installedTable, SWT.NONE);
        componentsCol.setText("Components");
        componentsCol.setWidth(150);

        // Checkbox toggle
        installedTable.addListener(SWT.Selection, e -> {
            if (e.detail == SWT.CHECK && e.item instanceof TableItem) {
                TableItem item = (TableItem) e.item;
                PluginInfo info = (PluginInfo) item.getData("pluginInfo");
                if (info != null) {
                    info.enabled = item.getChecked();
                    item.setText(1, info.enabled ? "\u2713 Enabled" : "Disabled");
                    item.setForeground(1, info.enabled ? enabledColor : disabledColor);
                }
            } else {
                showInstalledDetail();
            }
        });

        // Right: file tabs panel
        installedFileTabFolder = new CTabFolder(sash, SWT.BORDER | SWT.FLAT | SWT.BOTTOM);
        installedFileTabFolder.setSimple(false);
        installedFileTabFolder.setTabHeight(22);
        CTabItem installedPlaceholder = new CTabItem(installedFileTabFolder, SWT.NONE);
        installedPlaceholder.setText("\u2139 Info");
        StyledText installedPlaceholderText = createDetailText(installedFileTabFolder);
        installedPlaceholderText.setText("Select a plugin to see its details.");
        installedPlaceholder.setControl(installedPlaceholderText);
        installedFileTabFolder.setSelection(0);

        sash.setWeights(new int[]{40, 60});
        tab.setControl(sash);
    }

    // ==================== Available Tab ====================

    private void createAvailableTab(CTabFolder folder) {
        CTabItem tab = new CTabItem(folder, SWT.NONE);
        tab.setText("  Available Plugins  ");

        SashForm sash = new SashForm(folder, SWT.HORIZONTAL);

        // Left: search + table
        Composite leftPanel = new Composite(sash, SWT.NONE);
        leftPanel.setLayout(new GridLayout(1, false));

        searchText = new Text(leftPanel, SWT.BORDER | SWT.SEARCH);
        searchText.setMessage("\uD83D\uDD0D Search plugins...");
        searchText.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        searchText.addListener(SWT.Modify, e -> {
            String query = searchText.getText().trim().toLowerCase();
            refreshAvailableTable(query.isEmpty() ? null : query);
        });

        availableTable = new Table(leftPanel,
            SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.SINGLE);
        availableTable.setHeaderVisible(true);
        availableTable.setLinesVisible(true);
        availableTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        TableColumn nameCol = new TableColumn(availableTable, SWT.NONE);
        nameCol.setText("Plugin");
        nameCol.setWidth(160);

        TableColumn srcCol = new TableColumn(availableTable, SWT.NONE);
        srcCol.setText("Marketplace");
        srcCol.setWidth(120);

        TableColumn componentsCol = new TableColumn(availableTable, SWT.NONE);
        componentsCol.setText("Components");
        componentsCol.setWidth(120);

        availableTable.addListener(SWT.Selection, e -> showAvailableDetail());

        // Double-click to install
        availableTable.addListener(SWT.DefaultSelection, e -> installSelected());

        // Install buttons - prominent row
        Composite btnRow = new Composite(leftPanel, SWT.NONE);
        GridLayout btnLayout = new GridLayout(2, false);
        btnLayout.marginHeight = 4;
        btnRow.setLayout(btnLayout);
        btnRow.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));

        Button installBtn = new Button(btnRow, SWT.PUSH);
        installBtn.setText("  \u2B07 Install Plugin  ");
        installBtn.setFont(uiBoldFont);
        GridData installGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        installGd.heightHint = 32;
        installBtn.setLayoutData(installGd);
        installBtn.addListener(SWT.Selection, e -> installSelected());

        Button copyBtn = new Button(btnRow, SWT.PUSH);
        copyBtn.setText("\uD83D\uDCCB Copy Command");
        copyBtn.addListener(SWT.Selection, e -> {
            int idx = availableTable.getSelectionIndex();
            if (idx >= 0) {
                PluginInfo info = (PluginInfo) availableTable.getItem(idx).getData("pluginInfo");
                if (info != null) {
                    String cmd = "claude plugin install " + info.qualifiedId;
                    copyToClipboard(cmd);
                    setMessage("Copied: " + cmd);
                }
            }
        });

        // Hint label
        Label hintLabel = new Label(leftPanel, SWT.NONE);
        hintLabel.setText("Tip: Double-click a plugin to install it");
        hintLabel.setForeground(disabledColor);

        // Right: file tabs panel
        availableFileTabFolder = new CTabFolder(sash, SWT.BORDER | SWT.FLAT | SWT.BOTTOM);
        availableFileTabFolder.setSimple(false);
        availableFileTabFolder.setTabHeight(22);
        CTabItem availablePlaceholder = new CTabItem(availableFileTabFolder, SWT.NONE);
        availablePlaceholder.setText("\u2139 Info");
        StyledText availablePlaceholderText = createDetailText(availableFileTabFolder);
        availablePlaceholderText.setText("Select a plugin to see its details.");
        availablePlaceholder.setControl(availablePlaceholderText);
        availableFileTabFolder.setSelection(0);

        sash.setWeights(new int[]{40, 60});
        tab.setControl(sash);
    }

    // ==================== Load Data ====================

    private void loadData() {
        allPlugins.clear();
        localSkills.clear();
        loadEnabledPlugins();
        loadInstalledPlugins();
        scanMarketplaces();
        scanLocalSkills();
    }

    private void loadEnabledPlugins() {
        enabledPlugins = new LinkedHashMap<>();
        if (!Files.exists(settingsPath)) return;
        try {
            String json = new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8);
            Map<String, Object> root = JsonParser.parseObject(json);
            Map<String, Object> ep = JsonParser.getMap(root, "enabledPlugins");
            if (ep != null) {
                enabledPlugins = ep;
            }
        } catch (Exception e) {
            Activator.logError("[SkillsDialog] Failed to read settings: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private void loadInstalledPlugins() {
        if (!Files.exists(installedPluginsPath)) return;
        try {
            String json = new String(Files.readAllBytes(installedPluginsPath), StandardCharsets.UTF_8);
            Map<String, Object> root = JsonParser.parseObject(json);
            Map<String, Object> plugins = JsonParser.getMap(root, "plugins");
            if (plugins == null) return;

            for (Map.Entry<String, Object> entry : plugins.entrySet()) {
                String qualifiedId = entry.getKey();
                String[] parts = qualifiedId.split("@", 2);
                String pluginId = parts[0];
                String marketplace = parts.length > 1 ? parts[1] : "unknown";

                PluginInfo info = findOrCreatePlugin(pluginId, marketplace);
                info.installed = true;

                if (entry.getValue() instanceof List) {
                    List<Object> installs = (List<Object>) entry.getValue();
                    if (!installs.isEmpty() && installs.get(0) instanceof Map) {
                        Map<String, Object> install = (Map<String, Object>) installs.get(0);
                        info.installPath = JsonParser.getString(install, "installPath");
                    }
                }

                Object enabledVal = enabledPlugins.get(qualifiedId);
                info.enabled = enabledVal != null && Boolean.TRUE.equals(enabledVal);
            }
        } catch (Exception e) {
            Activator.logError("[SkillsDialog] Failed to read installed plugins: " + e.getMessage(), e);
        }
    }

    private void scanMarketplaces() {
        if (!Files.exists(marketplacesDir)) return;

        try (DirectoryStream<Path> marketplaces = Files.newDirectoryStream(marketplacesDir)) {
            for (Path marketplace : marketplaces) {
                if (!Files.isDirectory(marketplace)) continue;
                String marketplaceName = marketplace.getFileName().toString();

                Path pluginsSubDir = marketplace.resolve("plugins");
                if (!Files.isDirectory(pluginsSubDir)) continue;

                try (DirectoryStream<Path> plugins = Files.newDirectoryStream(pluginsSubDir)) {
                    for (Path pluginDir : plugins) {
                        if (!Files.isDirectory(pluginDir)) continue;
                        String pluginId = pluginDir.getFileName().toString();

                        PluginInfo info = findOrCreatePlugin(pluginId, marketplaceName);

                        // Read plugin.json
                        Path configPath = pluginDir.resolve(".claude-plugin").resolve("plugin.json");
                        if (Files.exists(configPath)) {
                            try {
                                String configJson = new String(
                                    Files.readAllBytes(configPath), StandardCharsets.UTF_8);
                                Map<String, Object> config = JsonParser.parseObject(configJson);
                                String desc = JsonParser.getString(config, "description", "");
                                if (!desc.isEmpty()) info.description = desc;
                                Map<String, Object> authorMap = JsonParser.getMap(config, "author");
                                if (authorMap != null) {
                                    info.author = JsonParser.getString(authorMap, "name", "");
                                }
                            } catch (Exception ignored) {}
                        }

                        // Detect components
                        info.hasSkills = Files.isDirectory(pluginDir.resolve("skills"));
                        info.hasCommands = Files.isDirectory(pluginDir.resolve("commands"));
                        info.hasAgents = Files.isDirectory(pluginDir.resolve("agents"));
                        info.hasHooks = Files.isDirectory(pluginDir.resolve("hooks"));
                        info.hasMcp = Files.isDirectory(pluginDir.resolve(".mcp"));

                        // Load skill details
                        if (info.hasSkills) {
                            loadSkills(info, pluginDir.resolve("skills"));
                        }

                        // Fallback description from README
                        if (info.description == null || info.description.isEmpty()) {
                            info.description = readFirstLine(pluginDir.resolve("README.md"));
                        }
                    }
                }
            }
        } catch (Exception e) {
            Activator.logError("[SkillsDialog] Failed to scan marketplaces: " + e.getMessage(), e);
        }
    }

    private void loadSkills(PluginInfo plugin, Path skillsDir) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(skillsDir)) {
            for (Path entry : entries) {
                Path skillMd = null;
                if (Files.isDirectory(entry)) {
                    skillMd = entry.resolve("SKILL.md");
                } else if (entry.toString().endsWith(".md")) {
                    skillMd = entry;
                }

                if (skillMd != null && Files.exists(skillMd)) {
                    SkillInfo skill = new SkillInfo();
                    try {
                        String content = new String(Files.readAllBytes(skillMd), StandardCharsets.UTF_8);
                        // Parse YAML frontmatter
                        if (content.startsWith("---")) {
                            int end = content.indexOf("---", 3);
                            if (end > 0) {
                                String frontmatter = content.substring(3, end);
                                for (String line : frontmatter.split("\n")) {
                                    line = line.trim();
                                    if (line.startsWith("name:")) {
                                        skill.name = line.substring(5).trim();
                                    } else if (line.startsWith("description:")) {
                                        skill.description = line.substring(12).trim();
                                    }
                                }
                            }
                        }
                        if (skill.name == null) {
                            skill.name = entry.getFileName().toString().replace(".md", "");
                        }
                    } catch (Exception ignored) {
                        skill.name = entry.getFileName().toString();
                    }
                    plugin.skills.add(skill);
                }
            }
        } catch (Exception ignored) {}
    }

    private String readFirstLine(Path file) {
        if (!Files.exists(file)) return "";
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    return trimmed.length() > 120 ? trimmed.substring(0, 117) + "..." : trimmed;
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private PluginInfo findOrCreatePlugin(String pluginId, String marketplace) {
        String qualifiedId = pluginId + "@" + marketplace;
        for (PluginInfo existing : allPlugins) {
            if (existing.qualifiedId.equals(qualifiedId)) {
                return existing;
            }
        }
        PluginInfo info = new PluginInfo();
        info.id = pluginId;
        info.marketplace = marketplace;
        info.qualifiedId = qualifiedId;
        info.description = "";
        info.author = "";
        info.enabled = false;
        info.installed = false;
        allPlugins.add(info);
        return info;
    }

    // ==================== Refresh Tables ====================

    private void refreshInstalledTable() {
        installedTable.removeAll();
        for (PluginInfo info : allPlugins) {
            if (!info.installed) continue;
            TableItem item = new TableItem(installedTable, SWT.NONE);
            item.setText(0, info.getDisplayName());
            item.setText(1, info.enabled ? "\u2713 Enabled" : "Disabled");
            item.setText(2, info.getComponentTags());
            item.setForeground(1, info.enabled ? enabledColor : disabledColor);
            item.setChecked(info.enabled);
            item.setData("pluginInfo", info);
        }
        if (installedTable.getItemCount() > 0) {
            installedTable.select(0);
            showInstalledDetail();
        }
    }

    private void refreshAvailableTable(String query) {
        availableTable.removeAll();
        for (PluginInfo info : allPlugins) {
            if (info.installed) continue;
            // Filter by query
            if (query != null) {
                String haystack = (info.id + " " + info.description + " " + info.getComponentTags()).toLowerCase();
                if (!haystack.contains(query)) continue;
            }
            TableItem item = new TableItem(availableTable, SWT.NONE);
            item.setText(0, info.getDisplayName());
            item.setText(1, info.marketplace);
            item.setText(2, info.getComponentTags());
            item.setData("pluginInfo", info);
        }
        if (availableTable.getItemCount() > 0) {
            availableTable.select(0);
            showAvailableDetail();
        }
    }

    // ==================== Local Skills ====================

    private void scanLocalSkills() {
        localSkills.clear();
        if (!Files.isDirectory(localSkillsDir)) return;

        try (DirectoryStream<Path> entries = Files.newDirectoryStream(localSkillsDir)) {
            for (Path skillDir : entries) {
                if (!Files.isDirectory(skillDir)) continue;
                String skillId = skillDir.getFileName().toString();
                if (skillId.startsWith(".")) continue; // skip hidden

                PluginInfo info = new PluginInfo();
                info.id = skillId;
                info.marketplace = "local";
                info.qualifiedId = skillId + "@local";
                info.hasSkills = true;
                info.installed = true;
                info.enabled = true;
                info.installPath = skillDir.toString();

                // Read SKILL.md
                Path skillMd = skillDir.resolve("SKILL.md");
                if (Files.exists(skillMd)) {
                    try {
                        String content = new String(Files.readAllBytes(skillMd), StandardCharsets.UTF_8);
                        if (content.startsWith("---")) {
                            int end = content.indexOf("---", 3);
                            if (end > 0) {
                                String frontmatter = content.substring(3, end);
                                SkillInfo skill = new SkillInfo();
                                for (String line : frontmatter.split("\n")) {
                                    line = line.trim();
                                    if (line.startsWith("name:")) {
                                        skill.name = line.substring(5).trim();
                                    } else if (line.startsWith("description:")) {
                                        String desc = line.substring(12).trim();
                                        // Remove surrounding quotes
                                        if (desc.startsWith("\"") && desc.endsWith("\"")) {
                                            desc = desc.substring(1, desc.length() - 1);
                                        }
                                        skill.description = desc;
                                        info.description = desc;
                                    }
                                }
                                if (skill.name == null) skill.name = skillId;
                                info.skills.add(skill);
                            }
                        }
                    } catch (Exception ignored) {}
                }

                if (info.description == null || info.description.isEmpty()) {
                    info.description = readFirstLine(skillDir.resolve("README.md"));
                }

                // Detect additional components
                info.hasCommands = Files.isDirectory(skillDir.resolve("commands"));
                info.hasAgents = Files.isDirectory(skillDir.resolve("agents"));
                info.hasHooks = Files.isDirectory(skillDir.resolve("hooks"));
                info.hasMcp = Files.isDirectory(skillDir.resolve(".mcp"));

                // Check for additional files (scripts, templates, etc.)
                try (DirectoryStream<Path> files = Files.newDirectoryStream(skillDir)) {
                    for (Path f : files) {
                        String fname = f.getFileName().toString();
                        if (Files.isDirectory(f) && !fname.startsWith(".")
                            && !fname.equals("skills") && !fname.equals("commands")
                            && !fname.equals("agents") && !fname.equals("hooks")) {
                            // Extra directories like templates, scripts, reference, etc.
                            if (info.skills.isEmpty()) {
                                SkillInfo si = new SkillInfo();
                                si.name = skillId;
                                info.skills.add(si);
                            }
                        }
                    }
                } catch (Exception ignored) {}

                localSkills.add(info);
            }
        } catch (Exception e) {
            Activator.logError("[SkillsDialog] Failed to scan local skills: " + e.getMessage(), e);
        }
    }

    private void refreshLocalSkillsTable() {
        localSkillsTable.removeAll();
        for (PluginInfo info : localSkills) {
            TableItem item = new TableItem(localSkillsTable, SWT.NONE);
            item.setText(0, info.getDisplayName());
            String desc = info.description != null ? info.description : "";
            if (desc.length() > 80) desc = desc.substring(0, 77) + "...";
            item.setText(1, desc);
            item.setData("pluginInfo", info);
        }
        if (localSkillsTable.getItemCount() > 0) {
            localSkillsTable.select(0);
            showLocalSkillDetail();
        }
    }

    private void showLocalSkillDetail() {
        int idx = localSkillsTable.getSelectionIndex();
        if (idx < 0) return;
        PluginInfo info = (PluginInfo) localSkillsTable.getItem(idx).getData("pluginInfo");
        if (info == null) return;

        // Dispose all existing tabs and their content widgets
        for (CTabItem item : localFileTabFolder.getItems()) {
            if (item.getControl() != null) item.getControl().dispose();
            item.dispose();
        }

        // ── Info tab ──────────────────────────────────────────────
        CTabItem infoTab = new CTabItem(localFileTabFolder, SWT.NONE);
        infoTab.setText("\u2139 Info");
        StyledText infoText = createDetailText(localFileTabFolder);
        infoText.setText(buildLocalSkillInfoText(info));
        infoTab.setControl(infoText);

        // ── File tabs — SKILL.md first, then other readable files ──
        if (info.installPath != null) {
            Path dir = Paths.get(info.installPath);

            // SKILL.md gets priority position
            Path skillMd = dir.resolve("SKILL.md");
            if (Files.exists(skillMd)) addLocalFileTab(skillMd);

            // Remaining readable text files (sorted, skip hidden + SKILL.md)
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                List<Path> sorted = new ArrayList<>();
                for (Path f : stream) sorted.add(f);
                sorted.sort((a, b) -> a.getFileName().toString()
                        .compareToIgnoreCase(b.getFileName().toString()));
                for (Path f : sorted) {
                    String name = f.getFileName().toString();
                    if (name.startsWith(".") || name.equals("SKILL.md")) continue;
                    if (Files.isRegularFile(f) && isReadableTextFile(name)) {
                        addLocalFileTab(f);
                    }
                }
            } catch (Exception ignored) {}
        }

        localFileTabFolder.setSelection(0);
        localFileTabFolder.layout(true, true);
    }

    /** Build the "Info" tab text: name, path, description, contents tree. */
    private String buildLocalSkillInfoText(PluginInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append("\u2550\u2550\u2550 ").append(info.getDisplayName()).append(" \u2550\u2550\u2550\n\n");
        sb.append("  Type:     Local Skill\n");
        sb.append("  Path:     ").append(info.installPath).append("\n\n");

        if (info.description != null && !info.description.isEmpty()) {
            sb.append("\u2500\u2500 Description \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n\n");
            String desc = info.description;
            while (desc.length() > 70) {
                int brk = desc.lastIndexOf(' ', 70);
                if (brk <= 0) brk = 70;
                sb.append("  ").append(desc, 0, brk).append("\n");
                desc = desc.substring(brk).trim();
            }
            if (!desc.isEmpty()) sb.append("  ").append(desc).append("\n");
            sb.append("\n");
        }

        if (info.installPath != null) {
            Path dir = Paths.get(info.installPath);
            sb.append("\u2500\u2500 Contents \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n\n");
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
                List<Path> sortedFiles = new ArrayList<>();
                for (Path f : files) sortedFiles.add(f);
                sortedFiles.sort((a, b) -> {
                    boolean aDir = Files.isDirectory(a), bDir = Files.isDirectory(b);
                    if (aDir != bDir) return aDir ? -1 : 1;
                    return a.getFileName().toString()
                            .compareToIgnoreCase(b.getFileName().toString());
                });
                boolean first = true;
                for (Path f : sortedFiles) {
                    String name = f.getFileName().toString();
                    if (name.startsWith(".")) continue;
                    boolean isDir = Files.isDirectory(f);
                    if (!first) sb.append("  \u2502\n");
                    first = false;
                    sb.append("  \u251C\u2500 ").append(isDir ? "\uD83D\uDCC1 " : "\uD83D\uDCC4 ").append(name);
                    if (isDir) {
                        sb.append("/");
                        try (DirectoryStream<Path> sub = Files.newDirectoryStream(f)) {
                            int c = 0; for (@SuppressWarnings("unused") Path s : sub) c++;
                            sb.append("  (").append(c).append(c == 1 ? " item" : " items").append(")");
                        } catch (Exception ignored) {}
                    } else {
                        try { sb.append("  (").append(formatFileSize(Files.size(f))).append(")"); }
                        catch (Exception ignored) {}
                        String hint = getFileTypeHint(getFileExtension(name));
                        if (!hint.isEmpty()) sb.append("\n  \u2502     ").append(hint);
                    }
                    sb.append("\n");
                }
                sb.append("  \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n");
            } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    /** Add a tab for one readable file, showing its full text content. */
    private void addLocalFileTab(Path file) {
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            CTabItem tab = new CTabItem(localFileTabFolder, SWT.NONE);
            tab.setText(file.getFileName().toString());
            StyledText text = createDetailText(localFileTabFolder);
            text.setText(content);
            tab.setControl(text);
        } catch (Exception ignored) {}
    }

    /** Returns true for file extensions we can render as plain text. */
    private boolean isReadableTextFile(String name) {
        String ext = getFileExtension(name).toLowerCase();
        switch (ext) {
            case "md": case "txt": case "json": case "yaml": case "yml":
            case "properties": case "xml": case "js": case "ts": case "py":
            case "sh": case "java": case "gradle": case "toml": case "ini":
            case "cfg": case "conf": case "css": case "html": case "htm":
            case "license": case "readme":
                return true;
            default:
                return false;
        }
    }

    /** Create a standard read-only StyledText suitable for detail/file content. */
    private StyledText createDetailText(Composite parent) {
        StyledText text = new StyledText(parent,
            SWT.READ_ONLY | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
        text.setFont(monoFont);
        text.setEditable(false);
        text.setCaret(null);
        return text;
    }

    // ==================== Detail Views ====================

    private void showInstalledDetail() {
        int idx = installedTable.getSelectionIndex();
        if (idx < 0) return;
        PluginInfo info = (PluginInfo) installedTable.getItem(idx).getData("pluginInfo");
        populatePluginTabFolder(installedFileTabFolder, info);
    }

    private void showAvailableDetail() {
        int idx = availableTable.getSelectionIndex();
        if (idx < 0) return;
        PluginInfo info = (PluginInfo) availableTable.getItem(idx).getData("pluginInfo");
        populatePluginTabFolder(availableFileTabFolder, info);
    }

    /**
     * Populates a CTabFolder with an Info tab + one tab per readable file
     * found in the plugin's installation directory.
     */
    private void populatePluginTabFolder(CTabFolder folder, PluginInfo info) {
        // Dispose all existing tabs
        for (CTabItem item : folder.getItems()) {
            if (item.getControl() != null) item.getControl().dispose();
            item.dispose();
        }

        // ── Info tab ─────────────────────────────────────────────
        CTabItem infoTab = new CTabItem(folder, SWT.NONE);
        infoTab.setText("\u2139 Info");
        StyledText infoText = createDetailText(folder);
        infoText.setText(buildDetailText(info));
        infoTab.setControl(infoText);

        // ── File tabs from plugin directory ───────────────────────
        Path pluginPath = findPluginPath(info);
        if (pluginPath != null && Files.isDirectory(pluginPath)) {
            // README.md first
            Path readme = pluginPath.resolve("README.md");
            if (Files.exists(readme)) addPluginFileTab(folder, readme);

            // Other readable files (sorted, skip README.md and hidden)
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginPath)) {
                List<Path> sorted = new ArrayList<>();
                for (Path f : stream) sorted.add(f);
                sorted.sort((a, b) -> a.getFileName().toString()
                        .compareToIgnoreCase(b.getFileName().toString()));
                for (Path f : sorted) {
                    String name = f.getFileName().toString();
                    if (name.startsWith(".") || name.equalsIgnoreCase("README.md")) continue;
                    if (Files.isRegularFile(f) && isReadableTextFile(name)) {
                        addPluginFileTab(folder, f);
                    }
                }
            } catch (Exception ignored) {}
        }

        folder.setSelection(0);
        folder.layout(true, true);
    }

    /** Add a single file tab to the given CTabFolder. */
    private void addPluginFileTab(CTabFolder folder, Path file) {
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            CTabItem tab = new CTabItem(folder, SWT.NONE);
            tab.setText(file.getFileName().toString());
            StyledText text = createDetailText(folder);
            text.setText(content);
            tab.setControl(text);
        } catch (Exception ignored) {}
    }

    private String buildDetailText(PluginInfo info) {
        if (info == null) return "No plugin selected.";

        StringBuilder sb = new StringBuilder();

        // Header
        sb.append("\u2550\u2550\u2550 ").append(info.getDisplayName()).append(" \u2550\u2550\u2550\n\n");

        // Status line
        if (info.installed) {
            sb.append("  Status:      ").append(info.enabled ? "\u2705 Enabled" : "\u274C Disabled").append("\n");
        } else {
            sb.append("  Status:      Not installed\n");
        }
        sb.append("  ID:          ").append(info.qualifiedId).append("\n");
        sb.append("  Marketplace: ").append(info.marketplace).append("\n");
        if (info.author != null && !info.author.isEmpty()) {
            sb.append("  Author:      ").append(info.author).append("\n");
        }
        sb.append("\n");

        // Description
        if (info.description != null && !info.description.isEmpty()) {
            sb.append("\u2500\u2500 Description \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n\n");
            sb.append("  ").append(info.description).append("\n\n");
        }

        // Components with visual separators
        String tags = info.getComponentTags();
        if (!tags.isEmpty()) {
            sb.append("\u2500\u2500 Components \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n\n");
            List<String> componentLines = new ArrayList<>();
            if (info.hasSkills)   componentLines.add("  \uD83C\uDFAF Skills     - Specialized knowledge and behaviors");
            if (info.hasCommands) componentLines.add("  \u2318  Commands   - Slash commands (e.g. /commit)");
            if (info.hasAgents)   componentLines.add("  \uD83E\uDD16 Agents     - Autonomous sub-agents");
            if (info.hasHooks)    componentLines.add("  \uD83D\uDD17 Hooks      - Pre/post tool call hooks");
            if (info.hasMcp)      componentLines.add("  \uD83D\uDD0C MCP        - Model Context Protocol servers");
            for (int i = 0; i < componentLines.size(); i++) {
                sb.append(componentLines.get(i)).append("\n");
                if (i < componentLines.size() - 1) {
                    sb.append("  \u2502\n");
                }
            }
            sb.append("\n");
        }

        // Skills detail with card-style separation
        if (!info.skills.isEmpty()) {
            sb.append("\u2500\u2500 Skills \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n\n");
            for (int i = 0; i < info.skills.size(); i++) {
                SkillInfo skill = info.skills.get(i);
                sb.append("  \u250C\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n");
                sb.append("  \u2502 \u25B8 ").append(skill.name).append("\n");
                if (skill.description != null && !skill.description.isEmpty()) {
                    sb.append("  \u2502\n");
                    // Word-wrap description at ~55 chars with box indent
                    String desc = skill.description;
                    while (desc.length() > 55) {
                        int breakAt = desc.lastIndexOf(' ', 55);
                        if (breakAt <= 0) breakAt = 55;
                        sb.append("  \u2502   ").append(desc, 0, breakAt).append("\n");
                        desc = desc.substring(breakAt).trim();
                    }
                    if (!desc.isEmpty()) {
                        sb.append("  \u2502   ").append(desc).append("\n");
                    }
                }
                sb.append("  \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n");
                if (i < info.skills.size() - 1) {
                    sb.append("\n");
                }
            }
            sb.append("\n");
        }

        // Plugin files/directories listing
        Path pluginPath = findPluginPath(info);
        if (pluginPath != null && Files.isDirectory(pluginPath)) {
            sb.append("\u2500\u2500 Files \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n\n");
            try (DirectoryStream<Path> pluginFiles = Files.newDirectoryStream(pluginPath)) {
                List<Path> sortedFiles = new ArrayList<>();
                for (Path f : pluginFiles) sortedFiles.add(f);
                sortedFiles.sort((a, b) -> {
                    boolean aDir = Files.isDirectory(a);
                    boolean bDir = Files.isDirectory(b);
                    if (aDir != bDir) return aDir ? -1 : 1;
                    return a.getFileName().toString().compareToIgnoreCase(
                        b.getFileName().toString());
                });

                boolean first = true;
                for (Path f : sortedFiles) {
                    String name = f.getFileName().toString();
                    if (name.startsWith(".") && !name.equals(".claude-plugin")) continue;
                    boolean isDir = Files.isDirectory(f);

                    if (!first) {
                        sb.append("  \u2502\n");
                    }
                    first = false;

                    sb.append("  \u251C\u2500 ").append(isDir ? "\uD83D\uDCC1 " : "\uD83D\uDCC4 ");
                    sb.append(name);
                    if (isDir) sb.append("/");

                    if (!isDir) {
                        try {
                            long size = Files.size(f);
                            sb.append("  (").append(formatFileSize(size)).append(")");
                        } catch (Exception ignored) {}
                    } else {
                        try (DirectoryStream<Path> subFiles = Files.newDirectoryStream(f)) {
                            int count = 0;
                            for (@SuppressWarnings("unused") Path sf : subFiles) count++;
                            sb.append("  (").append(count).append(count == 1 ? " item" : " items").append(")");
                        } catch (Exception ignored) {}
                    }
                    sb.append("\n");

                    if (!isDir) {
                        String ext = getFileExtension(name);
                        String typeHint = getFileTypeHint(ext);
                        if (!typeHint.isEmpty()) {
                            sb.append("  \u2502     ").append(typeHint).append("\n");
                        }
                    }
                }
                sb.append("  \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n");
            } catch (Exception ignored) {}
            sb.append("\n");
        }

        // README content
        Path readmePath = findPluginReadme(info);
        if (readmePath != null) {
            try {
                String readme = new String(Files.readAllBytes(readmePath), StandardCharsets.UTF_8);
                // Limit to first 40 lines
                String[] lines = readme.split("\n");
                int maxLines = Math.min(lines.length, 40);
                sb.append("\u2500\u2500 README (preview) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n\n");
                for (int i = 0; i < maxLines; i++) {
                    sb.append("  ").append(lines[i]).append("\n");
                }
                if (lines.length > maxLines) {
                    sb.append("\n  ... (").append(lines.length - maxLines).append(" more lines)\n");
                }
            } catch (Exception ignored) {}
        }

        // Install hint for non-installed plugins
        if (!info.installed) {
            sb.append("\n\u2500\u2500 Install \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n\n");
            sb.append("  Run in terminal:\n");
            sb.append("  $ claude plugin install ").append(info.qualifiedId).append("\n\n");
            sb.append("  Or click the 'Install Selected' button below.\n");
        }

        return sb.toString();
    }

    // ==================== Actions ====================

    private void installSelected() {
        int idx = availableTable.getSelectionIndex();
        if (idx < 0) {
            setErrorMessage("Select a plugin to install.");
            return;
        }
        PluginInfo info = (PluginInfo) availableTable.getItem(idx).getData("pluginInfo");
        if (info == null) return;

        boolean confirm = MessageDialog.openConfirm(getShell(),
            "Install Plugin",
            "Install \"" + info.getDisplayName() + "\"?\n\n"
            + "This will run: claude plugin install " + info.qualifiedId + "\n\n"
            + "The installation requires the Claude CLI to be available.");

        if (confirm) {
            String displayName = info.getDisplayName();
            String qid = info.qualifiedId;
            setMessage("Installing \"" + displayName + "\"... Please wait.");
            org.eclipse.swt.widgets.Display display = getShell().getDisplay();

            Thread installThread = new Thread(() -> {
                try {
                    ProcessBuilder pb = new ProcessBuilder("claude", "plugin", "install", qid);
                    pb.redirectErrorStream(true);
                    // Remove CLAUDECODE env var to avoid "nested session" error
                    pb.environment().remove("CLAUDECODE");
                    pb.environment().remove("CLAUDE_CODE_ENTRYPOINT");
                    Process process = pb.start();
                    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    int exitCode = process.waitFor();

                    display.asyncExec(() -> {
                        if (getShell() == null || getShell().isDisposed()) return;
                        if (exitCode == 0) {
                            setMessage("Plugin \"" + displayName + "\" installed successfully!");
                            // Reload all data and refresh all tables
                            loadData();
                            refreshLocalSkillsTable();
                            refreshInstalledTable();
                            refreshAvailableTable(null);
                            // Switch to Installed tab so user sees the result
                            tabFolder.setSelection(1);
                        } else {
                            MessageDialog.openError(getShell(), "Install Failed",
                                "Installation failed (exit code " + exitCode + "):\n\n" + output);
                            setMessage("Installation failed.");
                        }
                    });
                } catch (Exception e) {
                    String errMsg = e.getMessage();
                    display.asyncExec(() -> {
                        if (getShell() == null || getShell().isDisposed()) return;
                        MessageDialog.openError(getShell(), "Install Error",
                            "Failed to run installer: " + errMsg
                            + "\n\nYou can install manually:\n  claude plugin install " + qid);
                        setMessage("Installation error.");
                    });
                }
            });
            installThread.setDaemon(true);
            installThread.start();
        }
    }

    // ==================== Path Helpers ====================

    private Path findPluginPath(PluginInfo info) {
        // Try marketplace path first
        Path marketplacePlugin = marketplacesDir.resolve(info.marketplace)
            .resolve("plugins").resolve(info.id);
        if (Files.isDirectory(marketplacePlugin)) return marketplacePlugin;

        // Try install path
        if (info.installPath != null) {
            Path installDir = Paths.get(info.installPath);
            if (Files.isDirectory(installDir)) return installDir;
        }
        return null;
    }

    private Path findPluginReadme(PluginInfo info) {
        Path pluginPath = findPluginPath(info);
        if (pluginPath != null) {
            Path readme = pluginPath.resolve("README.md");
            if (Files.exists(readme)) return readme;
        }
        return null;
    }

    // ==================== Save ====================

    private boolean saveEnabledPlugins() {
        try {
            Map<String, Object> root;
            if (Files.exists(settingsPath)) {
                String existing = new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8);
                root = JsonParser.parseObject(existing);
            } else {
                root = new LinkedHashMap<>();
            }

            Map<String, Object> newEnabled = new LinkedHashMap<>();
            for (TableItem item : installedTable.getItems()) {
                PluginInfo info = (PluginInfo) item.getData("pluginInfo");
                if (info != null && item.getChecked()) {
                    newEnabled.put(info.qualifiedId, Boolean.TRUE);
                }
            }

            if (newEnabled.isEmpty()) {
                root.remove("enabledPlugins");
            } else {
                root.put("enabledPlugins", newEnabled);
            }

            Files.createDirectories(settingsPath.getParent());
            String json = JsonParser.prettyPrint(JsonParser.toJson(root));
            Files.write(settingsPath, json.getBytes(StandardCharsets.UTF_8));
            setMessage("Plugin settings saved successfully.");
            return true;
        } catch (Exception e) {
            MessageDialog.openError(getShell(), "Save Error",
                "Failed to save settings: " + e.getMessage());
            return false;
        }
    }

    // ==================== File Utilities ====================

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private String getFileExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    private String getFileTypeHint(String ext) {
        switch (ext) {
            case "md": return "\u2139 Markdown document";
            case "json": return "\u2139 JSON configuration";
            case "js": return "\u2139 JavaScript";
            case "ts": return "\u2139 TypeScript";
            case "py": return "\u2139 Python script";
            case "sh": return "\u2139 Shell script";
            case "yaml": case "yml": return "\u2139 YAML configuration";
            case "txt": return "\u2139 Text file";
            case "html": return "\u2139 HTML document";
            case "css": return "\u2139 Stylesheet";
            case "xml": return "\u2139 XML document";
            case "toml": return "\u2139 TOML configuration";
            default: return "";
        }
    }

    // ==================== Utilities ====================

    private void copyToClipboard(String text) {
        org.eclipse.swt.dnd.Clipboard clipboard =
            new org.eclipse.swt.dnd.Clipboard(getShell().getDisplay());
        try {
            clipboard.setContents(
                new Object[]{text},
                new org.eclipse.swt.dnd.Transfer[]{
                    org.eclipse.swt.dnd.TextTransfer.getInstance()
                });
        } finally {
            clipboard.dispose();
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
            saveEnabledPlugins();
            super.okPressed();
        } else if (buttonId == IDialogConstants.CLIENT_ID) {
            saveEnabledPlugins();
        } else {
            super.cancelPressed();
        }
    }

    @Override
    public boolean close() {
        if (monoFont != null && !monoFont.isDisposed()) monoFont.dispose();
        if (uiBoldFont != null && !uiBoldFont.isDisposed()) uiBoldFont.dispose();
        if (enabledColor != null && !enabledColor.isDisposed()) enabledColor.dispose();
        if (disabledColor != null && !disabledColor.isDisposed()) disabledColor.dispose();
        if (accentColor != null && !accentColor.isDisposed()) accentColor.dispose();
        return super.close();
    }
}
