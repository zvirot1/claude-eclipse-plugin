package com.anthropic.eclipse.claude.diff;

import java.util.regex.*;

/**
 * Extracts code blocks from Claude's text response.
 * Claude often wraps suggested code in ```lang ... ``` blocks.
 */
public class DiffExtractor {

    /**
     * Try to extract a modified code block from Claude's response.
     * Returns null if no code block is found.
     */
    public static String extractModifiedCode(String claudeResponse) {
        // Try ```lang\n...\n``` format
        Pattern fenced = Pattern.compile("```(?:\\w+)?\\n([\\s\\S]*?)```", Pattern.MULTILINE);
        Matcher m = fenced.matcher(claudeResponse);
        if (m.find()) {
            return m.group(1).trim();
        }

        // Try unified diff format (--- / +++ / @@)
        if (claudeResponse.contains("@@") && (claudeResponse.contains("---") || claudeResponse.contains("+++"))) {
            return claudeResponse; // Return as-is, DiffResult.fromUnifiedDiff will parse it
        }

        return null;
    }

    /**
     * Check if a response contains a code suggestion.
     */
    public static boolean containsCodeSuggestion(String response) {
        return response.contains("```") ||
               (response.contains("@@") && response.contains("---"));
    }

    /**
     * Build a prompt that asks Claude to return a full modified code block.
     */
    public static String buildRefactorPrompt(String instruction, String code, String filename) {
        return instruction + "\n\n" +
               "File: " + filename + "\n\n" +
               "Please return the COMPLETE modified code in a single ```cobol (or appropriate language) code block. " +
               "Do not truncate. Include all lines.\n\n" +
               "```\n" + code + "\n```";
    }
}
