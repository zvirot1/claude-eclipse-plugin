package com.anthropic.eclipse.claude.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchWindow;

import com.anthropic.eclipse.claude.views.ClaudeConversationView;
import com.anthropic.eclipse.claude.views.ClaudeConversationViewV2;

/**
 * Handler to start a fresh Claude conversation session.
 * Prefers the V2 webview; falls back to the legacy SWT view.
 */
public class NewSessionHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtils.getActiveWindow(event);
        if (window == null || window.getActivePage() == null) return null;

        // Prefer V2 (webview). Fall back to V1 if V2 unavailable.
        ClaudeConversationViewV2 v2 = HandlerUtils.getConversationViewV2(window.getActivePage());
        if (v2 != null) {
            v2.startNewSession();
            return null;
        }
        ClaudeConversationView v1 = HandlerUtils.getConversationView(window.getActivePage());
        if (v1 != null) {
            v1.startNewSession();
        }
        return null;
    }
}
