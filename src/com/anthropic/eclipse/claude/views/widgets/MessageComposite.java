package com.anthropic.eclipse.claude.views.widgets;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

import com.anthropic.eclipse.claude.model.MessageBlock;
import com.anthropic.eclipse.claude.model.MessageBlock.ToolCallSegment;

/**
 * Renders a single message (user or assistant turn) as a composite widget.
 * Contains: role label, content area (streaming text + tool calls + code blocks).
 */
public class MessageComposite extends Composite {

    private final MessageBlock messageBlock;
    private Composite contentArea;
    private StreamingTextWidget currentTextWidget;
    private final List<ToolCallComposite> toolCallWidgets = new ArrayList<>();
    private final List<CodeBlockComposite> codeBlockWidgets = new ArrayList<>();
    private boolean hasStreamedText = false; // true if any text was rendered during streaming
    private boolean fullTextRendered = false; // true ONLY if renderExistingContent wrote the full segment text
                                              // (used to skip late-arriving deltas in the race-condition case
                                              // where the widget was created after the block was already complete)
    private boolean finalized = false; // prevent double finalization

    /** Callback for fork action from context menu. */
    public interface ForkCallback {
        void forkFromMessage(MessageBlock block);
    }
    private ForkCallback forkCallback;

    // Colors
    private Color userBgColor;
    private Color assistantBgColor;
    private Color errorBgColor;
    private Color systemBgColor;
    private Color roleColor;

    public MessageComposite(Composite parent, MessageBlock block) {
        super(parent, SWT.NONE);
        this.messageBlock = block;
        initColors();
        createUI();
    }

    private void initColors() {
        ThemeManager tm = ThemeManager.getInstance();
        userBgColor = tm.getColor(tm.userMessageBg);
        assistantBgColor = tm.getColor(tm.assistantMessageBg);
        errorBgColor = tm.getColor(tm.errorMessageBg);
        systemBgColor = tm.getColor(tm.systemMessageBg);
        roleColor = tm.getColor(tm.roleTextColor);
    }

    private void createUI() {
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        layout.marginHeight = 8;
        layout.verticalSpacing = 6;
        setLayout(layout);
        setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        // Set background based on role
        switch (messageBlock.getRole()) {
            case USER:
                setBackground(userBgColor);
                break;
            case ERROR:
                setBackground(errorBgColor);
                break;
            case SYSTEM:
                setBackground(systemBgColor);
                break;
            default:
                setBackground(assistantBgColor);
                break;
        }

        // Role header
        createRoleHeader();

        // Content area
        contentArea = new Composite(this, SWT.NONE);
        GridLayout contentLayout = new GridLayout(1, false);
        contentLayout.marginWidth = 0;
        contentLayout.marginHeight = 0;
        contentLayout.verticalSpacing = 4;
        contentArea.setLayout(contentLayout);
        contentArea.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        contentArea.setBackground(getBackground());

        // If the message already has content (non-streaming), render it.
        // Skip for ASSISTANT messages that are still being streamed — the streaming
        // path (appendStreamingText) will handle those. Rendering here AND streaming
        // would duplicate the first few tokens (e.g., "HiHi!" instead of "Hi!").
        if (messageBlock.getRole() != MessageBlock.Role.ASSISTANT || messageBlock.isComplete()) {
            renderExistingContent();
        }

        // Context menu with Fork option
        createContextMenu();

        addDisposeListener(e -> {
            userBgColor.dispose();
            assistantBgColor.dispose();
            errorBgColor.dispose();
            systemBgColor.dispose();
            roleColor.dispose();
        });
    }

    @Override
    public Point computeSize(int wHint, int hHint, boolean changed) {
        // Always force re-computation to respect width constraints for text wrapping
        return super.computeSize(wHint, hHint, true);
    }

    private void createRoleHeader() {
        ThemeManager tm = ThemeManager.getInstance();

        // Role label — click to show Fork menu
        Label roleLabel = new Label(this, SWT.NONE);
        String roleText;
        switch (messageBlock.getRole()) {
            case USER:
                roleText = "\uD83D\uDC64 You  \u25BE";  // person emoji + ▾ dropdown arrow
                break;
            case ASSISTANT:
                roleText = "\u2728 Claude  \u25BE";  // sparkles emoji + ▾ dropdown arrow
                break;
            case SYSTEM:
                roleText = "\u2699 System";  // gear emoji
                break;
            case ERROR:
                roleText = "\u26A0 Error";   // warning emoji
                break;
            default:
                roleText = "Message";
        }
        roleLabel.setText(roleText);
        roleLabel.setForeground(roleColor);
        roleLabel.setBackground(getBackground());
        roleLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        roleLabel.setCursor(getDisplay().getSystemCursor(SWT.CURSOR_HAND));

        Font boldFont = new Font(getDisplay(), tm.getUIFontName(), 10, SWT.BOLD);
        roleLabel.setFont(boldFont);
        roleLabel.addDisposeListener(e -> boldFont.dispose());

        // Click on role label → show Fork popup menu
        roleLabel.addListener(SWT.MouseDown, e -> {
            Menu popup = new Menu(roleLabel.getShell(), SWT.POP_UP);
            MenuItem forkItem = new MenuItem(popup, SWT.PUSH);
            forkItem.setText("\u2442 Fork from here");
            forkItem.addListener(SWT.Selection, ev -> {
                if (forkCallback != null) {
                    forkCallback.forkFromMessage(messageBlock);
                }
            });
            MenuItem copyItem = new MenuItem(popup, SWT.PUSH);
            copyItem.setText("Copy message text");
            copyItem.addListener(SWT.Selection, ev -> {
                String text = messageBlock.getFullText();
                if (text != null && !text.isEmpty()) {
                    org.eclipse.swt.dnd.Clipboard cb = new org.eclipse.swt.dnd.Clipboard(getDisplay());
                    cb.setContents(new Object[]{text},
                        new org.eclipse.swt.dnd.Transfer[]{org.eclipse.swt.dnd.TextTransfer.getInstance()});
                    cb.dispose();
                }
            });
            org.eclipse.swt.graphics.Point loc = roleLabel.toDisplay(e.x, e.y + roleLabel.getSize().y);
            popup.setLocation(loc);
            popup.setVisible(true);
        });
    }

    private void renderExistingContent() {
        for (MessageBlock.ContentSegment seg : messageBlock.getSegments()) {
            if (seg instanceof MessageBlock.TextSegment) {
                MessageBlock.TextSegment textSeg = (MessageBlock.TextSegment) seg;
                if (textSeg.getLength() > 0) {
                    ensureTextWidget();
                    currentTextWidget.appendText(textSeg.getText());
                    hasStreamedText = true; // Prevent finalizeContent() from re-adding this text
                    fullTextRendered = true; // Block late streaming deltas from duplicating this content
                }
            } else if (seg instanceof MessageBlock.ImageSegment) {
                addImageWidget((MessageBlock.ImageSegment) seg);
            } else if (seg instanceof MessageBlock.ToolCallSegment) {
                addToolCallWidget((MessageBlock.ToolCallSegment) seg);
            }
        }
    }

    /**
     * Append streaming text delta to current text widget.
     */
    public void appendStreamingText(String delta) {
        if (isDisposed() || finalized) return;
        // Race fix: if the MessageComposite was created AFTER the underlying
        // MessageBlock was already complete, renderExistingContent() in the
        // constructor already wrote the full text. Late-arriving streaming
        // deltas (from buffered asyncExecs) would duplicate it. Skip them.
        //
        // IMPORTANT: only check the dedicated fullTextRendered flag — checking
        // messageBlock.isComplete() would over-trigger and drop legitimate
        // late deltas in the normal streaming case (content_block_stop fires
        // before all queued asyncExecs are processed on a busy UI thread).
        if (fullTextRendered) return;
        ensureTextWidget();
        currentTextWidget.appendText(delta);
        hasStreamedText = true;
    }

    /**
     * Render an attached image inline as a thumbnail (max ~200px) under the
     * current text. Click opens the full-size image in Eclipse's default
     * image viewer (via a temp file).
     */
    public void addImageWidget(MessageBlock.ImageSegment imageSeg) {
        if (isDisposed() || imageSeg.getBytes() == null) return;
        // Close current text widget so the image renders below
        if (currentTextWidget != null && !currentTextWidget.isFinalized()) {
            finalizeAndExtractCodeBlocks(currentTextWidget);
            currentTextWidget = null;
        }
        try {
            org.eclipse.swt.graphics.ImageData full = new org.eclipse.swt.graphics.ImageData(
                    new java.io.ByteArrayInputStream(imageSeg.getBytes()));
            // Scale to max 200x200 keeping aspect ratio
            int maxDim = 200;
            int w = full.width, h = full.height;
            if (w > maxDim || h > maxDim) {
                if (w >= h) { h = (int) ((double) h * maxDim / w); w = maxDim; }
                else        { w = (int) ((double) w * maxDim / h); h = maxDim; }
            }
            final org.eclipse.swt.graphics.Image thumb =
                    new org.eclipse.swt.graphics.Image(getDisplay(), full.scaledTo(w, h));

            org.eclipse.swt.widgets.Composite wrap = new org.eclipse.swt.widgets.Composite(contentArea, SWT.NONE);
            GridLayout wl = new GridLayout(1, false);
            wl.marginWidth = 0; wl.marginHeight = 4; wl.verticalSpacing = 2;
            wrap.setLayout(wl);
            wrap.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
            wrap.setBackground(getBackground());

            Label imgLabel = new Label(wrap, SWT.NONE);
            imgLabel.setImage(thumb);
            imgLabel.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
            imgLabel.setToolTipText("Click to open " + imageSeg.getName() + " (" + full.width + "×" + full.height + ")");
            imgLabel.setBackground(getBackground());
            imgLabel.setCursor(getDisplay().getSystemCursor(SWT.CURSOR_HAND));
            imgLabel.addDisposeListener(e -> { if (!thumb.isDisposed()) thumb.dispose(); });
            imgLabel.addListener(SWT.MouseDown, e -> openImageInExternalViewer(imageSeg));

            Label nameLabel = new Label(wrap, SWT.NONE);
            nameLabel.setText(imageSeg.getName() + "  (" + full.width + "×" + full.height + ")");
            nameLabel.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
            nameLabel.setBackground(getBackground());
            org.eclipse.swt.graphics.Color fg = getForeground();
            if (fg != null) nameLabel.setForeground(fg);

            relayoutParent();
        } catch (Exception ex) {
            com.anthropic.eclipse.claude.Activator.logWarning(
                    "[MessageComposite] Failed to render image '" + imageSeg.getName() + "': " + ex.getMessage());
        }
    }

    private void openImageInExternalViewer(MessageBlock.ImageSegment imageSeg) {
        java.nio.file.Path tmp = null;
        try {
            String safeName = imageSeg.getName().replaceAll("[^A-Za-z0-9._-]", "_");
            if (!safeName.toLowerCase().endsWith(".png") && !safeName.toLowerCase().endsWith(".jpg")
                    && !safeName.toLowerCase().endsWith(".jpeg") && !safeName.toLowerCase().endsWith(".gif")) {
                safeName += ".png";
            }
            tmp = java.nio.file.Files.createTempFile("claude-img-", "-" + safeName);
            java.nio.file.Files.write(tmp, imageSeg.getBytes());
            String absPath = tmp.toAbsolutePath().toString();

            // 1. Try SWT's Program.launch (preferred — uses OS file association)
            boolean launched = org.eclipse.swt.program.Program.launch(absPath);
            if (launched) {
                com.anthropic.eclipse.claude.Activator.logInfo(
                        "[MessageComposite] Opened image via Program.launch: " + absPath);
                return;
            }

            // 2. Fallback: rundll32 url.dll on Windows (always works on Windows
            //    even if no file association is registered for SWT)
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", absPath)
                        .inheritIO().start();
                com.anthropic.eclipse.claude.Activator.logInfo(
                        "[MessageComposite] Opened image via rundll32: " + absPath);
                return;
            }

            // 3. Fallback: AWT Desktop (last resort, may fail in headless / SWT)
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(tmp.toFile());
                com.anthropic.eclipse.claude.Activator.logInfo(
                        "[MessageComposite] Opened image via Desktop: " + absPath);
                return;
            }

            com.anthropic.eclipse.claude.Activator.logWarning(
                    "[MessageComposite] No way to open image. Saved at: " + absPath);
            // Show user where the file was saved so they can open manually
            org.eclipse.jface.dialogs.MessageDialog.openInformation(getShell(),
                    "Image saved",
                    "Could not open the image automatically.\n\nThe image was saved at:\n" + absPath);
        } catch (Exception ex) {
            com.anthropic.eclipse.claude.Activator.logError(
                    "[MessageComposite] Failed to open image: " + ex.getMessage(), ex);
            String pathInfo = tmp != null ? "\n\nThe image was saved at:\n" + tmp.toAbsolutePath() : "";
            org.eclipse.jface.dialogs.MessageDialog.openError(getShell(),
                    "Could not open image",
                    "Failed to open the image: " + ex.getMessage() + pathInfo);
        }
    }

    /**
     * Add a tool call collapsible section.
     */
    public ToolCallComposite addToolCallWidget(ToolCallSegment toolCall) {
        if (isDisposed()) return null;
        // Close current text widget if any (tool calls appear between text)
        if (currentTextWidget != null && !currentTextWidget.isFinalized()) {
            finalizeAndExtractCodeBlocks(currentTextWidget);
            currentTextWidget = null;
        }
        ToolCallComposite tcWidget = new ToolCallComposite(contentArea, toolCall);
        tcWidget.setBackground(getBackground());
        toolCallWidgets.add(tcWidget);
        // Propagate context menu to new widget
        Menu menu = getMenu();
        if (menu != null) {
            applyMenuToChildren(tcWidget, menu);
            tcWidget.setMenu(menu);
        }
        relayoutParent();
        return tcWidget;
    }

    /**
     * Update a tool call's status and output in its widget.
     */
    public void updateToolCall(ToolCallSegment toolCall) {
        for (ToolCallComposite tc : toolCallWidgets) {
            if (tc.getToolCall() == toolCall) {
                tc.setStatus(toolCall.getStatus());
                if (toolCall.getOutput() != null) {
                    tc.setOutput(toolCall.getOutput());
                }
                relayoutParent();
                break;
            }
        }
    }

    /**
     * Returns true if this composite contains a widget for the given tool call segment.
     */
    public boolean hasToolCall(ToolCallSegment toolCall) {
        for (ToolCallComposite tc : toolCallWidgets) {
            if (tc.getToolCall() == toolCall) return true;
        }
        return false;
    }

    /**
     * Find the ToolCallComposite for the given segment (by reference equality).
     */
    public ToolCallComposite findToolCallWidget(ToolCallSegment toolCall) {
        for (ToolCallComposite tc : toolCallWidgets) {
            if (tc.getToolCall() == toolCall) return tc;
        }
        return null;
    }

    /**
     * Force-sync every tool call widget's displayed status with its model status.
     * Safety net for race conditions between asyncExec items.
     */
    public void syncToolCallStatuses() {
        for (ToolCallComposite tc : toolCallWidgets) {
            if (tc.isDisposed()) continue;
            ToolCallSegment seg = tc.getToolCall();
            tc.setStatus(seg.getStatus());
            if (seg.getOutput() != null) {
                tc.setOutput(seg.getOutput());
            }
        }
    }

    /**
     * Finalize: parse accumulated text for markdown, extract code blocks.
     * If streaming was disabled (text widget is empty but MessageBlock has content),
     * populate from the MessageBlock first.
     */
    public void finalizeContent() {
        if (finalized) return; // Prevent double finalization
        finalized = true;

        // If streaming was disabled, the text widget may be empty or not exist.
        // Populate it from the MessageBlock's accumulated text.
        // BUT: if text was already rendered during streaming (hasStreamedText),
        // do NOT re-populate — the text is already in the UI widgets.
        if (!hasStreamedText) {
            String fullText = messageBlock.getFullText();
            if (fullText != null && !fullText.isEmpty()) {
                if (currentTextWidget == null || currentTextWidget.isFinalized()) {
                    if (currentTextWidget == null || currentTextWidget.getRawText().isEmpty()) {
                        ensureTextWidget();
                        currentTextWidget.appendText(fullText);
                    }
                } else if (currentTextWidget.getRawText().isEmpty()) {
                    currentTextWidget.appendText(fullText);
                }
            }
        }

        if (currentTextWidget != null && !currentTextWidget.isFinalized()) {
            finalizeAndExtractCodeBlocks(currentTextWidget);
        }
        relayoutParent();
    }

    /**
     * Finalize a StreamingTextWidget: apply markdown rendering (which removes code
     * block content from the display) and create CodeBlockComposite widgets for
     * each fenced code block found in the raw text.
     */
    private void finalizeAndExtractCodeBlocks(StreamingTextWidget widget) {
        String rawText = widget.getRawText();
        widget.finalizeContent();

        // Extract code blocks and create CodeBlockComposite widgets
        List<MarkdownRenderer.CodeBlockInfo> codeBlocks =
            MarkdownRenderer.extractCodeBlocks(rawText);

        for (MarkdownRenderer.CodeBlockInfo blockInfo : codeBlocks) {
            CodeBlockComposite cbWidget = new CodeBlockComposite(
                contentArea, blockInfo.language, blockInfo.code);
            codeBlockWidgets.add(cbWidget);
        }
    }

    /**
     * Get the message block.
     */
    public MessageBlock getMessageBlock() {
        return messageBlock;
    }

    /** Set the callback for fork action. */
    public void setForkCallback(ForkCallback callback) {
        this.forkCallback = callback;
    }

    private void createContextMenu() {
        Menu menu = new Menu(this);
        MenuItem forkItem = new MenuItem(menu, SWT.PUSH);
        forkItem.setText("\u2442 Fork from here");  // ⑂ fork symbol
        forkItem.addListener(SWT.Selection, e -> {
            if (forkCallback != null) {
                forkCallback.forkFromMessage(messageBlock);
            }
        });
        setMenu(menu);
        applyMenuToChildren(this, menu);
    }

    /** Recursively apply context menu to all child controls. */
    private void applyMenuToChildren(Composite parent, Menu menu) {
        for (org.eclipse.swt.widgets.Control child : parent.getChildren()) {
            child.setMenu(menu);
            if (child instanceof Composite) {
                applyMenuToChildren((Composite) child, menu);
            }
        }
    }

    // ==================== Internal ====================

    private void ensureTextWidget() {
        if (currentTextWidget == null || currentTextWidget.isFinalized()) {
            currentTextWidget = new StreamingTextWidget(contentArea, SWT.NONE);
            currentTextWidget.setBackground(getBackground());
            // Propagate context menu to new widget
            Menu menu = getMenu();
            if (menu != null) {
                applyMenuToChildren(currentTextWidget, menu);
                currentTextWidget.setMenu(menu);
            }
        }
    }

    private void relayoutParent() {
        contentArea.layout(true, true);
        layout(true, true);
        // Request parent to re-layout too (ScrolledComposite)
        Composite parent = getParent();
        if (parent != null && !parent.isDisposed()) {
            parent.layout(true, true);
        }
    }
}
