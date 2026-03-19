package com.anthropic.eclipse.claude.views.widgets;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

/**
 * Inline banner that appears within the conversation when Claude requests
 * permission to edit a file or run a command.
 * Shows: description of action, [Accept] [Reject] [Always Allow] buttons.
 */
public class PermissionBanner extends Composite {

    /**
     * Callback for permission decisions.
     */
    public interface PermissionCallback {
        void onAccepted(boolean alwaysAllow);
        void onRejected();
    }

    private Label descriptionLabel;
    private Label detailLabel;
    private final PermissionCallback callback;

    // Colors
    private Color bgColor;
    private Color borderColor;
    private Color warningColor;

    public PermissionBanner(Composite parent, String description, String detail,
                            PermissionCallback callback) {
        super(parent, SWT.BORDER);
        this.callback = callback;
        initColors();
        createUI(description, detail);
        // Start attention pulse so the user can't miss the banner
        startPulseAnimation();
    }

    private void initColors() {
        ThemeManager tm = ThemeManager.getInstance();
        bgColor = tm.getColor(tm.permissionBg);
        borderColor = tm.getColor(tm.permissionBorder);
        warningColor = tm.getColor(tm.permissionText);
    }

    private void createUI(String description, String detail) {
        ThemeManager tm = ThemeManager.getInstance();

        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        layout.marginHeight = 10;
        setLayout(layout);
        setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        setBackground(bgColor);

        // Warning icon and description
        Composite headerRow = new Composite(this, SWT.NONE);
        GridLayout headerLayout = new GridLayout(2, false);
        headerLayout.marginWidth = 0;
        headerLayout.marginHeight = 0;
        headerRow.setLayout(headerLayout);
        headerRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        headerRow.setBackground(bgColor);

        Label iconLabel = new Label(headerRow, SWT.NONE);
        iconLabel.setText("\u26A0");  // warning symbol
        iconLabel.setBackground(bgColor);
        Font iconFont = new Font(getDisplay(), tm.getUIFontName(), 14, SWT.BOLD);
        iconLabel.setFont(iconFont);
        iconLabel.addDisposeListener(e -> iconFont.dispose());

        descriptionLabel = new Label(headerRow, SWT.WRAP);
        descriptionLabel.setText(description);
        descriptionLabel.setForeground(warningColor);
        descriptionLabel.setBackground(bgColor);
        descriptionLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Font descFont = new Font(getDisplay(), tm.getUIFontName(), 10, SWT.BOLD);
        descriptionLabel.setFont(descFont);
        descriptionLabel.addDisposeListener(e -> descFont.dispose());

        // Detail text (file path or command)
        if (detail != null && !detail.isEmpty()) {
            detailLabel = new Label(this, SWT.WRAP);
            detailLabel.setText(detail);
            detailLabel.setBackground(bgColor);
            Font monoFont = new Font(getDisplay(), tm.getMonoFontName(), 9, SWT.NORMAL);
            detailLabel.setFont(monoFont);
            detailLabel.addDisposeListener(e -> monoFont.dispose());
            detailLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        }

        // Buttons row
        Composite buttonRow = new Composite(this, SWT.NONE);
        GridLayout btnLayout = new GridLayout(3, false);
        btnLayout.marginWidth = 0;
        btnLayout.marginTop = 6;
        buttonRow.setLayout(btnLayout);
        buttonRow.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        buttonRow.setBackground(bgColor);

        Button acceptBtn = new Button(buttonRow, SWT.PUSH);
        acceptBtn.setText("\u2713 Accept");
        acceptBtn.setToolTipText("Allow this action");
        acceptBtn.addListener(SWT.Selection, e -> {
            if (callback != null) callback.onAccepted(false);
            disableButtons();
            descriptionLabel.setText(descriptionLabel.getText() + " - Accepted");
        });

        Button rejectBtn = new Button(buttonRow, SWT.PUSH);
        rejectBtn.setText("\u2717 Reject");
        rejectBtn.setToolTipText("Deny this action");
        rejectBtn.addListener(SWT.Selection, e -> {
            if (callback != null) callback.onRejected();
            disableButtons();
            descriptionLabel.setText(descriptionLabel.getText() + " - Rejected");
        });

        Button alwaysAllowBtn = new Button(buttonRow, SWT.PUSH);
        alwaysAllowBtn.setText("Always Allow");
        alwaysAllowBtn.setToolTipText("Allow this and similar future actions");
        alwaysAllowBtn.addListener(SWT.Selection, e -> {
            if (callback != null) callback.onAccepted(true);
            disableButtons();
            descriptionLabel.setText(descriptionLabel.getText() + " - Always Allowed");
        });

        addDisposeListener(e -> {
            bgColor.dispose();
            borderColor.dispose();
            warningColor.dispose();
        });
    }

    /**
     * Pulses the banner background 3 times (orange → normal) to draw attention.
     * Uses Display.timerExec so it doesn't block the UI thread.
     */
    private void startPulseAnimation() {
        // Highlight color: bright orange-yellow to signal urgency
        RGB highlightRgb = new RGB(255, 200, 50);
        Color highlightColor = new Color(getDisplay(), highlightRgb);

        final int[] pulseCount = {0};
        final int totalPulses = 6; // 3 on + 3 off = 3 full blinks
        final int intervalMs = 220;

        Runnable[] pulse = new Runnable[1];
        pulse[0] = () -> {
            if (isDisposed()) {
                highlightColor.dispose();
                return;
            }
            boolean isHighlight = (pulseCount[0] % 2 == 0);
            Color current = isHighlight ? highlightColor : bgColor;
            setAllBackgrounds(current);
            pulseCount[0]++;
            if (pulseCount[0] < totalPulses) {
                getDisplay().timerExec(intervalMs, pulse[0]);
            } else {
                // Ensure we end on the normal background color
                setAllBackgrounds(bgColor);
                highlightColor.dispose();
            }
        };
        // Start the pulse after the widget is painted for the first time
        getDisplay().timerExec(200, pulse[0]);
    }

    /** Recursively set background on this composite and all child controls. */
    private void setAllBackgrounds(Color color) {
        if (isDisposed()) return;
        setBackground(color);
        for (Control child : getChildren()) {
            if (!child.isDisposed()) {
                child.setBackground(color);
                if (child instanceof Composite) {
                    for (Control grandchild : ((Composite) child).getChildren()) {
                        if (!grandchild.isDisposed() && !(grandchild instanceof Button)) {
                            grandchild.setBackground(color);
                        }
                    }
                }
            }
        }
    }

    private void disableButtons() {
        for (Control child : getChildren()) {
            if (child instanceof Composite) {
                for (Control btn : ((Composite) child).getChildren()) {
                    if (btn instanceof Button) {
                        btn.setEnabled(false);
                    }
                }
            }
        }
    }
}
