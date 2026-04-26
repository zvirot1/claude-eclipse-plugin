package com.anthropic.eclipse.claude.views.widgets;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

/**
 * A StyledText widget optimized for incremental text append during streaming.
 * Buffers deltas for batched append (50ms) to avoid performance issues with
 * rapid token-by-token updates.
 *
 * During streaming: displays raw text with minimal styling.
 * After finalize: applies full markdown rendering.
 */
public class StreamingTextWidget extends Composite {

    private StyledText styledText;
    private final StringBuilder rawMarkdown = new StringBuilder();
    private boolean finalized = false;

    // Batching for performance
    private final StringBuilder pendingText = new StringBuilder();
    private boolean flushScheduled = false;
    private static final int FLUSH_DELAY_MS = 50;

    public StreamingTextWidget(Composite parent, int style) {
        super(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        setLayout(layout);
        setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        styledText = new StyledText(this, SWT.WRAP | SWT.READ_ONLY | SWT.MULTI);
        styledText.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        styledText.setWordWrap(true);
        styledText.setEditable(false);
        styledText.setCaret(null);

        // Use ThemeManager for colors
        ThemeManager tm = ThemeManager.getInstance();
        Font font = new Font(parent.getDisplay(), tm.getUIFontName(), 11, SWT.NORMAL);
        styledText.setFont(font);
        Color textFg = tm.getColor(tm.bodyTextColor);
        styledText.setForeground(textFg);
        styledText.addDisposeListener(e -> { font.dispose(); textFg.dispose(); });
    }

    @Override
    public Point computeSize(int wHint, int hHint, boolean changed) {
        if (wHint == SWT.DEFAULT && getParent() != null && !getParent().isDisposed()) {
            int parentWidth = getParent().getClientArea().width;
            if (parentWidth > 0) {
                wHint = parentWidth;
            }
        }
        return super.computeSize(wHint, hHint, changed);
    }

    /**
     * Append raw text during streaming. Minimal styling for performance.
     * Uses batched flush to avoid excessive UI updates.
     * Auto-detects RTL on first non-whitespace character.
     */
    public void appendText(String delta) {
        if (finalized || delta == null || delta.isEmpty()) return;

        synchronized (pendingText) {
            boolean wasEmpty = rawMarkdown.length() == 0;
            pendingText.append(delta);
            rawMarkdown.append(delta);
            com.anthropic.eclipse.claude.Activator.logInfo(
                "[DIAG] StreamingTextWidget.appendText deltaLen=" + delta.length()
                + " rawTotalLen=" + rawMarkdown.length());
            if (wasEmpty) {
                Display.getDefault().asyncExec(() -> applyRtlIfNeeded(rawMarkdown.toString()));
            }
        }

        if (!flushScheduled && !isDisposed()) {
            flushScheduled = true;
            Display.getDefault().timerExec(FLUSH_DELAY_MS, this::flushPendingText);
        }
    }

    /**
     * After streaming completes, apply full markdown rendering.
     */
    public void finalizeContent() {
        if (finalized) return;
        finalized = true;

        // Flush any remaining text first
        flushPendingText();

        // Apply markdown rendering and RTL
        if (!styledText.isDisposed()) {
            String text = rawMarkdown.toString();
            // === DIAGNOSTIC LOGGING ===
            int len = text.length();
            String head = text.substring(0, Math.min(120, len)).replace("\n", "\\n");
            String tail = len > 120 ? text.substring(Math.max(0, len - 120)).replace("\n", "\\n") : "";
            boolean exactHalfDup = false;
            if (len >= 20 && len % 2 == 0) {
                String firstHalf = text.substring(0, len / 2);
                String secondHalf = text.substring(len / 2);
                exactHalfDup = firstHalf.equals(secondHalf);
            }
            com.anthropic.eclipse.claude.Activator.logInfo(
                "[DIAG] StreamingTextWidget.finalizeContent rawLen=" + len
                + " exactHalfDup=" + exactHalfDup
                + " head='" + head + "'"
                + " tail='" + tail + "'");
            // === END DIAGNOSTIC ===
            // Duplication guard: if rawMarkdown somehow has doubled content, take only first half
            // This can happen on Windows due to CLI sending redundant assistant snapshots
            int orientation = detectOrientation(text);
            // For RTL: fix leading punctuation (AI generates "?text" instead of "text?")
            // and prepend RTL Mark to set bidi base direction on macOS
            String renderText = (orientation == SWT.RIGHT_TO_LEFT)
                    ? "\u200F" + text  // RTL Mark sets base paragraph direction
                    : text;
            // Render markdown FIRST (MarkdownRenderer.render calls widget.setText which
            // resets orientation/alignment to defaults — we must reapply them afterward).
            MarkdownRenderer.render(styledText, renderText);
            // Reapply orientation and alignment AFTER setText
            styledText.setOrientation(orientation);
            // On Windows, setOrientation(RTL) sets WS_EX_LAYOUTRTL which mirrors the
            // coordinate system — calling setAlignment(SWT.RIGHT) would double-mirror
            // and push text to the LEFT. On macOS, setOrientation alone doesn't reflow,
            // so explicit alignment is needed.
            boolean isWindows = SWT.getPlatform().equals("win32");
            if (!isWindows) {
                styledText.setAlignment(orientation == SWT.RIGHT_TO_LEFT ? SWT.RIGHT : SWT.LEFT);
            }
            // Only set RTL on the StyledText itself — do NOT propagate to parent
            // composites, as that flips the entire layout (tool calls, buttons, headers)
            updateHeight();
        }
    }

    /**
     * Detects if text is primarily RTL (Hebrew/Arabic) and sets orientation + alignment.
     * Both are needed on macOS where setOrientation alone doesn't reflow visually.
     */
    private void applyRtlIfNeeded(String text) {
        if (styledText == null || styledText.isDisposed()) return;
        int orientation = detectOrientation(text);
        styledText.setOrientation(orientation);
        // On Windows, setOrientation(RTL) mirrors coordinates so setAlignment(RIGHT)
        // would appear LEFT. Only set explicit alignment on non-Windows platforms.
        if (!SWT.getPlatform().equals("win32")) {
            styledText.setAlignment(orientation == SWT.RIGHT_TO_LEFT ? SWT.RIGHT : SWT.LEFT);
        }
        // Only set RTL on StyledText — don't propagate to parents
        styledText.redraw();
    }

    public static int detectOrientation(String text) {
        if (text == null) return SWT.LEFT_TO_RIGHT;
        // Skip leading punctuation, whitespace, markdown formatting, and non-letter
        // symbols to find the first real alphabetic character whose directionality
        // determines the text orientation.  Previously, markdown chars like '#', '*',
        // '-', '>' were treated as LTR, causing Hebrew text with headings/lists to
        // render left-to-right.
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // Skip whitespace, common punctuation, markdown formatting, and any
            // character that is not a Unicode letter — we only care about the first
            // real letter to determine direction.
            if (!Character.isLetter(c)) continue;
            byte dir = Character.getDirectionality(c);
            if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                    || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
                    || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING) {
                return SWT.RIGHT_TO_LEFT;
            }
            return SWT.LEFT_TO_RIGHT;
        }
        return SWT.LEFT_TO_RIGHT;
    }

    /**
     * AI models often generate RTL text with sentence-ending punctuation (?, !)
     * at the start of the string (English convention). This moves them to the end
     * so they appear correctly at the visual left in RTL rendering.
     */
    /**
     * Fix punctuation placement in RTL text.
     * Claude sometimes generates "?text" instead of "text?" for Hebrew.
     * Move leading punctuation to end, and wrap trailing punctuation with
     * directional marks so BiDi algorithm places them correctly.
     */
    private static String fixRtlPunctuation(String text) {
        if (text == null || text.isEmpty()) return text;
        String[] lines = text.split("\n", -1);
        StringBuilder result = new StringBuilder();
        for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
            String line = lines[lineIdx];
            // Move leading punctuation to end
            int i = 0;
            while (i < line.length() && "?!.,:;".indexOf(line.charAt(i)) >= 0) {
                i++;
            }
            if (i > 0 && i < line.length()) {
                line = line.substring(i) + line.substring(0, i);
            }
            // Wrap trailing punctuation with LRM (U+200E) to anchor it at visual end (left in RTL)
            if (!line.isEmpty()) {
                int end = line.length() - 1;
                while (end >= 0 && "?!.,:;".indexOf(line.charAt(end)) >= 0) {
                    end--;
                }
                if (end < line.length() - 1) {
                    // Insert LRM before trailing punctuation
                    line = line.substring(0, end + 1) + "\u200E" + line.substring(end + 1);
                }
            }
            result.append(line);
            if (lineIdx < lines.length - 1) result.append("\n");
        }
        return result.toString();
    }

    /**
     * Get the raw markdown text accumulated during streaming.
     */
    public String getRawText() {
        return rawMarkdown.toString();
    }

    /**
     * Check if content has been finalized.
     */
    public boolean isFinalized() {
        return finalized;
    }

    /**
     * Get the underlying StyledText widget.
     */
    public StyledText getStyledText() {
        return styledText;
    }

    // ==================== Internal ====================

    private void flushPendingText() {
        flushScheduled = false;
        if (styledText.isDisposed()) return;

        String textToAppend;
        synchronized (pendingText) {
            if (pendingText.length() == 0) return;
            textToAppend = pendingText.toString();
            pendingText.setLength(0);
        }

        styledText.append(textToAppend);
        updateHeight();

        // Scroll to show latest text
        styledText.setTopIndex(styledText.getLineCount() - 1);
    }

    private void updateHeight() {
        // Do not set heightHint — let the StyledText compute its own height
        // based on wrapped content. Setting heightHint would use the unwrapped
        // line count and produce text that is too short or clips.
        if (!isDisposed()) {
            getParent().layout(true, true);
        }
    }
}
