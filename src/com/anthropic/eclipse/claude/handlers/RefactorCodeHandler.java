package com.anthropic.eclipse.claude.handlers;

import org.eclipse.core.commands.*;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.*;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.window.Window;

import com.anthropic.eclipse.claude.views.ClaudeConversationView;
import com.anthropic.eclipse.claude.views.ClaudeConversationViewV2;

/**
 * Sends selected code to Claude for refactoring via the interactive CLI.
 * Prefers the V2 webview; falls back to the legacy SWT view.
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
