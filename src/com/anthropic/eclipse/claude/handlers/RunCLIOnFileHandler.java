package com.anthropic.eclipse.claude.handlers;

import org.eclipse.core.commands.*;
import org.eclipse.ui.*;
import org.eclipse.core.resources.*;
import org.eclipse.swt.widgets.Display;
import com.anthropic.eclipse.claude.views.ClaudeConversationView;

/**
 * Runs Claude Code CLI on the currently open file.
 * Opens the ConversationView and sends the file for analysis.
 */
public class RunCLIOnFileHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtils.getActiveWindow(event);
        if (window == null) return null;

        IWorkbenchPage page = window.getActivePage();
        IEditorPart editor = page.getActiveEditor();
        if (editor == null) {
            Display.getDefault().asyncExec(() ->
                org.eclipse.jface.dialogs.MessageDialog.openInformation(
                    window.getShell(), "Claude Code", "No file is open in the editor.")
            );
            return null;
        }

        IEditorInput input = editor.getEditorInput();
        if (input instanceof org.eclipse.ui.IFileEditorInput) {
            IFile file = ((org.eclipse.ui.IFileEditorInput) input).getFile();
            String filePath = file.getLocation().toOSString();
            String fileName = file.getName();

            ClaudeConversationView view = HandlerUtils.getConversationView(page);
            if (view != null) {
                // Read file content and send to Claude
                try {
                    String content = new String(
                        java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath)),
                        java.nio.charset.StandardCharsets.UTF_8
                    );
                    view.sendCode("Analyze this file (" + fileName + "):", content);
                } catch (Exception e) {
                    view.sendCode("Analyze the file at: " + filePath, "");
                }
            }
        }
        return null;
    }
}
