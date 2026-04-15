package com.anthropic.eclipse.claude.views.widgets;

import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

/**
 * VS Code style "Effort" slider.
 *
 * Displays a label "Effort (X)" on the left and a row of 5 clickable dots on
 * the right representing Auto / Low / Medium / High / Max. The active
 * position is drawn as a larger filled circle; the others as small dimmed
 * dots.
 *
 * <p>The CLI values are {@code low, medium, high, max}. {@code Auto} means
 * "don't pass the --effort flag" and is modeled here as {@code null}.</p>
 */
public class EffortSliderWidget extends Composite {

    /** Ordered levels; index 0 = Auto (null CLI value). */
    public static final String[] LEVELS_CLI   = { null,   "low",  "medium", "high", "max" };
    public static final String[] LEVELS_LABEL = { "Auto", "Low",  "Medium", "High", "Max" };

    private final Label valueLabel;
    private final Canvas dotsCanvas;
    private int currentIndex;
    private Consumer<String> onChange;

    // Geometry
    private static final int DOT_BIG_RADIUS   = 5;
    private static final int DOT_SMALL_RADIUS = 2;
    private static final int DOT_SPACING      = 18;
    private static final int CANVAS_HEIGHT    = 16;

    public EffortSliderWidget(Composite parent, int style, String currentLevel) {
        super(parent, style);

        ThemeManager tm = ThemeManager.getInstance();
        Color bg = tm.getColor(tm.popupBg);
        Color fg = tm.getColor(tm.popupText);
        Color hint = tm.getColor(tm.hintText);

        this.currentIndex = indexFromCli(currentLevel);

        GridLayout layout = new GridLayout(3, false);
        layout.marginWidth = 6;
        layout.marginHeight = 4;
        layout.horizontalSpacing = 8;
        setLayout(layout);
        setBackground(bg);

        // Icon (wave/equalizer-ish character)
        Label icon = new Label(this, SWT.NONE);
        icon.setText("\u2248"); // ≈
        icon.setBackground(bg);
        icon.setForeground(fg);

        // Text: "Effort (Auto)"
        valueLabel = new Label(this, SWT.NONE);
        valueLabel.setBackground(bg);
        valueLabel.setForeground(fg);
        Font uiFont = new Font(parent.getDisplay(), tm.getUIFontName(), 9, SWT.NORMAL);
        valueLabel.setFont(uiFont);
        updateLabel();
        valueLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Dots canvas
        dotsCanvas = new Canvas(this, SWT.NO_BACKGROUND);
        dotsCanvas.setBackground(bg);
        GridData dotsGd = new GridData(SWT.END, SWT.CENTER, false, false);
        dotsGd.widthHint  = DOT_SPACING * LEVELS_CLI.length + 4;
        dotsGd.heightHint = CANVAS_HEIGHT;
        dotsCanvas.setLayoutData(dotsGd);

        dotsCanvas.addPaintListener(new PaintListener() {
            @Override
            public void paintControl(PaintEvent e) {
                paintDots(e.gc);
            }
        });

        dotsCanvas.addListener(SWT.MouseUp, e -> {
            int idx = indexFromX(e.x);
            if (idx >= 0 && idx != currentIndex) {
                currentIndex = idx;
                updateLabel();
                dotsCanvas.redraw();
                if (onChange != null) {
                    try { onChange.accept(LEVELS_CLI[idx]); } catch (Exception ignored) {}
                }
            }
        });

        addDisposeListener(e -> uiFont.dispose());
    }

    /** Callback invoked on user selection. Level is null for Auto. */
    public void setOnChange(Consumer<String> listener) {
        this.onChange = listener;
    }

    /** Get the current CLI value (null = Auto). */
    public String getLevel() {
        return LEVELS_CLI[currentIndex];
    }

    /** Set the current level without firing {@code onChange}. */
    public void setLevel(String cliValue) {
        int idx = indexFromCli(cliValue);
        if (idx != currentIndex) {
            currentIndex = idx;
            updateLabel();
            if (!dotsCanvas.isDisposed()) dotsCanvas.redraw();
        }
    }

    // ==================== Internal ====================

    private void updateLabel() {
        valueLabel.setText("Effort (" + LEVELS_LABEL[currentIndex] + ")");
    }

    private void paintDots(GC gc) {
        ThemeManager tm = ThemeManager.getInstance();
        Color accent = tm.getColor(tm.popupAccent);
        Color dim    = tm.getColor(tm.popupAccentDim);

        gc.setAntialias(SWT.ON);
        Rectangle b = dotsCanvas.getClientArea();
        int cy = b.height / 2;
        int startX = DOT_SPACING / 2;

        for (int i = 0; i < LEVELS_CLI.length; i++) {
            int cx = startX + i * DOT_SPACING;
            int r  = (i == currentIndex) ? DOT_BIG_RADIUS : DOT_SMALL_RADIUS;
            Color c = (i == currentIndex) ? accent : dim;
            gc.setBackground(c);
            gc.fillOval(cx - r, cy - r, 2 * r, 2 * r);
        }
    }

    private int indexFromX(int mouseX) {
        // Map x to nearest dot
        int startX = DOT_SPACING / 2;
        int best = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < LEVELS_CLI.length; i++) {
            int cx = startX + i * DOT_SPACING;
            int d = Math.abs(mouseX - cx);
            if (d < bestDist) { bestDist = d; best = i; }
        }
        // Require click near the row (generous tolerance)
        return (bestDist <= DOT_SPACING) ? best : -1;
    }

    private static int indexFromCli(String cli) {
        if (cli == null || cli.isBlank()) return 0;
        for (int i = 0; i < LEVELS_CLI.length; i++) {
            if (cli.equals(LEVELS_CLI[i])) return i;
        }
        return 0;
    }

    @Override
    public Point computeSize(int wHint, int hHint, boolean changed) {
        Point p = super.computeSize(wHint, hHint, changed);
        // Ensure we have enough height for the dots + label
        if (p.y < 22) p.y = 22;
        return p;
    }
}
