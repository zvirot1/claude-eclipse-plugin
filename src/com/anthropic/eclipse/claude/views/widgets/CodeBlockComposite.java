package com.anthropic.eclipse.claude.views.widgets;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Renders a code block with syntax-aware styling and action buttons.
 * Provides: [Copy] [Apply to Editor] [Insert at Cursor] buttons.
 * Code blocks always use dark theme (like VS Code).
 */
public class CodeBlockComposite extends Composite {

    private final String language;
    private final String code;
    private StyledText codeText;

    // Colors
    private Color bgColor;
    private Color headerBgColor;
    private Color textColor;
    private Color langColor;
    private Font monoFont;

    public CodeBlockComposite(Composite parent, String language, String code) {
        super(parent, SWT.NONE);
        this.language = language != null ? language : "";
        this.code = code != null ? code : "";
        initColors();
        createUI();
    }

    private void initColors() {
        // Code blocks always use dark theme (like VS Code)
        ThemeManager tm = ThemeManager.getInstance();
        bgColor = tm.getColor(tm.codeBg);
        headerBgColor = tm.getColor(tm.codeHeaderBg);
        textColor = tm.getColor(tm.codeText);
        langColor = tm.getColor(tm.codeLangText);
        monoFont = new Font(getDisplay(), tm.getMonoFontName(), 10, SWT.NORMAL);
    }

    private void createUI() {
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = 0;
        setLayout(layout);
        setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        // Header with language label and buttons
        Composite header = new Composite(this, SWT.NONE);
        GridLayout headerLayout = new GridLayout(4, false);
        headerLayout.marginWidth = 10;
        headerLayout.marginHeight = 4;
        header.setLayout(headerLayout);
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        header.setBackground(headerBgColor);

        // Language label
        Label langLabel = new Label(header, SWT.NONE);
        langLabel.setText(language.isEmpty() ? "code" : language);
        langLabel.setForeground(langColor);
        langLabel.setBackground(headerBgColor);
        langLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Copy button
        Button copyBtn = new Button(header, SWT.PUSH | SWT.FLAT);
        copyBtn.setText("Copy");
        copyBtn.setToolTipText("Copy code to clipboard");
        copyBtn.addListener(SWT.Selection, e -> copyToClipboard());

        // Apply button
        Button applyBtn = new Button(header, SWT.PUSH | SWT.FLAT);
        applyBtn.setText("Apply");
        applyBtn.setToolTipText("Replace selection in active editor");
        applyBtn.addListener(SWT.Selection, e -> applyToEditor());

        // Insert button
        Button insertBtn = new Button(header, SWT.PUSH | SWT.FLAT);
        insertBtn.setText("Insert");
        insertBtn.setToolTipText("Insert at cursor position in active editor");
        insertBtn.addListener(SWT.Selection, e -> insertAtCursor());

        // Code text area
        codeText = new StyledText(this, SWT.READ_ONLY | SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
        codeText.setText(code);
        codeText.setFont(monoFont);
        codeText.setForeground(textColor);
        codeText.setBackground(bgColor);
        codeText.setEditable(false);
        codeText.setCaret(null);
        codeText.setWordWrap(false);

        // Apply syntax highlighting.
        // setStyleRanges() throws IllegalArgumentException ("Argument not
        // valid") when ANY range is malformed — overlapping with the prior
        // range, negative offset/length, or extending past the end of the
        // text. SyntaxHighlighter has been observed producing such ranges on
        // edge-case CLI output, and the resulting exception was bubbling out
        // of finalizeContent — killing the asyncExec mid-render so the
        // assistant bubble never visually appeared. This was a major
        // contributor to the "I send a message and don't see the reply"
        // symptom reported by corporate users. Sanitize the ranges and wrap
        // the call so a single bad highlight only loses the syntax color, not
        // the entire bubble.
        if (code != null && !code.isEmpty() && language != null && !language.isEmpty()) {
            try {
                org.eclipse.swt.custom.StyleRange[] styles =
                    SyntaxHighlighter.highlight(code, language, ThemeManager.getInstance(), codeText);
                org.eclipse.swt.custom.StyleRange[] safe = sanitizeStyleRanges(styles, code.length());
                if (safe != null && safe.length > 0) {
                    codeText.setStyleRanges(safe);
                }
            } catch (Throwable t) {
                // Never let a syntax-highlight bug take down the bubble.
                com.anthropic.eclipse.claude.Activator.logWarning(
                        "[CodeBlockComposite] setStyleRanges failed for lang=" + language
                                + " codeLen=" + code.length() + " — rendering without highlights. " + t);
            }
        }

        // Calculate height (max 15 lines visible)
        int lineCount = Math.min(codeText.getLineCount(), 15);
        int lineHeight = codeText.getLineHeight();
        GridData codeGd = new GridData(SWT.FILL, SWT.TOP, true, false);
        codeGd.heightHint = lineCount * lineHeight + 10;
        codeText.setLayoutData(codeGd);

        addDisposeListener(e -> {
            bgColor.dispose();
            headerBgColor.dispose();
            textColor.dispose();
            langColor.dispose();
            monoFont.dispose();
        });
    }

    /**
     * Filter/repair a StyleRange[] so SWT's
     * {@code StyledText.setStyleRanges} cannot throw
     * IllegalArgumentException ("Argument not valid"). SWT requires:
     * <ul>
     *   <li>start &gt;= 0 and start &lt; textLength</li>
     *   <li>start + length &lt;= textLength (length is clamped if it would
     *       extend past the end)</li>
     *   <li>ranges must be sorted by start AND must not overlap — the next
     *       range's start must be &gt;= prev.start + prev.length</li>
     * </ul>
     * SyntaxHighlighter has been observed producing overlapping ranges on
     * edge-case code blocks (e.g. nested string literals in the language
     * grammar), and the resulting setStyleRanges throw killed the entire
     * bubble's render — leaving "no message visible" for the user. This
     * helper makes a best-effort repair: clamp lengths, drop overlaps.
     */
    private static org.eclipse.swt.custom.StyleRange[] sanitizeStyleRanges(
            org.eclipse.swt.custom.StyleRange[] input, int textLength) {
        if (input == null || input.length == 0 || textLength <= 0) return input;
        java.util.List<org.eclipse.swt.custom.StyleRange> out =
                new java.util.ArrayList<>(input.length);
        int lastEnd = 0;
        for (org.eclipse.swt.custom.StyleRange r : input) {
            if (r == null) continue;
            int start = r.start;
            int length = r.length;
            // Drop ranges that are entirely out of bounds.
            if (start < 0 || length <= 0 || start >= textLength) continue;
            // Clamp lengths that extend past the end of the text.
            if (start + length > textLength) length = textLength - start;
            // Drop overlap with previous range — bump start forward.
            if (start < lastEnd) {
                int shift = lastEnd - start;
                start = lastEnd;
                length -= shift;
                if (length <= 0) continue;
            }
            org.eclipse.swt.custom.StyleRange copy = (org.eclipse.swt.custom.StyleRange) r.clone();
            copy.start = start;
            copy.length = length;
            out.add(copy);
            lastEnd = start + length;
        }
        return out.toArray(new org.eclipse.swt.custom.StyleRange[0]);
    }

    /**
     * Get the code content.
     */
    public String getCode() {
        return code;
    }

    /**
     * Get the language.
     */
    public String getLanguage() {
        return language;
    }

    // ==================== Actions ====================

    private void copyToClipboard() {
        Clipboard clipboard = new Clipboard(getDisplay());
        try {
            clipboard.setContents(
                new Object[]{code},
                new Transfer[]{TextTransfer.getInstance()}
            );
        } finally {
            clipboard.dispose();
        }
    }

    private void applyToEditor() {
        try {
            IEditorPart editor = PlatformUI.getWorkbench()
                .getActiveWorkbenchWindow()
                .getActivePage()
                .getActiveEditor();

            if (editor instanceof ITextEditor) {
                ITextEditor textEditor = (ITextEditor) editor;
                IDocument document = textEditor.getDocumentProvider()
                    .getDocument(textEditor.getEditorInput());

                ITextSelection selection = (ITextSelection) textEditor
                    .getSelectionProvider().getSelection();

                if (selection.getLength() > 0) {
                    // Replace selection
                    document.replace(selection.getOffset(), selection.getLength(), code);
                } else {
                    // No selection - replace entire document
                    document.set(code);
                }
            }
        } catch (Exception e) {
            // Show error
            MessageBox msgBox = new MessageBox(getShell(), SWT.ICON_ERROR | SWT.OK);
            msgBox.setMessage("Could not apply code to editor: " + e.getMessage());
            msgBox.setText("Apply Error");
            msgBox.open();
        }
    }

    private void insertAtCursor() {
        try {
            IEditorPart editor = PlatformUI.getWorkbench()
                .getActiveWorkbenchWindow()
                .getActivePage()
                .getActiveEditor();

            if (editor instanceof ITextEditor) {
                ITextEditor textEditor = (ITextEditor) editor;
                IDocument document = textEditor.getDocumentProvider()
                    .getDocument(textEditor.getEditorInput());

                ITextSelection selection = (ITextSelection) textEditor
                    .getSelectionProvider().getSelection();

                // Insert at cursor position
                document.replace(selection.getOffset(), 0, code);
            }
        } catch (Exception e) {
            MessageBox msgBox = new MessageBox(getShell(), SWT.ICON_ERROR | SWT.OK);
            msgBox.setMessage("Could not insert code: " + e.getMessage());
            msgBox.setText("Insert Error");
            msgBox.open();
        }
    }
}
