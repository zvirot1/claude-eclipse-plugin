package com.anthropic.eclipse.claude.cli;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.*;
import org.eclipse.swt.events.*;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.core.resources.*;
import org.eclipse.ui.*;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Eclipse View for Claude Code CLI integration.
 * Provides a terminal-like panel for running Claude Code commands.
 */
public class ClaudeCodeView extends ViewPart {

    public static final String ID = "com.anthropic.eclipse.claude.cli.ClaudeCodeView";

    private StyledText outputText;
    private Text promptInput;
    private Button runButton, stopButton;
    private Label statusLabel;
    private Combo workDirCombo;
    private Text cliPathText;

    private ClaudeCodeCLI cli;
    private Color addedColor, removedColor, infoColor, errorColor, promptColor;
    private Font monoFont;

    @Override
    public void createPartControl(Composite parent) {
        cli = new ClaudeCodeCLI();

        Display display = parent.getDisplay();
        addedColor   = new Color(display, 80, 200, 80);
        removedColor = new Color(display, 220, 80, 80);
        infoColor    = new Color(display, 100, 150, 255);
        errorColor   = new Color(display, 255, 100, 100);
        promptColor  = new Color(display, 255, 200, 50);
        monoFont     = new Font(display, "Courier New", 10, SWT.NORMAL);

        parent.setLayout(new GridLayout(1, false));

        createStatusBar(parent);
        createConfigArea(parent);
        createOutputArea(parent);
        createInputArea(parent);

        parent.addDisposeListener(e -> disposeResources());

        refreshStatus();
    }

    private void createStatusBar(Composite parent) {
        Composite bar = new Composite(parent, SWT.NONE);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        bar.setLayout(new GridLayout(3, false));

        Label title = new Label(bar, SWT.NONE);
        title.setText("Claude Code CLI");
        FontData[] fd = title.getFont().getFontData();
        fd[0].setStyle(SWT.BOLD);
        title.setFont(new Font(parent.getDisplay(), fd));
        title.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        statusLabel = new Label(bar, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button refreshBtn = new Button(bar, SWT.PUSH);
        refreshBtn.setText("⟳ Detect CLI");
        refreshBtn.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                cli.detectCLI();
                refreshStatus();
            }
        });
    }

    private void createConfigArea(Composite parent) {
        // Collapsible section
        ExpandBar expandBar = new ExpandBar(parent, SWT.NONE);
        expandBar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        ExpandItem item = new ExpandItem(expandBar, SWT.NONE);
        item.setText("Configuration");

        Composite config = new Composite(expandBar, SWT.NONE);
        config.setLayout(new GridLayout(3, false));

        // CLI Path
        new Label(config, SWT.NONE).setText("CLI Path:");
        cliPathText = new Text(config, SWT.BORDER);
        cliPathText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        cliPathText.setMessage("Auto-detected or enter path to claude binary");

        Button browseBtn = new Button(config, SWT.PUSH);
        browseBtn.setText("Browse...");
        browseBtn.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                FileDialog fd = new FileDialog(getSite().getShell(), SWT.OPEN);
                fd.setText("Select Claude CLI executable");
                String path = fd.open();
                if (path != null) {
                    cliPathText.setText(path);
                    cli.setCLIPath(path);
                    refreshStatus();
                }
            }
        });

        // Working directory
        new Label(config, SWT.NONE).setText("Working Dir:");
        workDirCombo = new Combo(config, SWT.DROP_DOWN);
        workDirCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        populateWorkDirCombo();

        Button browseDir = new Button(config, SWT.PUSH);
        browseDir.setText("Browse...");
        browseDir.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                DirectoryDialog dd = new DirectoryDialog(getSite().getShell());
                dd.setText("Select Working Directory");
                String dir = dd.open();
                if (dir != null) {
                    workDirCombo.setText(dir);
                }
            }
        });

        item.setControl(config);
        item.setHeight(config.computeSize(SWT.DEFAULT, SWT.DEFAULT).y);
        item.setExpanded(false);
    }

    private void populateWorkDirCombo() {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IWorkspaceRoot root = workspace.getRoot();
        for (IProject project : root.getProjects()) {
            if (project.isOpen()) {
                workDirCombo.add(project.getLocation().toOSString());
            }
        }
        if (workDirCombo.getItemCount() > 0) {
            workDirCombo.select(0);
        } else {
            workDirCombo.setText(System.getProperty("user.home"));
        }
    }

    private void createOutputArea(Composite parent) {
        outputText = new StyledText(parent, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
        outputText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        outputText.setFont(monoFont);
        outputText.setBackground(new Color(parent.getDisplay(), 18, 18, 18));  // dark bg
        outputText.setForeground(new Color(parent.getDisplay(), 220, 220, 220));
        outputText.setEditable(false);
        outputText.setWordWrap(false);

        // Quick action buttons above output
        Composite quickActions = new Composite(parent, SWT.NONE);
        quickActions.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        quickActions.setLayout(new GridLayout(5, false));

        createQuickButton(quickActions, "📋 COBOL Field Impact",
            "Find all usages and side effects of field: ", true);
        createQuickButton(quickActions, "🔍 Explain File",
            "Explain the purpose and logic of this COBOL program", false);
        createQuickButton(quickActions, "🐛 Find Bugs",
            "Find potential bugs and logic errors in this COBOL code", false);
        createQuickButton(quickActions, "📊 Dead Code",
            "Find dead code, unused variables, and unreachable paragraphs", false);

        Button clearOutputBtn = new Button(quickActions, SWT.PUSH);
        clearOutputBtn.setText("🗑 Clear");
        clearOutputBtn.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                outputText.setText("");
            }
        });
    }

    private void createQuickButton(Composite parent, String label, String promptPrefix, boolean askInput) {
        Button btn = new Button(parent, SWT.PUSH);
        btn.setText(label);
        btn.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                if (askInput) {
                    InputDialog input = new InputDialog(getSite().getShell(),
                        "Claude Code", promptPrefix, "", null);
                    if (input.open() == org.eclipse.jface.window.Window.OK) {
                        promptInput.setText(promptPrefix + input.getValue());
                        executeCommand();
                    }
                } else {
                    promptInput.setText(promptPrefix);
                    executeCommand();
                }
            }
        });
    }

    private void createInputArea(Composite parent) {
        Composite inputArea = new Composite(parent, SWT.NONE);
        inputArea.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
        inputArea.setLayout(new GridLayout(3, false));

        Label prompt = new Label(inputArea, SWT.NONE);
        prompt.setText("$ claude");
        prompt.setForeground(promptColor);

        promptInput = new Text(inputArea, SWT.BORDER | SWT.SINGLE);
        promptInput.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        promptInput.setFont(monoFont);
        promptInput.setMessage("Enter prompt for Claude Code CLI...");
        promptInput.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.keyCode == SWT.CR) executeCommand();
            }
        });

        Composite btnPanel = new Composite(inputArea, SWT.NONE);
        btnPanel.setLayout(new GridLayout(2, false));

        runButton = new Button(btnPanel, SWT.PUSH);
        runButton.setText("▶ Run");
        runButton.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) { executeCommand(); }
        });

        stopButton = new Button(btnPanel, SWT.PUSH);
        stopButton.setText("■ Stop");
        stopButton.setEnabled(false);
        stopButton.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                cli.stop();
                appendOutput("\n[Stopped by user]\n", errorColor);
                setRunning(false);
            }
        });
    }

    private void executeCommand() {
        String prompt = promptInput.getText().trim();
        if (prompt.isEmpty()) return;
        if (cli.getStatus() == ClaudeCodeCLI.CLIStatus.NOT_INSTALLED) {
            appendOutput("[ERROR] Claude Code CLI not found.\n\n" + ClaudeCodeCLI.getInstallInstructions() + "\n", errorColor);
            return;
        }

        String workDirStr = workDirCombo.getText();
        if (workDirStr.isEmpty()) workDirStr = System.getProperty("user.home");
        File workDir = new File(workDirStr);

        appendOutput("\n$ claude --print \"" + truncate(prompt, 80) + "\"\n", promptColor);
        appendOutput("Working dir: " + workDir.getAbsolutePath() + "\n\n", infoColor);
        promptInput.setText("");
        setRunning(true);

        final String finalPrompt = prompt;
        final File finalWorkDir = workDir;

        cli.runAsync(
            finalPrompt,
            finalWorkDir,
            line -> Display.getDefault().asyncExec(() -> appendOutput(line + "\n", null)),
            fullOutput -> Display.getDefault().asyncExec(() -> {
                appendOutput("\n[Done]\n", infoColor);
                setRunning(false);
            }),
            error -> Display.getDefault().asyncExec(() -> {
                appendOutput("\n[ERROR] " + error + "\n", errorColor);
                setRunning(false);
            })
        );
    }

    /**
     * Called from handlers to run a command on a specific file.
     */
    public void runOnFile(String prompt, Path filePath) {
        workDirCombo.setText(filePath.getParent().toAbsolutePath().toString());
        promptInput.setText(prompt + " " + filePath.getFileName());
        executeCommand();
    }

    private void appendOutput(String text, Color color) {
        if (outputText.isDisposed()) return;
        int start = outputText.getText().length();
        outputText.append(text);
        if (color != null) {
            StyleRange style = new StyleRange();
            style.start = start;
            style.length = text.length();
            style.foreground = color;
            try { outputText.setStyleRange(style); } catch (Exception ignored) {}
        }
        outputText.setTopIndex(outputText.getLineCount() - 1);
    }

    private void setRunning(boolean running) {
        runButton.setEnabled(!running);
        stopButton.setEnabled(running);
        statusLabel.setText(running ? "● Running..." : getStatusText());
    }

    private void refreshStatus() {
        ClaudeCodeCLI.CLIStatus s = cli.getStatus();
        if (s == ClaudeCodeCLI.CLIStatus.NOT_INSTALLED) {
            statusLabel.setText("⚠ CLI not found");
            statusLabel.setForeground(errorColor);
            appendOutput(ClaudeCodeCLI.getInstallInstructions() + "\n", infoColor);
        } else {
            String version = cli.getVersion();
            statusLabel.setText("✓ CLI found: " + cli.getCLIPath() + "  [" + version + "]");
            statusLabel.setForeground(addedColor);
            if (!cliPathText.getText().equals(cli.getCLIPath())) {
                cliPathText.setText(cli.getCLIPath() != null ? cli.getCLIPath() : "");
            }
        }
    }

    private String getStatusText() {
        ClaudeCodeCLI.CLIStatus s = cli.getStatus();
        return s == ClaudeCodeCLI.CLIStatus.FOUND ? "Ready" : "Not installed";
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private void disposeResources() {
        if (addedColor != null)   addedColor.dispose();
        if (removedColor != null) removedColor.dispose();
        if (infoColor != null)    infoColor.dispose();
        if (errorColor != null)   errorColor.dispose();
        if (promptColor != null)  promptColor.dispose();
        if (monoFont != null)     monoFont.dispose();
    }

    @Override public void setFocus() { promptInput.setFocus(); }

    // Simple input dialog
    private static class InputDialog extends org.eclipse.jface.dialogs.InputDialog {
        public InputDialog(Shell parent, String title, String message, String initial,
                           org.eclipse.jface.dialogs.IInputValidator validator) {
            super(parent, title, message, initial, validator);
        }
    }
}
