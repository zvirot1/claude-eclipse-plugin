package com.anthropic.eclipse.claude.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a parsed diff between original and modified code.
 */
public class DiffResult {

    public enum LineType {
        CONTEXT,   // unchanged line
        ADDED,     // + line
        REMOVED    // - line
    }

    public static class DiffLine {
        public final LineType type;
        public final String content;
        public final int originalLineNum;  // -1 if not applicable
        public final int modifiedLineNum;  // -1 if not applicable

        public DiffLine(LineType type, String content, int originalLineNum, int modifiedLineNum) {
            this.type = type;
            this.content = content;
            this.originalLineNum = originalLineNum;
            this.modifiedLineNum = modifiedLineNum;
        }
    }

    private final String originalText;
    private final String modifiedText;
    private final List<DiffLine> lines;
    private final String filename;

    public DiffResult(String originalText, String modifiedText, List<DiffLine> lines, String filename) {
        this.originalText = originalText;
        this.modifiedText = modifiedText;
        this.lines = lines;
        this.filename = filename;
    }

    public String getOriginalText() { return originalText; }
    public String getModifiedText() { return modifiedText; }
    public List<DiffLine> getLines() { return lines; }
    public String getFilename() { return filename; }

    public int getAddedCount() {
        return (int) lines.stream().filter(l -> l.type == LineType.ADDED).count();
    }

    public int getRemovedCount() {
        return (int) lines.stream().filter(l -> l.type == LineType.REMOVED).count();
    }

    /**
     * Parse a unified diff string (from Claude's response) into a DiffResult.
     * Also accepts plain "before/after" blocks.
     */
    public static DiffResult fromUnifiedDiff(String originalText, String diffOrModified, String filename) {
        List<DiffLine> lines = new ArrayList<>();

        // If it looks like a unified diff, parse it
        if (diffOrModified.contains("\n---") || diffOrModified.contains("\n+++") || diffOrModified.startsWith("---")) {
            return parseUnifiedDiff(originalText, diffOrModified, filename);
        }

        // Otherwise treat diffOrModified as the fully replaced text and compute line diff
        return computeLineDiff(originalText, diffOrModified, filename);
    }

    private static DiffResult parseUnifiedDiff(String original, String diff, String filename) {
        List<DiffLine> lines = new ArrayList<>();
        StringBuilder modifiedBuilder = new StringBuilder();
        String[] diffLines = diff.split("\n");
        int origLine = 0, modLine = 0;

        for (String line : diffLines) {
            if (line.startsWith("---") || line.startsWith("+++") || line.startsWith("@@")) continue;
            if (line.startsWith("+")) {
                modLine++;
                lines.add(new DiffLine(LineType.ADDED, line.substring(1), -1, modLine));
                modifiedBuilder.append(line.substring(1)).append("\n");
            } else if (line.startsWith("-")) {
                origLine++;
                lines.add(new DiffLine(LineType.REMOVED, line.substring(1), origLine, -1));
            } else {
                origLine++;
                modLine++;
                String content = line.startsWith(" ") ? line.substring(1) : line;
                lines.add(new DiffLine(LineType.CONTEXT, content, origLine, modLine));
                modifiedBuilder.append(content).append("\n");
            }
        }

        return new DiffResult(original, modifiedBuilder.toString(), lines, filename);
    }

    private static DiffResult computeLineDiff(String original, String modified, String filename) {
        List<DiffLine> result = new ArrayList<>();
        String[] origLines = original.split("\n", -1);
        String[] modLines  = modified.split("\n", -1);

        // Simple LCS-based diff
        int[][] lcs = new int[origLines.length + 1][modLines.length + 1];
        for (int i = origLines.length - 1; i >= 0; i--) {
            for (int j = modLines.length - 1; j >= 0; j--) {
                if (origLines[i].equals(modLines[j])) {
                    lcs[i][j] = lcs[i+1][j+1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i+1][j], lcs[i][j+1]);
                }
            }
        }

        int i = 0, j = 0, origNum = 0, modNum = 0;
        while (i < origLines.length || j < modLines.length) {
            if (i < origLines.length && j < modLines.length && origLines[i].equals(modLines[j])) {
                result.add(new DiffLine(LineType.CONTEXT, origLines[i], ++origNum, ++modNum));
                i++; j++;
            } else if (j < modLines.length && (i >= origLines.length || lcs[i][j+1] >= lcs[i+1][j])) {
                result.add(new DiffLine(LineType.ADDED, modLines[j], -1, ++modNum));
                j++;
            } else {
                result.add(new DiffLine(LineType.REMOVED, origLines[i], ++origNum, -1));
                i++;
            }
        }

        return new DiffResult(original, modified, result, filename);
    }
}
