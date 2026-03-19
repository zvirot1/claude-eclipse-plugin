package com.anthropic.eclipse.claude.diff;

import org.eclipse.jface.text.source.Annotation;

/**
 * Annotation type for pending Claude edits shown in Eclipse editors.
 * Used to highlight added/removed regions in the editor's gutter and text body.
 */
public class InlineEditAnnotation extends Annotation {

    /**
     * Annotation type for added lines (shown with green background).
     */
    public static final String TYPE_ADDED = "com.anthropic.eclipse.claude.annotation.added";

    /**
     * Annotation type for removed lines (shown with red background).
     */
    public static final String TYPE_REMOVED = "com.anthropic.eclipse.claude.annotation.removed";

    private final int offset;
    private final int length;
    private final String newText;

    /**
     * Create an inline edit annotation.
     *
     * @param type    Either TYPE_ADDED or TYPE_REMOVED
     * @param text    Description text for the annotation
     * @param offset  Character offset in the document
     * @param length  Length of the annotated region
     * @param newText The replacement text (for added annotations)
     */
    public InlineEditAnnotation(String type, String text, int offset, int length, String newText) {
        super(type, false, text);
        this.offset = offset;
        this.length = length;
        this.newText = newText;
    }

    /**
     * Get the document offset of this annotation.
     */
    public int getOffset() {
        return offset;
    }

    /**
     * Get the length of the annotated region.
     */
    public int getLength() {
        return length;
    }

    /**
     * Get the replacement text (for added annotations).
     */
    public String getNewText() {
        return newText;
    }
}
