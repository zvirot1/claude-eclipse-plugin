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

        // Apply syntax highlighting
        if (code != null && !code.isEmpty() && language != null && !language.isEmpty()) {
            org.eclipse.swt.custom.StyleRange[] styles =
                SyntaxHighlighter.highlight(code, language, ThemeManager.getInstance(), codeText);
            if (styles.length > 0) {
                codeText.setStyleRanges(styles);
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
