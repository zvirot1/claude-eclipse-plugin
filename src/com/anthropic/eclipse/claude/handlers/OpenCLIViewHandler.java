package com.anthropic.eclipse.claude.handlers;

import org.eclipse.core.commands.*;
import org.eclipse.ui.*;
import com.anthropic.eclipse.claude.views.ClaudeConversationViewV2;

/**
 * Opens the Claude Code conversation view (V2 webview).
 */
public class OpenCLIViewHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtils.getActiveWindow(event);
        if (window == null) return null;
        try {
            window.getActivePage().showView(ClaudeConversationViewV2.ID);
        } catch (PartInitException e) {
            e.printStackTrace();
        }
        return null;
    }
}
