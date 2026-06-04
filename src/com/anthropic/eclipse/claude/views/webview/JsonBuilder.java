package com.anthropic.eclipse.claude.views.webview;

import java.util.Base64;

import com.anthropic.eclipse.claude.model.MessageBlock;
import com.anthropic.eclipse.claude.model.SessionInfo;
import com.anthropic.eclipse.claude.model.UsageInfo;

/**
 * Builds JSON payloads for the Java -> JS push events that the Eclipse
 * conversation view sends into the webview. Mirrors the IntelliJ plugin's
 * ClaudeChatPanel JSON builders (lines 2872-2942) so the JS app.js can be
 * reused unchanged.
 *
 * <p>Output is plain JSON strings. We avoid a JSON library dependency since
 * the shapes are small and stable.
 */
public final class JsonBuilder {

    private JsonBuilder() {}

    /** Escape a Java string for embedding inside a JSON string literal. */
    public static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    public static String buildSessionInfoJson(SessionInfo info) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"sessionId\":").append(jsonString(info.getSessionId()));
        json.append(",\"model\":").append(jsonString(info.getModel()));
        json.append(",\"workingDirectory\":").append(jsonString(info.getWorkingDirectory()));
        json.append(",\"permissionMode\":").append(jsonString(info.getPermissionMode()));
        json.append("}");
        return json.toString();
    }

    public static String buildMessageBlockJson(MessageBlock block) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"role\":").append(jsonString(block.getRole().name().toLowerCase()));
        json.append(",\"timestamp\":").append(block.getTimestamp());
        json.append(",\"segments\":[");

        boolean first = true;
        for (MessageBlock.ContentSegment seg : block.getSegments()) {
            if (!first) json.append(",");
            first = false;

            if (seg instanceof MessageBlock.TextSegment) {
                MessageBlock.TextSegment textSeg = (MessageBlock.TextSegment) seg;
                json.append("{\"type\":\"text\",\"text\":")
                    .append(jsonString(textSeg.getText()))
                    .append("}");
            } else if (seg instanceof MessageBlock.ToolCallSegment) {
                json.append(buildToolCallJson((MessageBlock.ToolCallSegment) seg));
            } else if (seg instanceof MessageBlock.ImageSegment) {
                MessageBlock.ImageSegment img = (MessageBlock.ImageSegment) seg;
                String b64 = (img.getBytes() != null)
                    ? Base64.getEncoder().encodeToString(img.getBytes())
                    : "";
                json.append("{\"type\":\"image\"")
                    .append(",\"name\":").append(jsonString(img.getName()))
                    .append(",\"mediaType\":").append(jsonString(img.getMediaType()))
                    .append(",\"bytes\":").append(jsonString(b64))
                    .append("}");
            } else if (seg instanceof MessageBlock.ToolResultSegment) {
                MessageBlock.ToolResultSegment tr = (MessageBlock.ToolResultSegment) seg;
                json.append("{\"type\":\"tool_result\"")
                    .append(",\"toolUseId\":").append(jsonString(tr.getToolUseId()))
                    .append(",\"content\":").append(jsonString(tr.getContent()))
                    .append(",\"isError\":").append(tr.isError())
                    .append("}");
            }
        }

        json.append("]}");
        return json.toString();
    }

    public static String buildToolCallJson(MessageBlock.ToolCallSegment toolCall) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"type\":\"tool_use\"");
        json.append(",\"toolId\":").append(jsonString(toolCall.getToolId()));
        json.append(",\"toolName\":").append(jsonString(toolCall.getToolName()));
        json.append(",\"displayName\":").append(jsonString(toolCall.getDisplayName()));
        json.append(",\"summary\":").append(jsonString(toolCall.getSummary()));
        json.append(",\"input\":").append(jsonString(toolCall.getInput()));
        json.append(",\"output\":").append(jsonString(toolCall.getOutput()));
        json.append(",\"status\":").append(jsonString(toolCall.getStatus().name().toLowerCase()));
        json.append("}");
        return json.toString();
    }

    public static String buildUsageJson(UsageInfo usage) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"inputTokens\":").append(usage.getTotalInputTokens());
        json.append(",\"outputTokens\":").append(usage.getTotalOutputTokens());
        json.append(",\"totalTokens\":").append(usage.getTotalTokens());
        json.append(",\"costUsd\":").append(usage.getTotalCostUsd());
        json.append(",\"durationMs\":").append(usage.getTotalDurationMs());
        json.append(",\"turns\":").append(usage.getTotalTurns());
        json.append(",\"formattedCost\":").append(jsonString(usage.formatCost()));
        json.append(",\"formattedTokens\":").append(jsonString(usage.formatTokens()));
        json.append(",\"formattedDuration\":").append(jsonString(usage.formatDuration()));
        json.append("}");
        return json.toString();
    }
}
