package com.anthropic.eclipse.claude.views;

import com.anthropic.eclipse.claude.Activator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import com.anthropic.eclipse.claude.views.widgets.ThemeManager;

/**
 * Dialog for managing Claude project memory (MEMORY.md).
 * Memory persists context across sessions for a specific project.
 */
public class MemoryDialog extends TitleAreaDialog {

    private final String projectDir;

    // Tab folder
    private CTabFolder tabFolder;

    // Memory editor
    private StyledText memoryText;

    // File path
    private final Path memoryPath;

    private Font monoFont;

    public MemoryDialog(Shell parentShell, String projectDir) {
        super(parentShell);
        this.projectDir = projectDir != null ? projectDir : System.getProperty("user.home");

        // Compute encoded project path (replace / with -)
        String encodedPath = this.projectDir.replace("/", "-");
        this.memoryPath = Paths.get(
            System.getProperty("user.home"), ".claude", "projects",
            encodedPath, "memory", "MEMORY.md");

        setShellStyle(SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MAX | SWT.APPLICATION_MODAL);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Claude Code - Memory & Context");
        shell.setSize(800, 600);
        Rectangle screen = shell.getDisplay().getPrimaryMonitor().getBounds();
        shell.setLocation((screen.width - 800) / 2, (screen.height - 600) / 2);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle("Project Memory");
        setMessage("Edit persistent memory for this project. Claude reads this file "
            + "to maintain context across sessions.");

        Composite area = (Composite) super.createDialogArea(parent);
        ThemeManager tm = ThemeManager.getInstance();
        monoFont = new Font(parent.getDisplay(), tm.getMonoFontName(), 11, SWT.NORMAL);

        tabFolder = new CTabFolder(area, SWT.BORDER | SWT.FLAT);
        tabFolder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        tabFolder.setSimple(false);

        createMemoryTab(tabFolder);
        createTipsTab(tabFolder);

        tabFolder.setSelection(0);
        loadMemory();

        return area;
    }

    private void createMemoryTab(CTabFolder folder) {
        CTabItem tab = new CTabItem(folder, SWT.NONE);
        tab.setText("Project Memory (MEMORY.md)");
        tab.setToolTipText(memoryPath.toString());

        Composite content = new Composite(folder, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 8;
        layout.marginHeight = 8;
        content.setLayout(layout);

        // File path label
        Label pathLabel = new Label(content, SWT.NONE);
        pathLabel.setText("File: " + memoryPath.toString());
        pathLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        // Status label
        Label statusLabel = new Label(content, SWT.NONE);
        boolean exists = Files.exists(memoryPath);
        statusLabel.setText(exists ? "\u2713 File exists" : "\u26A0 Will be created on save");
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        // Text editor
        memoryText = new StyledText(content,
            SWT.MULTI | SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL | SWT.WRAP);
        memoryText.setFont(monoFont);
        GridData textGd = new GridData(SWT.FILL, SWT.FILL, true, true);
        textGd.heightHint = 400;
        memoryText.setLayoutData(textGd);

        tab.setControl(content);
    }

    private void createTipsTab(CTabFolder folder) {
        CTabItem tab = new CTabItem(folder, SWT.NONE);
        tab.setText("Tips & Examples");

        Composite content = new Composite(folder, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        layout.marginHeight = 12;
        content.setLayout(layout);

        StyledText tipsText = new StyledText(content,
            SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        tipsText.setFont(monoFont);
        tipsText.setEditable(false);
        tipsText.setCaret(null);
        GridData tipsGd = new GridData(SWT.FILL, SWT.FILL, true, true);
        tipsGd.heightHint = 400;
        tipsText.setLayoutData(tipsGd);

        tipsText.setText(
            "# Memory Tips\n\n"
            + "Memory files help Claude remember important context about your project.\n"
            + "Claude reads MEMORY.md at the start of each session.\n\n"
            + "## What to include:\n\n"
            + "- Project architecture and key design decisions\n"
            + "- Important file locations and their purposes\n"
            + "- Coding conventions and style preferences\n"
            + "- Known issues or gotchas\n"
            + "- Build/test commands and workflows\n"
            + "- Team agreements and code review standards\n\n"
            + "## Example:\n\n"
            + "```markdown\n"
            + "# Project Memory\n\n"
            + "## Architecture\n"
            + "- Eclipse PDE plugin using SWT/JFace\n"
            + "- Communicates with Claude CLI via NDJSON over stdin/stdout\n"
            + "- No external JSON library - uses custom JsonParser\n\n"
            + "## Build Commands\n"
            + "- Build: mvn clean verify\n"
            + "- Deploy: copy jar to Eclipse dropins/ folder\n"
            + "- Test: restart Eclipse with -clean flag\n\n"
            + "## Conventions\n"
            + "- All dialogs extend TitleAreaDialog\n"
            + "- Use ThemeManager for all colors\n"
            + "- Use monospace font for code/command inputs\n"
            + "```\n\n"
            + "Claude can also add to memory automatically during conversations\n"
            + "when it discovers important project context."
        );

        tab.setControl(content);
    }

    // ==================== Load ====================

    private void loadMemory() {
        try {
            if (Files.exists(memoryPath)) {
                String content = new String(Files.readAllBytes(memoryPath), StandardCharsets.UTF_8);
                memoryText.setText(content);
            } else {
                memoryText.setText("");
            }
        } catch (Exception e) {
            Activator.logError("[MemoryDialog] Failed to read " + memoryPath + ": " + e.getMessage(), e);
            memoryText.setText("");
        }
    }

    // ==================== Save ====================

    private boolean saveMemory() {
        try {
            String content = memoryText.getText();

            if (content.isEmpty() && !Files.exists(memoryPath)) {
                return true; // Don't create empty files
            }

            if (content.isEmpty() && Files.exists(memoryPath)) {
                Files.delete(memoryPath);
                return true;
            }

            Files.createDirectories(memoryPath.getParent());
            Files.write(memoryPath, content.getBytes(StandardCharsets.UTF_8));
            setMessage("Memory saved successfully.");
            return true;
        } catch (Exception e) {
            MessageDialog.openError(getShell(), "Save Error",
                "Failed to save " + memoryPath + ": " + e.getMessage());
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
            saveMemory();
            super.okPressed();
        } else if (buttonId == IDialogConstants.CLIENT_ID) {
            saveMemory();
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
