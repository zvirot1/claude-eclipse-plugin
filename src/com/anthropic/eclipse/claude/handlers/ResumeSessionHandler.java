package com.anthropic.eclipse.claude.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchWindow;

import com.anthropic.eclipse.claude.views.ClaudeConversationView;

/**
 * Handler to resume a previous Claude conversation session.
 * Delegates to ClaudeConversationView.showResumeDialog().
 */
public class ResumeSessionHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtils.getActiveWindow(event);
        if (window == null || window.getActivePage() == null) return null;

        ClaudeConversationView view = HandlerUtils.getConversationView(window.getActivePage());
        if (view != null) {
            view.showResumeDialog();
        }
        return null;
    }
}
