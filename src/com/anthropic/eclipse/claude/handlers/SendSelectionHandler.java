package com.anthropic.eclipse.claude.handlers;

import org.eclipse.core.commands.*;
import org.eclipse.ui.*;

import com.anthropic.eclipse.claude.views.ClaudeConversationView;
import com.anthropic.eclipse.claude.views.ClaudeConversationViewV2;

import org.eclipse.swt.widgets.Display;

public class SendSelectionHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtils.getActiveWindow(event);
        if (window == null) return null;

        String selectedText = HandlerUtils.getSelectedText(window);
        if (selectedText == null) {
            Display.getDefault().asyncExec(() ->
                org.eclipse.jface.dialogs.MessageDialog.openInformation(
                    window.getShell(), "Claude AI", "Please select some code first.")
            );
            return null;
        }

        String fileName = HandlerUtils.getActiveFileName(window);
        String prompt = "Please analyze this code from " + fileName + ":";
        // Prefer V2 (webview). Fall back to V1 if V2 unavailable.
        ClaudeConversationViewV2 v2 = HandlerUtils.getConversationViewV2(window.getActivePage());
        if (v2 != null) {
            v2.sendCode(prompt, selectedText);
            return null;
        }
        ClaudeConversationView v1 = HandlerUtils.getConversationView(window.getActivePage());
        if (v1 != null) {
            v1.sendCode(prompt, selectedText);
        }
        return null;
    }
}
