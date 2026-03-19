package com.anthropic.eclipse.claude.handlers;

import org.eclipse.core.commands.*;
import org.eclipse.ui.*;
import com.anthropic.eclipse.claude.views.ClaudeConversationView;

/**
 * Opens the Claude Code conversation view.
 */
public class OpenCLIViewHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtils.getActiveWindow(event);
        if (window == null) return null;
        try {
            window.getActivePage().showView(ClaudeConversationView.ID);
        } catch (PartInitException e) {
            e.printStackTrace();
        }
        return null;
    }
}
