package com.anthropic.eclipse.claude.views.widgets;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

import com.anthropic.eclipse.claude.model.MessageBlock;
import com.anthropic.eclipse.claude.model.MessageBlock.ToolCallSegment;
import com.anthropic.eclipse.claude.model.MessageBlock.ToolStatus;

/**
 * Collapsible section showing a tool call (Read, Edit, Bash, Grep, etc.).
 * Shows a header with tool icon, name, and status, with expandable details.
 */
public class ToolCallComposite extends Composite {

    private final ToolCallSegment toolCall;
    private Label headerLabel;
    private Label statusLabel;
    private Composite detailsArea;
    private StyledText detailsText;
    private boolean expanded = false;

    // Colors
    private Color bgColor;
    private Color headerBgColor;
    private Color runningColor;
    private Color completedColor;
    private Color failedColor;
    private Font monoFont;

    /**
     * Get the tool call segment associated with this widget.
     */
    public ToolCallSegment getToolCall() {
        return toolCall;
    }

    public ToolCallComposite(Composite parent, ToolCallSegment toolCall) {
        super(parent, SWT.NONE);
        this.toolCall = toolCall;
        initColors();
        createUI();
    }

    private void initColors() {
        ThemeManager tm = ThemeManager.getInstance();
        bgColor = tm.getColor(tm.toolBg);
        headerBgColor = tm.getColor(tm.toolHeaderBg);
        runningColor = tm.getColor(tm.toolRunningColor);
        completedColor = tm.getColor(tm.toolCompletedColor);
        failedColor = tm.getColor(tm.toolFailedColor);
        monoFont = new Font(getDisplay(), tm.getMonoFontName(), 10, SWT.NORMAL);
    }

    private void createUI() {
        ThemeManager tm = ThemeManager.getInstance();

        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 8;
        layout.marginHeight = 4;
        layout.verticalSpacing = 2;
        setLayout(layout);
        setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        setBackground(bgColor);

        // Header row
        Composite header = new Composite(this, SWT.NONE);
        GridLayout headerLayout = new GridLayout(3, false);
        headerLayout.marginWidth = 8;
        headerLayout.marginHeight = 4;
        header.setLayout(headerLayout);
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        header.setBackground(headerBgColor);

        // Toggle indicator
        Label toggleLabel = new Label(header, SWT.NONE);
        toggleLabel.setText(expanded ? "\u25BC" : "\u25B6");  // triangle
        toggleLabel.setBackground(headerBgColor);

        // Tool name and summary
        Color headerTextColor = tm.getColor(tm.toolHeaderText);
        Color toggleColor = tm.getColor(tm.toolToggleColor);
        toggleLabel.setForeground(toggleColor);

        headerLabel = new Label(header, SWT.NONE);
        headerLabel.setText(toolCall.getSummary());
        headerLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        headerLabel.setBackground(headerBgColor);
        headerLabel.setForeground(headerTextColor);
        Font boldFont = new Font(getDisplay(), tm.getUIFontName(), 9, SWT.BOLD);
        headerLabel.setFont(boldFont);
        headerLabel.addDisposeListener(e -> { boldFont.dispose(); headerTextColor.dispose(); toggleColor.dispose(); });

        // Status indicator
        statusLabel = new Label(header, SWT.NONE);
        updateStatusDisplay();
        statusLabel.setBackground(headerBgColor);

        // Click to expand/collapse
        Listener toggleListener = e -> toggleExpanded(toggleLabel);
        header.addListener(SWT.MouseDown, toggleListener);
        headerLabel.addListener(SWT.MouseDown, toggleListener);
        toggleLabel.addListener(SWT.MouseDown, toggleListener);
        statusLabel.addListener(SWT.MouseDown, toggleListener);

        // Details area (initially hidden)
        detailsArea = new Composite(this, SWT.NONE);
        GridLayout detailsLayout = new GridLayout(1, false);
        detailsLayout.marginWidth = 8;
        detailsLayout.marginHeight = 4;
        detailsArea.setLayout(detailsLayout);
        GridData detailsGd = new GridData(SWT.FILL, SWT.TOP, true, false);
        detailsGd.exclude = !expanded;
        detailsArea.setLayoutData(detailsGd);
        detailsArea.setVisible(expanded);
        detailsArea.setBackground(bgColor);

        detailsText = new StyledText(detailsArea, SWT.READ_ONLY | SWT.WRAP | SWT.MULTI);
        detailsText.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        detailsText.setFont(monoFont);
        detailsText.setEditable(false);
        detailsText.setCaret(null);
        detailsText.setWordWrap(true);
        detailsText.setBackground(bgColor);
        Color detailsTextColor = tm.getColor(tm.toolDetailsText);
        detailsText.setForeground(detailsTextColor);
        detailsText.addDisposeListener(e -> detailsTextColor.dispose());

        // Show input if available
        updateDetailsText();

        addDisposeListener(e -> {
            bgColor.dispose();
            headerBgColor.dispose();
            runningColor.dispose();
            completedColor.dispose();
            failedColor.dispose();
            monoFont.dispose();
        });
    }

    /**
     * Update the status display based on current tool status.
     */
    public void setStatus(ToolStatus status) {
        toolCall.setStatus(status);
        if (isDisposed()) return;
        Display display = Display.getDefault();
        Runnable updater = () -> {
            if (!isDisposed()) {
                updateStatusDisplay();
                updateDetailsText();
                headerLabel.setText(toolCall.getSummary());
                getParent().layout(true, true);
            }
        };
        // If already on the UI thread, run immediately so the change is visible
        // before any other asyncExec items run.
        if (display.getThread() == Thread.currentThread()) {
            updater.run();
        } else {
            display.asyncExec(updater);
        }
    }

    /**
     * Set the output text (tool result).
     */
    public void setOutput(String output) {
        toolCall.setOutput(output);
        if (isDisposed()) return;
        Display display = Display.getDefault();
        Runnable updater = () -> { if (!isDisposed()) updateDetailsText(); };
        if (display.getThread() == Thread.currentThread()) {
            updater.run();
        } else {
            display.asyncExec(updater);
        }
    }

    /**
     * Expand or collapse the details section.
     */
    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
        if (!isDisposed()) {
            GridData gd = (GridData) detailsArea.getLayoutData();
            gd.exclude = !expanded;
            detailsArea.setVisible(expanded);
            getParent().layout(true, true);
        }
    }

    // ==================== Internal ====================

    private void toggleExpanded(Label toggleLabel) {
        expanded = !expanded;
        if (!toggleLabel.isDisposed()) {
            toggleLabel.setText(expanded ? "\u25BC" : "\u25B6");
        }
        setExpanded(expanded);
    }

    private void updateStatusDisplay() {
        if (statusLabel == null || statusLabel.isDisposed()) return;

        ToolStatus status = toolCall.getStatus();
        switch (status) {
            case RUNNING:
                statusLabel.setText("\u23F3 Running...");  // hourglass
                statusLabel.setForeground(runningColor);
                break;
            case COMPLETED:
                statusLabel.setText("\u2713 Done");  // checkmark
                statusLabel.setForeground(completedColor);
                break;
            case FAILED:
                statusLabel.setText("\u2717 Failed");  // X mark
                statusLabel.setForeground(failedColor);
                break;
            case NEEDS_PERMISSION:
                statusLabel.setText("\u26A0 Permission needed");  // warning
                statusLabel.setForeground(runningColor);
                break;
        }
    }

    private void updateDetailsText() {
        if (detailsText == null || detailsText.isDisposed()) return;

        StringBuilder sb = new StringBuilder();

        String input = toolCall.getInput();
        if (input != null && !input.isEmpty()) {
            sb.append("Input:\n");
            // Truncate long inputs for display
            if (input.length() > 2000) {
                sb.append(input, 0, 2000);
                sb.append("\n... (truncated)");
            } else {
                sb.append(input);
            }
        }

        String output = toolCall.getOutput();
        if (output != null && !output.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("Output:\n");
            String displayOutput = stripXmlTags(output);
            // Truncate long outputs
            if (displayOutput.length() > 3000) {
                sb.append(displayOutput, 0, 3000);
                sb.append("\n... (truncated)");
            } else {
                sb.append(displayOutput);
            }
        }

        detailsText.setText(sb.toString());

        // Update height
        int lineCount = detailsText.getLineCount();
        int lineHeight = detailsText.getLineHeight();
        int maxHeight = Math.min(lineCount * lineHeight + 10, 300);
        GridData gd = (GridData) detailsText.getLayoutData();
        gd.heightHint = maxHeight;
    }

    /**
     * Strip XML wrapper tags from tool output for clean display.
     * E.g. "<tool_use_error>message</tool_use_error>" becomes "message".
     */
    private static String stripXmlTags(String output) {
        if (output == null) return "";
        String s = output.trim();
        // Repeatedly strip a single outer tag pair until none remain
        String prev;
        do {
            prev = s;
            // Match an opening tag at the start and its matching closing tag at the end
            int open = s.indexOf('<');
            if (open != 0) break;
            int closeTag = s.indexOf('>');
            if (closeTag < 1) break;
            String tagName = s.substring(1, closeTag).trim();
            // Skip tags with attributes or that look like processed markup
            if (tagName.isEmpty() || tagName.contains(" ")) break;
            String closingTag = "</" + tagName + ">";
            if (s.endsWith(closingTag)) {
                s = s.substring(closeTag + 1, s.length() - closingTag.length()).trim();
            } else {
                break;
            }
        } while (!s.equals(prev));
        return s.isEmpty() ? output.trim() : s;
    }
}
