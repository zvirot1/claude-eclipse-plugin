package com.anthropic.eclipse.claude.handlers;

import org.eclipse.core.commands.*;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.*;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.window.Window;

import com.anthropic.eclipse.claude.views.ClaudeConversationView;

/**
 * Sends selected code to Claude for refactoring via the interactive CLI.
 * Uses the ConversationView to send the refactoring request.
 */
public class RefactorCodeHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtils.getActiveWindow(event);
        if (window == null) return null;

        String selectedText = HandlerUtils.getSelectedText(window);
        String filename = HandlerUtils.getActiveFileName(window);

        if (selectedText == null) {
            Display.getDefault().asyncExec(() ->
                org.eclipse.jface.dialogs.MessageDialog.openInformation(
                    window.getShell(), "Claude AI", "Please select code to refactor.")
            );
            return null;
        }

        // Ask for instruction
        InputDialog dialog = new InputDialog(
            window.getShell(),
            "Claude AI - Refactor",
            "What should Claude do with this code?",
            "Refactor this code to improve readability and maintainability",
            null
        );
        if (dialog.open() != Window.OK) return null;

        String instruction = dialog.getValue();
        String prompt = instruction + "\n\nFile: " + filename;

        // Send to ConversationView via CLI
        ClaudeConversationView view = HandlerUtils.getConversationView(window.getActivePage());
        if (view != null) {
            view.sendCode(prompt, selectedText);
        }
        return null;
    }
}
