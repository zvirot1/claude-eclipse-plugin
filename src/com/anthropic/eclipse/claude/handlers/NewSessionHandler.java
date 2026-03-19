package com.anthropic.eclipse.claude.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchWindow;

import com.anthropic.eclipse.claude.views.ClaudeConversationView;

/**
 * Handler to start a fresh Claude conversation session.
 */
public class NewSessionHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtils.getActiveWindow(event);
        if (window != null && window.getActivePage() != null) {
            ClaudeConversationView view = HandlerUtils.getConversationView(window.getActivePage());
            if (view != null) {
                view.startNewSession();
            }
        }
        return null;
    }
}
