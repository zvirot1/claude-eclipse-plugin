package com.anthropic.eclipse.claude.handlers;

import org.eclipse.core.commands.*;
import org.eclipse.ui.*;

import com.anthropic.eclipse.claude.views.ClaudeConversationViewV2;

import org.eclipse.swt.widgets.Display;

public class ReviewCodeHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtils.getActiveWindow(event);
        if (window == null) return null;

        String selectedText = HandlerUtils.getSelectedText(window);
        if (selectedText == null) {
            Display.getDefault().asyncExec(() ->
                org.eclipse.jface.dialogs.MessageDialog.openInformation(
                    window.getShell(), "Claude AI", "Please select some code to review.")
            );
            return null;
        }

        String prompt = "Please review this code. Look for: bugs, performance issues, "
            + "code quality improvements, and best practices violations:";
        ClaudeConversationViewV2 view = HandlerUtils.getConversationViewV2(window.getActivePage());
        if (view != null) {
            view.sendCode(prompt, selectedText);
        }
        return null;
    }
}
