package com.anthropic.eclipse.claude.diff;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.CompareUI;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.IWorkbenchPage;

import com.anthropic.eclipse.claude.Activator;

/**
 * Opens a native Eclipse Compare editor showing the AI-suggested diff.
 *
 * <p>Left = original (read-only), Right = modified (user can Accept changes).
 * <p>Usage: {@code ClaudeCompareInput.open(page, originalContent, modifiedContent, filename);}
 */
public class ClaudeCompareInput extends CompareEditorInput {

    private final String originalContent;
    private final String modifiedContent;
    private final String filename;

    private ClaudeCompareInput(String original, String modified, String filename) {
        super(buildConfig(filename));
        this.originalContent = original;
        this.modifiedContent = modified;
        this.filename        = filename;
    }

    /**
     * Opens the Compare editor on the given workbench page.
     */
    public static void open(IWorkbenchPage page, String original, String modified, String filename) {
        ClaudeCompareInput input = new ClaudeCompareInput(original, modified, filename);
        CompareUI.openCompareEditorOnPage(input, page);
    }

    /**
     * Opens the Compare editor for a file path, reading current content from disk.
     * @param filePath  absolute path to the target file
     * @param modified  the AI-suggested new content
     * @param page      workbench page on which to open the editor
     */
    public static void openForFile(IWorkbenchPage page, String filePath, String modified) {
        String filename = Paths.get(filePath).getFileName().toString();
        String original;
        try {
            original = Files.readString(Paths.get(filePath), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Activator.logError("[ClaudeCompareInput] Cannot read original file: " + filePath, e);
            original = "";
        }
        open(page, original, modified, filename);
    }

    // ====== CompareEditorInput contract ======

    @Override
    protected Object prepareInput(IProgressMonitor monitor)
            throws InvocationTargetException, InterruptedException {
        TextNode left  = new TextNode(originalContent, filename, true);
        TextNode right = new TextNode(modifiedContent, filename, false);
        return new DiffNode(Differencer.CHANGE, null, left, right);
    }

    // ====== helpers ======

    private static CompareConfiguration buildConfig(String filename) {
        CompareConfiguration cfg = new CompareConfiguration();
        cfg.setLeftLabel("Original: " + filename);
        cfg.setRightLabel("Claude suggested: " + filename);
        cfg.setLeftEditable(false);
        cfg.setRightEditable(true);
        return cfg;
    }

    // ====== inner node ======

    private static class TextNode implements ITypedElement, IStreamContentAccessor {

        private final byte[] bytes;
        private final String name;

        TextNode(String content, String name, boolean isLeft) {
            this.bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
            this.name  = name;
        }

        @Override
        public String getName() { return name; }

        @Override
        public String getType() {
            // Return the file extension as the type so Compare uses the right viewer
            int dot = name.lastIndexOf('.');
            return dot >= 0 ? name.substring(dot + 1) : ITypedElement.TEXT_TYPE;
        }

        @Override
        public Image getImage() { return null; }

        @Override
        public InputStream getContents() throws CoreException {
            return new ByteArrayInputStream(bytes);
        }
    }
}
