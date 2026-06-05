package com.anthropic.eclipse.claude.handlers;

import org.eclipse.core.commands.*;
import org.eclipse.ui.*;
import org.eclipse.core.resources.*;
import org.eclipse.swt.widgets.Display;

import com.anthropic.eclipse.claude.views.ClaudeConversationViewV2;

/**
 * Runs Claude Code CLI on the currently open file.
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

            // Read file content (best-effort).
            String content = null;
            try {
                content = new String(
                    java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath)),
                    java.nio.charset.StandardCharsets.UTF_8
                );
            } catch (Exception ignored) {
                // fall through; we'll send the path instead
            }

            String prompt;
            String code;
            if (content != null) {
                prompt = "Analyze this file (" + fileName + "):";
                code = content;
            } else {
                prompt = "Analyze the file at: " + filePath;
                code = "";
            }

            ClaudeConversationViewV2 view = HandlerUtils.getConversationViewV2(page);
            if (view != null) {
                view.sendCode(prompt, code);
            }
        }
        return null;
    }
}
