package com.anthropic.eclipse.claude.handlers;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.*;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;
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

    /**
     * Open the default Claude Code conversation view (V2 webview). This is
     * the primary entry point used by Ctrl+Shift+C, the toolbar button,
     * and the "Claude Code &gt; Open Claude Code Panel" menu item.
     */
    public static void openPrimaryView(IWorkbenchPage page) {
        try {
            page.showView(ClaudeConversationViewV2.ID);
        } catch (PartInitException e) {
            e.printStackTrace();
        }
    }

    /**
     * Show and return the Claude Code conversation view (V2 webview).
     * Called by the code-aware handlers (Send Selection, Explain Code,
     * Review Code, Refactor Code, Run CLI on File, Resume Session, New
     * Session) so their commands target the same chat the user sees as
     * the default.
     *
     * @return the view, or {@code null} if it fails to instantiate (e.g.
     *     Edge WebView2 unavailable on this machine — extremely rare on
     *     supported Windows installs).
     */
    public static ClaudeConversationViewV2 getConversationViewV2(IWorkbenchPage page) {
        try {
            IViewPart view = page.showView(ClaudeConversationViewV2.ID);
            if (view instanceof ClaudeConversationViewV2) {
                return (ClaudeConversationViewV2) view;
            }
        } catch (PartInitException e) {
            e.printStackTrace();
        }
        return null;
    }
}
