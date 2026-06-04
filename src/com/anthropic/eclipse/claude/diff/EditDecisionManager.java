package com.anthropic.eclipse.claude.diff;

import java.util.*;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import com.anthropic.eclipse.claude.Activator;
import com.anthropic.eclipse.claude.model.MessageBlock.ToolCallSegment;

/**
 * Tracks pending file edits from Claude and manages accept/reject workflow.
 * When Claude uses the Edit tool, the edit can be staged here for review
 * before being applied to the Eclipse editor.
 */
public class EditDecisionManager {

    /**
     * A pending file edit waiting for user decision.
     */
    public static class PendingEdit {
        private final String filePath;
        private final String originalContent;
        private final String modifiedContent;
        private DiffResult diff;
        private ToolCallSegment sourceToolCall;
        private EditState state = EditState.PENDING;
        private final long timestamp;

        public PendingEdit(String filePath, String originalContent, String modifiedContent) {
            this.filePath = filePath;
            this.originalContent = originalContent;
            this.modifiedContent = modifiedContent;
            this.timestamp = System.currentTimeMillis();
        }

        public String getFilePath() { return filePath; }
        public String getOriginalContent() { return originalContent; }
        public String getModifiedContent() { return modifiedContent; }
        public DiffResult getDiff() { return diff; }
        public void setDiff(DiffResult diff) { this.diff = diff; }
        public ToolCallSegment getSourceToolCall() { return sourceToolCall; }
        public void setSourceToolCall(ToolCallSegment sourceToolCall) { this.sourceToolCall = sourceToolCall; }
        public EditState getState() { return state; }
        public void setState(EditState state) { this.state = state; }
        public long getTimestamp() { return timestamp; }

        public String getFileName() {
            int lastSlash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
            return lastSlash >= 0 ? filePath.substring(lastSlash + 1) : filePath;
        }
    }

    public enum EditState {
        PENDING, ACCEPTED, REJECTED
    }

    /**
     * Listener for edit decision events.
     */
    public interface EditDecisionListener {
        void onEditStaged(PendingEdit edit);
        void onEditAccepted(PendingEdit edit);
        void onEditRejected(PendingEdit edit);
    }

    private final Map<String, PendingEdit> pendingEdits = new LinkedHashMap<>();
    private final List<EditDecisionListener> listeners = new ArrayList<>();

    // Track annotations per file so we can remove them later
    private final Map<String, List<Annotation>> fileAnnotations = new HashMap<>();
    private final Map<String, IAnnotationModel> fileAnnotationModels = new HashMap<>();

    /**
     * Stage a new edit for review (pre-edit: file not yet modified on disk).
     * Shows inline annotations in the open editor.
     */
    public void stageEdit(String filePath, String oldContent, String newContent, ToolCallSegment toolCall) {
        PendingEdit edit = createEdit(filePath, oldContent, newContent, toolCall);
        pendingEdits.put(filePath, edit);
        applyAnnotations(edit);
        for (EditDecisionListener l : listeners) {
            l.onEditStaged(edit);
        }
    }

    /**
     * Record a completed edit (post-edit: CLI already modified the file on disk).
     * Stores the before/after diff for diff-viewing and revert, but does NOT
     * try to annotate the now-changed file (positions would be stale).
     */
    public void recordCompletedEdit(String filePath, String originalContent,
                                    String modifiedContent, ToolCallSegment toolCall) {
        PendingEdit edit = createEdit(filePath, originalContent, modifiedContent, toolCall);
        pendingEdits.put(filePath, edit);
        // No annotations — file is already in its new state
        for (EditDecisionListener l : listeners) {
            l.onEditStaged(edit);
        }
    }

    /** Shared helper: build a PendingEdit with diff computed. */
    private PendingEdit createEdit(String filePath, String oldContent,
                                   String newContent, ToolCallSegment toolCall) {
        PendingEdit edit = new PendingEdit(filePath, oldContent, newContent);
        edit.setSourceToolCall(toolCall);
        DiffResult diff = DiffResult.fromUnifiedDiff(oldContent, newContent, filePath);
        edit.setDiff(diff);
        return edit;
    }

    /**
     * Accept and apply the edit to the Eclipse editor.
     *
     * <p>Equivalent to {@link #acceptEdit(String, boolean) acceptEdit(filePath, true)}
     * — used by the legacy V1 view where the staging widget represents
     * a proposed (not-yet-written) change and the editor document needs
     * to be updated explicitly on Accept.
     */
    public void acceptEdit(String filePath) {
        acceptEdit(filePath, true);
    }

    /**
     * Accept the edit, optionally applying it to the Eclipse editor.
     *
     * @param applyToEditor when {@code true}, write the edit's modified
     *     content into the open editor's document (V1 behavior). When
     *     {@code false}, just mark the edit accepted and notify listeners
     *     — used by the V2 webview view where the CLI has already
     *     written the file to disk and {@code revertOpenEditor} has
     *     already refreshed the editor buffer; calling
     *     {@code document.set()} a second time would mark the buffer
     *     dirty (asterisk) for no functional benefit.
     */
    public void acceptEdit(String filePath, boolean applyToEditor) {
        PendingEdit edit = pendingEdits.get(filePath);
        if (edit == null || edit.getState() != EditState.PENDING) return;

        edit.setState(EditState.ACCEPTED);
        removeAnnotations(filePath);
        if (applyToEditor) {
            applyToDocument(edit);
        }

        for (EditDecisionListener l : listeners) {
            l.onEditAccepted(edit);
        }
    }

    /**
     * Reject the edit.
     */
    public void rejectEdit(String filePath) {
        PendingEdit edit = pendingEdits.get(filePath);
        if (edit == null || edit.getState() != EditState.PENDING) return;

        edit.setState(EditState.REJECTED);
        removeAnnotations(filePath);

        for (EditDecisionListener l : listeners) {
            l.onEditRejected(edit);
        }
    }

    /**
     * Accept all pending edits.
     */
    public void acceptAll() {
        for (String filePath : new ArrayList<>(pendingEdits.keySet())) {
            PendingEdit edit = pendingEdits.get(filePath);
            if (edit.getState() == EditState.PENDING) {
                acceptEdit(filePath);
            }
        }
    }

    /**
     * Reject all pending edits.
     */
    public void rejectAll() {
        for (String filePath : new ArrayList<>(pendingEdits.keySet())) {
            PendingEdit edit = pendingEdits.get(filePath);
            if (edit.getState() == EditState.PENDING) {
                rejectEdit(filePath);
            }
        }
    }

    /**
     * Get all pending edits.
     */
    public List<PendingEdit> getPendingEdits() {
        List<PendingEdit> result = new ArrayList<>();
        for (PendingEdit edit : pendingEdits.values()) {
            if (edit.getState() == EditState.PENDING) {
                result.add(edit);
            }
        }
        return result;
    }

    /**
     * Get a specific edit by file path.
     */
    public PendingEdit getEdit(String filePath) {
        return pendingEdits.get(filePath);
    }

    /**
     * Check if there are any pending edits.
     */
    public boolean hasPendingEdits() {
        return pendingEdits.values().stream()
            .anyMatch(e -> e.getState() == EditState.PENDING);
    }

    /**
     * Clear all edit records and annotations.
     */
    public void clear() {
        // Remove all annotations
        for (String filePath : new ArrayList<>(fileAnnotations.keySet())) {
            removeAnnotations(filePath);
        }
        pendingEdits.clear();
    }

    /**
     * Add listener.
     */
    public void addListener(EditDecisionListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove listener.
     */
    public void removeListener(EditDecisionListener listener) {
        listeners.remove(listener);
    }

    // ==================== Annotation Support ====================

    /**
     * Apply green/red annotations to the Eclipse editor to show pending changes.
     * Runs on the UI thread since it manipulates editors.
     */
    private void applyAnnotations(PendingEdit edit) {
        Display.getDefault().asyncExec(() -> {
            try {
                IWorkbenchPage page = PlatformUI.getWorkbench()
                    .getActiveWorkbenchWindow()
                    .getActivePage();
                if (page == null) return;

                // Find and open the file
                IFile[] files = ResourcesPlugin.getWorkspace().getRoot()
                    .findFilesForLocationURI(new java.io.File(edit.getFilePath()).toURI());
                if (files.length == 0) return;

                IEditorPart editorPart = IDE.openEditor(page, files[0]);
                if (!(editorPart instanceof ITextEditor)) return;

                ITextEditor textEditor = (ITextEditor) editorPart;
                IDocumentProvider provider = textEditor.getDocumentProvider();
                IDocument document = provider.getDocument(textEditor.getEditorInput());
                IAnnotationModel annotationModel = provider.getAnnotationModel(textEditor.getEditorInput());
                if (document == null || annotationModel == null) return;

                // Remove any existing annotations for this file first
                removeAnnotations(edit.getFilePath());

                List<Annotation> annotations = new ArrayList<>();
                DiffResult diff = edit.getDiff();
                if (diff == null) return;

                // Build annotation map for batch add
                Map<Annotation, Position> annotationMap = new LinkedHashMap<>();

                // Walk the diff lines and annotate the original document
                String originalContent = edit.getOriginalContent();
                String[] originalLines = originalContent.split("\n", -1);

                for (DiffResult.DiffLine diffLine : diff.getLines()) {
                    if (diffLine.type == DiffResult.LineType.REMOVED && diffLine.originalLineNum > 0) {
                        // Red annotation: line being removed
                        int lineNum = diffLine.originalLineNum - 1; // 0-based
                        if (lineNum < originalLines.length) {
                            int offset = getLineOffset(originalLines, lineNum);
                            int length = originalLines[lineNum].length();
                            if (length == 0) length = 1; // minimum 1 for visibility

                            // Ensure within document bounds
                            if (offset >= 0 && offset + length <= document.getLength()) {
                                InlineEditAnnotation annotation = new InlineEditAnnotation(
                                    InlineEditAnnotation.TYPE_REMOVED,
                                    "Claude will remove: " + diffLine.content,
                                    offset, length, null
                                );
                                annotationMap.put(annotation, new Position(offset, length));
                                annotations.add(annotation);
                            }
                        }
                    } else if (diffLine.type == DiffResult.LineType.ADDED && diffLine.originalLineNum == -1) {
                        // Green annotation: we annotate the line BEFORE the insertion point
                        // Find the nearest context line to annotate
                        // For added lines, we mark the previous context/removed line
                        int insertAfterLine = findInsertionPoint(diff, diffLine);
                        if (insertAfterLine >= 0 && insertAfterLine < originalLines.length) {
                            int offset = getLineOffset(originalLines, insertAfterLine);
                            int length = originalLines[insertAfterLine].length();
                            if (length == 0) length = 1;

                            if (offset >= 0 && offset + length <= document.getLength()) {
                                InlineEditAnnotation annotation = new InlineEditAnnotation(
                                    InlineEditAnnotation.TYPE_ADDED,
                                    "Claude will add: " + diffLine.content,
                                    offset, length, diffLine.content
                                );
                                annotationMap.put(annotation, new Position(offset, length));
                                annotations.add(annotation);
                            }
                        }
                    }
                }

                // Apply annotations in batch if model supports it
                if (annotationModel instanceof IAnnotationModelExtension) {
                    ((IAnnotationModelExtension) annotationModel).replaceAnnotations(
                        new Annotation[0], annotationMap);
                } else {
                    for (Map.Entry<Annotation, Position> entry : annotationMap.entrySet()) {
                        annotationModel.addAnnotation(entry.getKey(), entry.getValue());
                    }
                }

                // Store references for later removal
                fileAnnotations.put(edit.getFilePath(), annotations);
                fileAnnotationModels.put(edit.getFilePath(), annotationModel);

            } catch (Exception e) {
                Activator.logError("[EditDecisionManager] Failed to apply annotations: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Remove all annotations for a given file.
     */
    private void removeAnnotations(String filePath) {
        List<Annotation> annotations = fileAnnotations.remove(filePath);
        IAnnotationModel annotationModel = fileAnnotationModels.remove(filePath);

        if (annotations == null || annotationModel == null) return;

        Display display = Display.getDefault();
        if (display.isDisposed()) return;

        display.asyncExec(() -> {
            try {
                if (annotationModel instanceof IAnnotationModelExtension) {
                    ((IAnnotationModelExtension) annotationModel).replaceAnnotations(
                        annotations.toArray(new Annotation[0]), Collections.emptyMap());
                } else {
                    for (Annotation a : annotations) {
                        annotationModel.removeAnnotation(a);
                    }
                }
            } catch (Exception e) {
                Activator.logError("[EditDecisionManager] Failed to remove annotations: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Calculate the character offset of a line in the original text.
     */
    private int getLineOffset(String[] lines, int lineIndex) {
        int offset = 0;
        for (int i = 0; i < lineIndex && i < lines.length; i++) {
            offset += lines[i].length() + 1; // +1 for newline
        }
        return offset;
    }

    /**
     * Find the original line number after which an added line should be inserted.
     * Returns 0-based line index, or -1 if not found.
     */
    private int findInsertionPoint(DiffResult diff, DiffResult.DiffLine addedLine) {
        List<DiffResult.DiffLine> lines = diff.getLines();
        int idx = lines.indexOf(addedLine);

        // Walk backwards to find the nearest line with an original line number
        for (int i = idx - 1; i >= 0; i--) {
            DiffResult.DiffLine prev = lines.get(i);
            if (prev.originalLineNum > 0) {
                return prev.originalLineNum - 1; // convert to 0-based
            }
        }

        // If nothing before, mark at the very first line
        return 0;
    }

    // ==================== Internal ====================

    /**
     * Apply edit to Eclipse editor using IDocument API.
     */
    private void applyToDocument(PendingEdit edit) {
        try {
            IWorkbenchPage page = PlatformUI.getWorkbench()
                .getActiveWorkbenchWindow()
                .getActivePage();

            // Try to find the file in the workspace
            IFile[] files = ResourcesPlugin.getWorkspace().getRoot()
                .findFilesForLocationURI(new java.io.File(edit.getFilePath()).toURI());

            if (files.length > 0) {
                // Open the file in editor
                IEditorPart editorPart = IDE.openEditor(page, files[0]);

                if (editorPart instanceof ITextEditor) {
                    ITextEditor textEditor = (ITextEditor) editorPart;
                    IDocument document = textEditor.getDocumentProvider()
                        .getDocument(textEditor.getEditorInput());

                    if (document != null) {
                        document.set(edit.getModifiedContent());
                    }
                }
            } else {
                // File not in workspace - try to write directly
                java.nio.file.Files.writeString(
                    java.nio.file.Paths.get(edit.getFilePath()),
                    edit.getModifiedContent()
                );
            }
        } catch (Exception e) {
            Activator.logError("[EditDecisionManager] Failed to apply edit: " + e.getMessage(), e);
        }
    }
}
