package com.anthropic.eclipse.claude.handlers;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.*;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;
import com.anthropic.eclipse.claude.views.ClaudeChatView;
import com.anthropic.eclipse.claude.views.ClaudeConversationView;
import com.anthropic.eclipse.claude.views.ClaudeConversationViewV2;

public class HandlerUtils {

    public static IWorkbenchWindow getActiveWindow(ExecutionEvent event) {
        return HandlerUtil.getActiveWorkbenchWindow(event);
    }

    public static String getSelectedText(IWorkbenchWindow window) {
        IWorkbenchPage page = window.getActivePage();
        if (page == null) return null;
        IEditorPart editor = page.getActiveEditor();
        if (editor instanceof ITextEditor) {
            ITextEditor textEditor = (ITextEditor) editor;
            ISelection selection = textEditor.getSelectionProvider().getSelection();
            if (selection instanceof ITextSelection) {
                String text = ((ITextSelection) selection).getText();
                return (text != null && !text.trim().isEmpty()) ? text : null;
            }
        }
        return null;
    }

    public static String getActiveFileName(IWorkbenchWindow window) {
        IWorkbenchPage page = window.getActivePage();
        if (page == null) return "unknown";
        IEditorPart editor = page.getActiveEditor();
        if (editor != null && editor.getEditorInput() != null) {
            return editor.getEditorInput().getName();
        }
        return "unknown";
    }

    public static ClaudeChatView getChatView(IWorkbenchPage page) {
        try {
            IViewPart view = page.showView(ClaudeChatView.ID);
            if (view instanceof ClaudeChatView) return (ClaudeChatView) view;
        } catch (PartInitException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Show and get the legacy SWT ClaudeConversationView. Still used by
     * code-aware handlers (Send Selection, Explain Code, Review Code,
     * Refactor Code, Run CLI on File, Resume Session, New Session) because
     * they call into the legacy view's Java API which V2 hasn't yet
     * re-exposed.
     */
    public static ClaudeConversationView getConversationView(IWorkbenchPage page) {
        try {
            IViewPart view = page.showView(ClaudeConversationView.ID);
            if (view instanceof ClaudeConversationView) return (ClaudeConversationView) view;
        } catch (PartInitException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Open the default Claude Code conversation view. As of the webview
     * migration this is the V2 (browser-based) view, which the main entry
     * points — Ctrl+Shift+C, toolbar button, "Claude Code > Open Claude
     * Code Panel" menu — all use. The legacy SWT view is still reachable
     * via {@link #getConversationView(IWorkbenchPage)} for the code-aware
     * handlers and via Show View > Other > Claude AI for direct opening.
     */
    public static void openPrimaryView(IWorkbenchPage page) {
        try {
            page.showView(ClaudeConversationViewV2.ID);
        } catch (PartInitException e) {
            e.printStackTrace();
            // Fall back to the legacy view if V2 fails to instantiate
            // (e.g. Edge WebView2 unavailable on this machine).
            try {
                page.showView(ClaudeConversationView.ID);
            } catch (PartInitException e2) {
                e2.printStackTrace();
            }
        }
    }
}
