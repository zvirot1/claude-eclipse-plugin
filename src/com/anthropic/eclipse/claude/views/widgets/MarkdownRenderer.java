package com.anthropic.eclipse.claude.views.widgets;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Display;

/**
 * Converts markdown text into SWT StyleRanges for StyledText widgets.
 * Uses a line-by-line state machine approach - not a full markdown parser.
 *
 * Supports: # headers, **bold**, *italic*, `inline code`, ```code blocks```,
 * - bullet lists, 1. numbered lists.
 *
 * Properly strips markdown delimiters from rendered text and caches Color
 * objects to avoid SWT resource leaks.
 */
public class MarkdownRenderer {

    /**
     * Info about a code block found in the markdown text.
     */
    public static class CodeBlockInfo {
        public final int startOffset;
        public final int endOffset;
        public final String language;
        public final String code;

        public CodeBlockInfo(int startOffset, int endOffset, String language, String code) {
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.language = language;
            this.code = code;
        }
    }

    /**
     * Allocate a theme-aware Color. These colors are created fresh per render call
     * and tracked via the provided list so the widget can dispose them when it is destroyed.
     *
     * Using ThemeManager.isDarkMode() keeps colors correct for both light and dark Eclipse themes.
     */
    private static Color getHeaderColor(Display display) {
        boolean dark = ThemeManager.isDarkMode(display);
        // Light blue accent for headers — visible on both dark and light backgrounds.
        return new Color(display, dark ? new org.eclipse.swt.graphics.RGB(100, 175, 255)
                                       : new org.eclipse.swt.graphics.RGB(20, 80, 170));
    }

    private static Color getCodeFg(Display display) {
        boolean dark = ThemeManager.isDarkMode(display);
        // Warm amber for inline code text (like VS Code's string color).
        return new Color(display, dark ? new org.eclipse.swt.graphics.RGB(220, 160, 80)
                                       : new org.eclipse.swt.graphics.RGB(170, 50, 20));
    }

    private static Color getCodeBg(Display display) {
        boolean dark = ThemeManager.isDarkMode(display);
        // Subtle tinted background for inline code.
        return new Color(display, dark ? new org.eclipse.swt.graphics.RGB(48, 48, 54)
                                       : new org.eclipse.swt.graphics.RGB(232, 232, 238));
    }

    /**
     * Result of processing inline markdown on a single line.
     * Contains the cleaned text (delimiters removed) and style ranges.
     */
    private static class InlineResult {
        String cleanText;
        List<StyleRange> styles = new ArrayList<>();
    }

    /**
     * Apply markdown styling to a StyledText widget.
     * Processes the full text, strips markdown delimiters, and sets StyleRanges.
     */
    public static void render(StyledText widget, String markdown) {
        if (markdown == null || markdown.isEmpty()) return;

        Display display = widget.getDisplay();

        // Allocate per-render theme-aware colors ONCE and dispose them via the widget.
        final Color headerColor = getHeaderColor(display);
        final Color codeFg     = getCodeFg(display);
        final Color codeBg     = getCodeBg(display);
        widget.addDisposeListener(e -> {
            if (!headerColor.isDisposed()) headerColor.dispose();
            if (!codeFg.isDisposed())      codeFg.dispose();
            if (!codeBg.isDisposed())      codeBg.dispose();
        });

        // Process the text to remove markdown syntax and collect style ranges
        StringBuilder processed = new StringBuilder();
        List<StyleRange> styles = new ArrayList<>();

        String[] lines = markdown.split("\n", -1);
        boolean inCodeBlock = false;
        int currentOffset = 0;

        for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
            String line = lines[lineIdx];
            String suffix = (lineIdx < lines.length - 1) ? "\n" : "";

            // Check for code block fences.
            // Code blocks are rendered as separate CodeBlockComposite widgets by
            // MessageComposite.finalizeContent() — we must NOT render them here too
            // or they would appear twice (once inline, once as the widget).
            if (line.trim().startsWith("```")) {
                if (!inCodeBlock) {
                    // Opening fence: add one blank separator line so surrounding text
                    // has a visual gap before the CodeBlockComposite widget below.
                    processed.append("\n");
                    currentOffset++;
                }
                inCodeBlock = !inCodeBlock;
                continue;
            }

            if (inCodeBlock) {
                // Skip — this content is rendered by CodeBlockComposite, not here.
                continue;
            }

            // Process markdown line
            if (line.startsWith("### ")) {
                String headerText = line.substring(4) + suffix;
                processed.append(headerText);
                addHeaderStyle(styles, currentOffset, headerText.length(), headerColor);
                currentOffset += headerText.length();
            } else if (line.startsWith("## ")) {
                String headerText = line.substring(3) + suffix;
                processed.append(headerText);
                addHeaderStyle(styles, currentOffset, headerText.length(), headerColor);
                currentOffset += headerText.length();
            } else if (line.startsWith("# ")) {
                String headerText = line.substring(2) + suffix;
                processed.append(headerText);
                addHeaderStyle(styles, currentOffset, headerText.length(), headerColor);
                currentOffset += headerText.length();
            } else {
                // Regular line - process inline markdown, stripping delimiters
                InlineResult result = processInlineMarkdown(line, codeFg, codeBg);
                String cleanLine = result.cleanText + suffix;
                processed.append(cleanLine);

                // Adjust style offsets to the current position in processed text
                for (StyleRange sr : result.styles) {
                    sr.start += currentOffset;
                    styles.add(sr);
                }

                currentOffset += cleanLine.length();
            }
        }

        // Set the processed text
        widget.setText(processed.toString());

        // Apply styles
        int textLength = processed.length();
        for (StyleRange style : styles) {
            if (style.start >= 0 && style.start + style.length <= textLength) {
                try {
                    widget.setStyleRange(style);
                } catch (Exception e) {
                    // Skip invalid ranges
                }
            }
        }
    }

    /**
     * Process a single line to strip inline markdown delimiters and produce style ranges.
     * Handles **bold**, *italic*, and `inline code` - stripping delimiters from output.
     * Colors are passed in (already allocated, disposed by the widget's dispose listener).
     */
    private static InlineResult processInlineMarkdown(String line, Color codeFg, Color codeBg) {
        InlineResult result = new InlineResult();
        StringBuilder clean = new StringBuilder();
        int i = 0;

        while (i < line.length()) {
            // Check for inline code: `text`
            if (line.charAt(i) == '`') {
                // Skip triple backtick (code fence inside line - shouldn't happen but be safe)
                if (i + 2 < line.length() && line.charAt(i + 1) == '`' && line.charAt(i + 2) == '`') {
                    clean.append(line.charAt(i));
                    i++;
                    continue;
                }
                int end = line.indexOf('`', i + 1);
                if (end > i) {
                    String content = line.substring(i + 1, end);
                    int start = clean.length();
                    clean.append(content);
                    StyleRange style = new StyleRange();
                    style.start = start;
                    style.length = content.length();
                    style.foreground = codeFg;
                    style.background = codeBg;
                    result.styles.add(style);
                    i = end + 1;
                    continue;
                }
            }

            // Check for bold: **text**
            if (i + 1 < line.length() && line.charAt(i) == '*' && line.charAt(i + 1) == '*') {
                int end = line.indexOf("**", i + 2);
                if (end > i) {
                    String content = line.substring(i + 2, end);
                    int start = clean.length();
                    clean.append(content);
                    StyleRange style = new StyleRange();
                    style.start = start;
                    style.length = content.length();
                    style.fontStyle = SWT.BOLD;
                    result.styles.add(style);
                    i = end + 2;
                    continue;
                }
            }

            // Check for italic: *text* (single asterisk, not part of **)
            if (line.charAt(i) == '*') {
                // Don't match if next char is also * (that's bold)
                if (i + 1 < line.length() && line.charAt(i + 1) != '*') {
                    int end = line.indexOf('*', i + 1);
                    // Make sure closing * is not part of **
                    if (end > i && !(end + 1 < line.length() && line.charAt(end + 1) == '*')) {
                        String content = line.substring(i + 1, end);
                        int start = clean.length();
                        clean.append(content);
                        StyleRange style = new StyleRange();
                        style.start = start;
                        style.length = content.length();
                        style.fontStyle = SWT.ITALIC;
                        result.styles.add(style);
                        i = end + 1;
                        continue;
                    }
                }
            }

            // Regular character
            clean.append(line.charAt(i));
            i++;
        }

        result.cleanText = clean.toString();
        return result;
    }

    /**
     * Extract code blocks from markdown text.
     * Returns list of code block locations and content.
     */
    public static List<CodeBlockInfo> extractCodeBlocks(String markdown) {
        List<CodeBlockInfo> blocks = new ArrayList<>();
        if (markdown == null) return blocks;

        String[] lines = markdown.split("\n", -1);
        boolean inCodeBlock = false;
        StringBuilder codeContent = new StringBuilder();
        String language = "";
        int charOffset = 0;
        int blockStartOffset = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (line.trim().startsWith("```") && !inCodeBlock) {
                inCodeBlock = true;
                language = line.trim().substring(3).trim();
                blockStartOffset = charOffset;
                codeContent = new StringBuilder();
            } else if (line.trim().startsWith("```") && inCodeBlock) {
                inCodeBlock = false;
                blocks.add(new CodeBlockInfo(
                    blockStartOffset,
                    charOffset + line.length(),
                    language,
                    codeContent.toString()
                ));
            } else if (inCodeBlock) {
                if (codeContent.length() > 0) codeContent.append("\n");
                codeContent.append(line);
            }

            charOffset += line.length() + 1; // +1 for newline
        }

        return blocks;
    }

    // ==================== Internal Style Helpers ====================

    private static void addHeaderStyle(List<StyleRange> styles, int start, int length, Color headerColor) {
        StyleRange style = new StyleRange();
        style.start = start;
        style.length = length;
        style.fontStyle = SWT.BOLD;
        style.foreground = headerColor;
        styles.add(style);
    }
}
