package com.anthropic.eclipse.claude.handlers;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.*;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;
import com.anthropic.eclipse.claude.views.ClaudeChatView;
import com.anthropic.eclipse.claude.views.ClaudeConversationView;

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
     * Show and get the new ClaudeConversationView.
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
}
