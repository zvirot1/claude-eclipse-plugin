package com.anthropic.eclipse.claude.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchWindow;

/**
 * Handler to open the new unified Claude Code conversation view.
 */
public class OpenConversationHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtils.getActiveWindow(event);
        if (window != null && window.getActivePage() != null) {
            HandlerUtils.getConversationView(window.getActivePage());
        }
        return null;
    }
}
