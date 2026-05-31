package com.anthropic.eclipse.claude.views.widgets;

import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/**
 * Floating popup that lets the user pick a CLI permission mode, VS Code style.
 *
 * Shows three rows (icon + title + description + optional checkmark) plus a
 * small "Modes  ⇧+tab to switch" header. Closes on outside click, Escape,
 * focus loss, or when a selection is made.
 *
 * <p>Also hosts the {@link EffortSliderWidget} below the mode list (matching
 * the VS Code design where Effort lives inside the same popup).</p>
 */
public class ModeSelectorPopup {

    /** Permission modes exposed to the user, mapped to their CLI values. */
    public enum Mode {
        ASK_BEFORE_EDITS("default",           "\u270B",  "Ask before edits",
                         "Claude will ask for approval before making each edit"),
        EDIT_AUTOMATICALLY("acceptEdits",     "\u270F",  "Edit automatically",
                           "Claude will edit your selected text or the whole file"),
        PLAN_MODE("plan",                     "\uD83D\uDCCB", "Plan mode",
                  "Claude will explore the code and present a plan before editing");

        public final String cliValue;
        public final String icon;
        public final String label;
        public final String description;

        Mode(String cliValue, String icon, String label, String description) {
            this.cliValue = cliValue;
            this.icon = icon;
            this.label = label;
            this.description = description;
        }

        /** Look up a Mode by its CLI value (case-sensitive). Defaults to ASK_BEFORE_EDITS. */
        public static Mode fromCliValue(String cli) {
            if (cli == null) return ASK_BEFORE_EDITS;
            for (Mode m : values()) {
                if (m.cliValue.equals(cli)) return m;
            }
            // Legacy values: "bypassPermissions"/"dontAsk"/"auto" map to "edit automatically"
            // for display; the underlying CLI value is preserved elsewhere if needed.
            if ("bypassPermissions".equals(cli) || "dontAsk".equals(cli) || "auto".equals(cli)) {
                return EDIT_AUTOMATICALLY;
            }
            return ASK_BEFORE_EDITS;
        }

        /** Cycle to the next mode (wraps). Used by Shift+Tab. */
        public Mode next() {
            Mode[] all = values();
            return all[(this.ordinal() + 1) % all.length];
        }
    }

    private final Shell shell;
    private final Control anchor;
    private final Consumer<Mode> onSelect;
    private Mode currentMode;

    // Optional Effort slider hosted inside the popup
    private EffortSliderWidget effortWidget;

    private ModeSelectorPopup(Control anchor, Mode current, Consumer<Mode> onSelect,
                              String currentEffort, Consumer<String> onEffortSelect) {
        this.anchor = anchor;
        this.onSelect = onSelect;
        this.currentMode = current;

        Display display = anchor.getDisplay();
        this.shell = new Shell(anchor.getShell(), SWT.ON_TOP | SWT.TOOL | SWT.NO_TRIM);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 8;
        layout.marginHeight = 8;
        layout.verticalSpacing = 2;
        shell.setLayout(layout);

        ThemeManager tm = ThemeManager.getInstance();
        Color bg = tm.getColor(tm.popupBg);
        Color fg = tm.getColor(tm.popupText);
        Color hint = tm.getColor(tm.hintText);
        Color border = tm.getColor(tm.popupBorder);

        shell.setBackground(bg);
        Font headerFont = new Font(display, tm.getUIFontName(), 9, SWT.NORMAL);
        Font titleFont  = new Font(display, tm.getUIFontName(), 10, SWT.BOLD);
        Font descFont   = new Font(display, tm.getUIFontName(), 9, SWT.NORMAL);

        // --- Header: "Modes    ⇧+tab to switch" ---
        Composite header = new Composite(shell, SWT.NONE);
        GridLayout hLayout = new GridLayout(2, false);
        hLayout.marginWidth = 2;
        hLayout.marginHeight = 0;
        header.setLayout(hLayout);
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        header.setBackground(bg);

        Label modesLbl = new Label(header, SWT.NONE);
        modesLbl.setText("Modes");
        modesLbl.setBackground(bg);
        modesLbl.setForeground(fg);
        modesLbl.setFont(headerFont);

        Label shortcutLbl = new Label(header, SWT.NONE);
        shortcutLbl.setText("\u21E7+tab to switch");
        shortcutLbl.setBackground(bg);
        shortcutLbl.setForeground(hint);
        shortcutLbl.setFont(headerFont);
        shortcutLbl.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));

        // --- Mode rows ---
        for (Mode m : Mode.values()) {
            addModeRow(shell, m, bg, fg, hint, titleFont, descFont);
        }

        // --- Effort slider (optional) ---
        if (onEffortSelect != null) {
            // Subtle separator line
            Label sep = new Label(shell, SWT.SEPARATOR | SWT.HORIZONTAL);
            sep.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

            effortWidget = new EffortSliderWidget(shell, SWT.NONE, currentEffort);
            effortWidget.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            effortWidget.setOnChange(level -> {
                try {
                    onEffortSelect.accept(level);
                } catch (Exception ignored) {}
            });
        }

        shell.addDisposeListener(e -> {
            headerFont.dispose();
            titleFont.dispose();
            descFont.dispose();
        });

        // Close on Escape / focus loss / outside click
        shell.addListener(SWT.Traverse, e -> {
            if (e.detail == SWT.TRAVERSE_ESCAPE) {
                e.doit = false;
                close();
            }
        });
        shell.addListener(SWT.Deactivate, e -> close());
        Display.getDefault().timerExec(50, () -> {
            // Defer so the initial activation doesn't immediately deactivate
            if (!shell.isDisposed()) shell.setFocus();
        });

        // Position above the anchor (VS Code style)
        shell.pack();
        positionAboveAnchor();

        // A subtle border by drawing a border color around the shell via setBackground
        // trick: SWT doesn't give us border on NO_TRIM shells easily, so we leave it
        // at default. The popup-bg color gives visual separation from the view.
        if (border != null) { /* placeholder — kept for future */ }
    }

    /**
     * Show the popup anchored above {@code anchor}. Returns the instance so
     * callers can close it programmatically (e.g. on view dispose).
     */
    public static ModeSelectorPopup show(Control anchor, Mode currentMode,
                                         Consumer<Mode> onSelect) {
        return show(anchor, currentMode, onSelect, null, null);
    }

    /**
     * Show the popup with both mode selector and effort slider.
     *
     * @param currentEffort   current effort level (low/medium/high/max), or null for Auto
     * @param onEffortSelect  invoked when user changes effort; null = hide slider
     */
    public static ModeSelectorPopup show(Control anchor, Mode currentMode,
                                         Consumer<Mode> onSelect,
                                         String currentEffort,
                                         Consumer<String> onEffortSelect) {
        ModeSelectorPopup popup = new ModeSelectorPopup(anchor, currentMode, onSelect,
                                                         currentEffort, onEffortSelect);
        popup.shell.open();
        return popup;
    }

    public void close() {
        if (shell != null && !shell.isDisposed()) {
            shell.close();
        }
    }

    public boolean isOpen() {
        return shell != null && !shell.isDisposed() && shell.isVisible();
    }

    // ==================== Internal ====================

    private void addModeRow(Composite parent, Mode m, Color bg, Color fg, Color hint,
                            Font titleFont, Font descFont) {
        Composite row = new Composite(parent, SWT.NONE);
        GridLayout rLayout = new GridLayout(3, false);
        rLayout.marginWidth = 6;
        rLayout.marginHeight = 4;
        rLayout.horizontalSpacing = 8;
        row.setLayout(rLayout);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        row.setBackground(bg);

        // Icon
        Label icon = new Label(row, SWT.NONE);
        icon.setText(m.icon);
        icon.setBackground(bg);
        icon.setForeground(fg);
        icon.setFont(titleFont);

        // Title + description (stacked)
        Composite text = new Composite(row, SWT.NONE);
        GridLayout tLayout = new GridLayout(1, false);
        tLayout.marginWidth = 0;
        tLayout.marginHeight = 0;
        tLayout.verticalSpacing = 1;
        text.setLayout(tLayout);
        text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        text.setBackground(bg);

        Label title = new Label(text, SWT.NONE);
        title.setText(m.label);
        title.setBackground(bg);
        title.setForeground(fg);
        title.setFont(titleFont);

        Label desc = new Label(text, SWT.WRAP);
        desc.setText(m.description);
        desc.setBackground(bg);
        desc.setForeground(hint);
        desc.setFont(descFont);
        GridData descGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        descGd.widthHint = 320;
        desc.setLayoutData(descGd);

        // Checkmark (only for current mode)
        Label check = new Label(row, SWT.NONE);
        check.setText(m == currentMode ? "\u2713" : " ");
        check.setBackground(bg);
        check.setForeground(fg);
        check.setFont(titleFont);

        // Click handler on row + children
        org.eclipse.swt.widgets.Listener clickListener = e -> {
            try { onSelect.accept(m); } catch (Exception ignored) {}
            close();
        };
        row.addListener(SWT.MouseUp, clickListener);
        icon.addListener(SWT.MouseUp, clickListener);
        title.addListener(SWT.MouseUp, clickListener);
        desc.addListener(SWT.MouseUp, clickListener);
        text.addListener(SWT.MouseUp, clickListener);
        check.addListener(SWT.MouseUp, clickListener);

        // Hover highlight
        ThemeManager tm = ThemeManager.getInstance();
        Color hoverBg = tm.getColor(tm.popupHoverBg);
        row.addListener(SWT.MouseEnter, e -> setRowBg(row, hoverBg));
        row.addListener(SWT.MouseExit,  e -> setRowBg(row, bg));
    }

    private void setRowBg(Composite row, Color c) {
        if (row.isDisposed() || c == null) return;
        row.setBackground(c);
        for (Control child : row.getChildren()) {
            child.setBackground(c);
            if (child instanceof Composite) {
                for (Control gc : ((Composite) child).getChildren()) {
                    gc.setBackground(c);
                }
            }
        }
    }

    private void positionAboveAnchor() {
        Point anchorAbs = anchor.toDisplay(0, 0);
        Point anchorSize = anchor.getSize();
        Point shellSize = shell.getSize();

        // Align right edge of popup with right edge of anchor, above it
        int x = anchorAbs.x + anchorSize.x - shellSize.x;
        int y = anchorAbs.y - shellSize.y - 4;

        // Keep on-screen
        Rectangle display = anchor.getDisplay().getBounds();
        if (x < display.x + 4) x = display.x + 4;
        if (y < display.y + 4) {
            // Not enough room above — show below
            y = anchorAbs.y + anchorSize.y + 4;
        }

        shell.setLocation(x, y);
    }
}
