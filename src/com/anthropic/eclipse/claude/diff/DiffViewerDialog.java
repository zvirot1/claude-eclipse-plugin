package com.anthropic.eclipse.claude.diff;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.*;
import org.eclipse.swt.events.*;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.jface.text.*;
import com.anthropic.eclipse.claude.diff.DiffResult.*;

import java.util.List;

/**
 * Side-by-side diff viewer dialog.
 * Shows original (left) vs modified (right) code with color highlighting.
 */
public class DiffViewerDialog extends Dialog {

    private final DiffResult diff;
    private final IWorkbenchPage page;

    // Colors (all tracked for disposal)
    private Color addedBg, removedBg, contextBg, addedFg, removedFg;
    private Color lineNumBg, lineNumFg, headerBg;
    private Color statsAddedFg, statsRemovedFg;
    private Color origHeaderBg, modHeaderBg;
    private Font monoFont;

    // Panels
    private StyledText leftPane, rightPane;
    private boolean syncingScroll = false;

    public DiffViewerDialog(Shell shell, DiffResult diff, IWorkbenchPage page) {
        super(shell);
        this.diff = diff;
        this.page = page;
        setShellStyle(SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MAX | SWT.APPLICATION_MODAL);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Claude AI — Diff Viewer: " + diff.getFilename());
        shell.setSize(1100, 700);
        // Center on screen
        Rectangle screen = shell.getDisplay().getPrimaryMonitor().getBounds();
        shell.setLocation((screen.width - 1100) / 2, (screen.height - 700) / 2);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(new GridLayout(1, false));

        initColors(parent.getDisplay());
        createHeaderBar(area);
        createDiffPanes(area);
        populatePanes();

        return area;
    }

    private void initColors(Display display) {
        addedBg    = new Color(display, 204, 255, 204);   // light green
        removedBg  = new Color(display, 255, 204, 204);   // light red
        contextBg  = new Color(display, 255, 255, 255);   // white
        addedFg    = new Color(display, 0, 100, 0);       // dark green
        removedFg  = new Color(display, 150, 0, 0);       // dark red
        lineNumBg  = new Color(display, 240, 240, 240);
        lineNumFg  = new Color(display, 120, 120, 120);
        headerBg   = new Color(display, 50, 50, 80);
        monoFont   = new Font(display, "Courier New", 10, SWT.NORMAL);
    }

    private void createHeaderBar(Composite parent) {
        Composite header = new Composite(parent, SWT.NONE);
        header.setBackground(headerBg);
        header.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        header.setLayout(new GridLayout(4, false));

        Label titleLabel = new Label(header, SWT.NONE);
        titleLabel.setForeground(header.getDisplay().getSystemColor(SWT.COLOR_WHITE));
        titleLabel.setBackground(headerBg);
        titleLabel.setText("  " + diff.getFilename());
        titleLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label statsLabel = new Label(header, SWT.NONE);
        statsLabel.setBackground(headerBg);
        statsAddedFg = new Color(header.getDisplay(), 150, 255, 150);
        statsLabel.setForeground(statsAddedFg);
        statsLabel.setText("+" + diff.getAddedCount() + " ");

        Label statsLabel2 = new Label(header, SWT.NONE);
        statsLabel2.setBackground(headerBg);
        statsRemovedFg = new Color(header.getDisplay(), 255, 150, 150);
        statsLabel2.setForeground(statsRemovedFg);
        statsLabel2.setText("-" + diff.getRemovedCount() + "  ");
    }

    private void createDiffPanes(Composite parent) {
        // Column headers
        Composite colHeaders = new Composite(parent, SWT.NONE);
        colHeaders.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        colHeaders.setLayout(new GridLayout(2, true));
        colHeaders.setBackground(lineNumBg);

        Label origHeader = new Label(colHeaders, SWT.CENTER);
        origHeader.setText("Original");
        origHeaderBg = new Color(parent.getDisplay(), 255, 230, 230);
        origHeader.setBackground(origHeaderBg);
        origHeader.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label modHeader = new Label(colHeaders, SWT.CENTER);
        modHeader.setText("Modified (Claude's suggestion)");
        modHeaderBg = new Color(parent.getDisplay(), 230, 255, 230);
        modHeader.setBackground(modHeaderBg);
        modHeader.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // SashForm for the two panes
        SashForm sash = new SashForm(parent, SWT.HORIZONTAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        leftPane  = createTextPane(sash);
        rightPane = createTextPane(sash);
        sash.setWeights(new int[]{50, 50});

        // Synchronized scrolling
        leftPane.getVerticalBar().addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                if (!syncingScroll) {
                    syncingScroll = true;
                    rightPane.setTopIndex(leftPane.getTopIndex());
                    syncingScroll = false;
                }
            }
        });
        rightPane.getVerticalBar().addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                if (!syncingScroll) {
                    syncingScroll = true;
                    leftPane.setTopIndex(rightPane.getTopIndex());
                    syncingScroll = false;
                }
            }
        });
    }

    private StyledText createTextPane(Composite parent) {
        StyledText text = new StyledText(parent, SWT.MULTI | SWT.READ_ONLY | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
        text.setFont(monoFont);
        text.setEditable(false);
        text.setWordWrap(false);
        text.setLeftMargin(4);
        return text;
    }

    private void populatePanes() {
        List<DiffLine> lines = diff.getLines();

        StringBuilder leftText  = new StringBuilder();
        StringBuilder rightText = new StringBuilder();

        for (DiffLine line : lines) {
            switch (line.type) {
                case CONTEXT:
                    leftText.append(formatLineNum(line.originalLineNum)).append(line.content).append("\n");
                    rightText.append(formatLineNum(line.modifiedLineNum)).append(line.content).append("\n");
                    break;
                case REMOVED:
                    leftText.append(formatLineNum(line.originalLineNum)).append(line.content).append("\n");
                    rightText.append(formatLineNum(-1)).append("\n");  // blank placeholder
                    break;
                case ADDED:
                    leftText.append(formatLineNum(-1)).append("\n");   // blank placeholder
                    rightText.append(formatLineNum(line.modifiedLineNum)).append(line.content).append("\n");
                    break;
            }
        }

        leftPane.setText(leftText.toString());
        rightPane.setText(rightText.toString());

        // Apply color styles
        applyStyles(leftPane, lines, true);
        applyStyles(rightPane, lines, false);
    }

    private void applyStyles(StyledText pane, List<DiffLine> lines, boolean isLeft) {
        String text = pane.getText();
        String[] textLines = text.split("\n", -1);
        int lineIndex = 0;
        int charOffset = 0;

        for (DiffLine diffLine : lines) {
            if (lineIndex >= textLines.length) break;
            String currentLine = textLines[lineIndex];
            int lineLen = currentLine.length() + 1; // +1 for \n

            Color bg;
            if (diffLine.type == LineType.REMOVED) {
                bg = isLeft ? removedBg : lineNumBg;
            } else if (diffLine.type == LineType.ADDED) {
                bg = isLeft ? lineNumBg : addedBg;
            } else {
                bg = contextBg;
            }

            StyleRange style = new StyleRange();
            style.start = charOffset;
            style.length = Math.min(lineLen, text.length() - charOffset);
            style.background = bg;
            if (diffLine.type == LineType.REMOVED && isLeft) style.foreground = removedFg;
            if (diffLine.type == LineType.ADDED && !isLeft)  style.foreground = addedFg;

            if (style.length > 0) {
                try { pane.setStyleRange(style); } catch (Exception ignored) {}
            }

            charOffset += lineLen;
            lineIndex++;
        }
    }

    private String formatLineNum(int num) {
        if (num < 0) return "     ";
        return String.format("%4d ", num);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        // "Apply to Editor" button
        createButton(parent, 100, "Apply to Editor", false);
        // "Copy Modified" button
        createButton(parent, 101, "Copy Modified Code", false);
        createButton(parent, IDialogConstants.CLOSE_ID, "Close", true);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == 100) {
            applyToEditor();
        } else if (buttonId == 101) {
            copyModifiedToClipboard();
        } else {
            super.buttonPressed(buttonId);
        }
    }

    private void applyToEditor() {
        if (page == null) return;
        IEditorPart editor = page.getActiveEditor();
        if (editor instanceof ITextEditor) {
            ITextEditor textEditor = (ITextEditor) editor;
            IDocumentProvider provider = textEditor.getDocumentProvider();
            IDocument document = provider.getDocument(textEditor.getEditorInput());
            if (document != null) {
                try {
                    document.set(diff.getModifiedText());
                    close();
                } catch (Exception e) {
                    MessageBox box = new MessageBox(getShell(), SWT.ICON_ERROR | SWT.OK);
                    box.setMessage("Could not apply changes: " + e.getMessage());
                    box.open();
                }
            }
        } else {
            MessageBox box = new MessageBox(getShell(), SWT.ICON_WARNING | SWT.OK);
            box.setText("No Active Editor");
            box.setMessage("Please open the file in a text editor first.");
            box.open();
        }
    }

    private void copyModifiedToClipboard() {
        org.eclipse.swt.dnd.Clipboard clipboard = new org.eclipse.swt.dnd.Clipboard(getShell().getDisplay());
        clipboard.setContents(
            new Object[]{ diff.getModifiedText() },
            new org.eclipse.swt.dnd.Transfer[]{ org.eclipse.swt.dnd.TextTransfer.getInstance() }
        );
        clipboard.dispose();
    }

    @Override
    public boolean close() {
        disposeColors();
        return super.close();
    }

    private void disposeColors() {
        if (addedBg != null)       addedBg.dispose();
        if (removedBg != null)     removedBg.dispose();
        if (contextBg != null)     contextBg.dispose();
        if (addedFg != null)       addedFg.dispose();
        if (removedFg != null)     removedFg.dispose();
        if (lineNumBg != null)     lineNumBg.dispose();
        if (lineNumFg != null)     lineNumFg.dispose();
        if (headerBg != null)      headerBg.dispose();
        if (statsAddedFg != null)  statsAddedFg.dispose();
        if (statsRemovedFg != null) statsRemovedFg.dispose();
        if (origHeaderBg != null)  origHeaderBg.dispose();
        if (modHeaderBg != null)   modHeaderBg.dispose();
        if (monoFont != null)      monoFont.dispose();
    }
}
