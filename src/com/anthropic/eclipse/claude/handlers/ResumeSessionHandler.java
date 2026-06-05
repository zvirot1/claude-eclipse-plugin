package com.anthropic.eclipse.claude.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchWindow;

import com.anthropic.eclipse.claude.views.ClaudeConversationViewV2;

/**
 * Handler to resume a previous Claude conversation session.
 */
public class ResumeSessionHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtils.getActiveWindow(event);
        if (window == null || window.getActivePage() == null) return null;

        ClaudeConversationViewV2 view = HandlerUtils.getConversationViewV2(window.getActivePage());
        if (view != null) {
            view.showResumeDialog();
        }
        return null;
    }
}
