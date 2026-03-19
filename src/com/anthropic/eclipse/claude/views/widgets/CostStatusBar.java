package com.anthropic.eclipse.claude.views.widgets;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

import com.anthropic.eclipse.claude.model.SessionInfo;
import com.anthropic.eclipse.claude.model.UsageInfo;

/**
 * Bottom status bar showing session info, model, token usage, and cost.
 */
public class CostStatusBar extends Composite {

    private Label sessionLabel;
    private Label modelLabel;
    private Label tokensLabel;
    private Label costLabel;
    private Label statusLabel;

    private Color bgColor;
    private Color textColor;
    private Color accentColor;

    public CostStatusBar(Composite parent) {
        super(parent, SWT.NONE);
        initColors();
        createUI();
    }

    private void initColors() {
        ThemeManager tm = ThemeManager.getInstance();
        bgColor = tm.getColor(tm.statusBarBg);
        textColor = tm.getColor(tm.statusBarText);
        accentColor = tm.getColor(tm.statusBarAccent);
    }

    private void createUI() {
        ThemeManager tm = ThemeManager.getInstance();

        GridLayout layout = new GridLayout(5, false);
        layout.marginWidth = 10;
        layout.marginHeight = 4;
        layout.horizontalSpacing = 15;
        setLayout(layout);
        setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
        setBackground(bgColor);

        Font statusFont = new Font(getDisplay(), tm.getUIFontName(), 9, SWT.NORMAL);

        // Status indicator
        statusLabel = createStatusLabel("Ready", statusFont);

        // Session info
        sessionLabel = createStatusLabel("No session", statusFont);

        // Model
        modelLabel = createStatusLabel("", statusFont);

        // Tokens
        tokensLabel = createStatusLabel("0 tokens", statusFont);

        // Cost
        costLabel = createStatusLabel("$0.00", statusFont);

        addDisposeListener(e -> {
            bgColor.dispose();
            textColor.dispose();
            accentColor.dispose();
            statusFont.dispose();
        });
    }

    private Label createStatusLabel(String text, Font font) {
        Label label = new Label(this, SWT.NONE);
        label.setText(text);
        label.setFont(font);
        label.setForeground(textColor);
        label.setBackground(bgColor);
        return label;
    }

    /**
     * Update the status text (e.g., "Running", "Streaming", "Ready").
     */
    public void setStatus(String status) {
        if (!statusLabel.isDisposed()) {
            statusLabel.setText(status);
            statusLabel.setForeground(accentColor);
            layout(true);
        }
    }

    /**
     * Show a temporary toast message that auto-dismisses after {@code durationMs} ms,
     * then restores the previous status text.
     */
    public void showToast(String message, int durationMs) {
        if (isDisposed() || statusLabel.isDisposed()) return;
        String prevText = statusLabel.getText();
        statusLabel.setText(message);
        statusLabel.setForeground(accentColor);
        layout(true);
        getDisplay().timerExec(durationMs, () -> {
            if (!isDisposed() && !statusLabel.isDisposed()) {
                statusLabel.setText(prevText);
                statusLabel.setForeground(textColor);
                layout(true);
            }
        });
    }

    /**
     * Update session info display.
     */
    public void updateSession(SessionInfo info) {
        if (info == null) return;
        if (!sessionLabel.isDisposed()) {
            String id = info.getSessionId();
            sessionLabel.setText(id != null && id.length() > 8 ? id.substring(0, 8) + "..." : (id != null ? id : ""));
        }
        if (!modelLabel.isDisposed() && info.getModel() != null) {
            modelLabel.setText(info.getModel());
        }
        layout(true);
    }

    /**
     * Update usage/cost display.
     */
    public void updateUsage(UsageInfo usage) {
        if (usage == null) return;
        if (!tokensLabel.isDisposed()) {
            tokensLabel.setText(usage.formatTokens());
        }
        if (!costLabel.isDisposed()) {
            costLabel.setText(usage.formatCost());
        }
        layout(true);
    }

    /**
     * Reset all display values.
     */
    public void reset() {
        if (!statusLabel.isDisposed()) statusLabel.setText("Ready");
        if (!sessionLabel.isDisposed()) sessionLabel.setText("No session");
        if (!modelLabel.isDisposed()) modelLabel.setText("");
        if (!tokensLabel.isDisposed()) tokensLabel.setText("0 tokens");
        if (!costLabel.isDisposed()) costLabel.setText("$0.00");
        layout(true);
    }
}
