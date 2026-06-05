package com.anthropic.eclipse.claude.handlers;

import org.eclipse.core.commands.*;
import org.eclipse.ui.*;

import com.anthropic.eclipse.claude.views.ClaudeConversationViewV2;

import org.eclipse.swt.widgets.Display;

public class ExplainCodeHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtils.getActiveWindow(event);
        if (window == null) return null;

        String selectedText = HandlerUtils.getSelectedText(window);
        if (selectedText == null) {
            Display.getDefault().asyncExec(() ->
                org.eclipse.jface.dialogs.MessageDialog.openInformation(
                    window.getShell(), "Claude AI", "Please select some code to explain.")
            );
            return null;
        }

        String prompt = "Please explain what this code does, step by step:";
        ClaudeConversationViewV2 view = HandlerUtils.getConversationViewV2(window.getActivePage());
        if (view != null) {
            view.sendCode(prompt, selectedText);
        }
        return null;
    }
}
