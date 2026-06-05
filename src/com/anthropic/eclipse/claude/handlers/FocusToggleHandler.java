package com.anthropic.eclipse.claude.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

import com.anthropic.eclipse.claude.Activator;
import com.anthropic.eclipse.claude.views.ClaudeConversationViewV2;

/**
 * Toggles keyboard focus between the active editor and the Claude Code panel.
 * Bound to M1+ESCAPE (Ctrl+Esc / Cmd+Esc).
 *
 * - If the Claude panel already has focus → return focus to the active editor.
 * - Otherwise → bring up the Claude panel and give it focus.
 */
public class FocusToggleHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null) return null;

        IWorkbenchPage page = window.getActivePage();
        if (page == null) return null;

        // If the Claude view already has focus, return focus to the active editor.
        IViewPart claudeView = page.findView(ClaudeConversationViewV2.ID);
        if (claudeView != null && page.getActivePart() == claudeView) {
            if (page.getActiveEditor() != null) {
                page.activate(page.getActiveEditor());
                page.getActiveEditor().setFocus();
            }
            return null;
        }

        // Otherwise, open (or show) the Claude view and focus it.
        try {
            IViewPart view = page.showView(ClaudeConversationViewV2.ID);
            if (view != null) {
                view.setFocus();
            }
        } catch (PartInitException e) {
            Activator.logError("[FocusToggleHandler] Could not open Claude view", e);
        }
        return null;
    }
}
