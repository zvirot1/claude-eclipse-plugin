package com.anthropic.eclipse.claude.handlers;

import org.eclipse.core.commands.*;
import org.eclipse.ui.*;

/**
 * Opens the Claude Code conversation view.
 * Now redirects to the new unified ConversationView.
 */
public class OpenChatHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtils.getActiveWindow(event);
        if (window != null && window.getActivePage() != null) {
            HandlerUtils.openPrimaryView(window.getActivePage());
        }
        return null;
    }
}
