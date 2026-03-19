package com.anthropic.eclipse.claude.handlers;

import org.eclipse.core.commands.*;
import org.eclipse.ui.*;

import com.anthropic.eclipse.claude.views.ClaudeConversationView;

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
        ClaudeConversationView view = HandlerUtils.getConversationView(window.getActivePage());
        if (view != null) {
            view.sendCode("Please analyze this code from " + fileName + ":", selectedText);
        }
        return null;
    }
}
