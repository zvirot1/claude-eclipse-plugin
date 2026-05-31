package com.anthropic.eclipse.claude.model;

import java.util.*;

/**
 * Represents a single conversation turn (message) in the conversation.
 * Contains a list of content segments - text, tool calls, and tool results.
 */
public class MessageBlock {

    /**
     * The role of the message sender.
     */
    public enum Role {
        USER, ASSISTANT, SYSTEM, ERROR
    }

    /**
     * Status of a tool call.
     */
    public enum ToolStatus {
        RUNNING, COMPLETED, FAILED, NEEDS_PERMISSION
    }

    private final Role role;
    private final long timestamp;
    private final List<ContentSegment> segments = new ArrayList<>();

    public MessageBlock(Role role) {
        this.role = role;
        this.timestamp = System.currentTimeMillis();
    }

    private volatile boolean complete = false;

    public Role getRole() { return role; }
    public long getTimestamp() { return timestamp; }
    public List<ContentSegment> getSegments() { return segments; }
    public boolean isComplete() { return complete; }
    public void setComplete(boolean complete) { this.complete = complete; }

    /**
     * Add a segment to this message.
     */
    public void addSegment(ContentSegment segment) {
        segments.add(segment);
    }

    /**
     * Get the last text segment, creating one if the last segment is not text.
     */
    public TextSegment getOrCreateLastTextSegment() {
        if (!segments.isEmpty() && segments.get(segments.size() - 1) instanceof TextSegment) {
            return (TextSegment) segments.get(segments.size() - 1);
        }
        TextSegment textSeg = new TextSegment();
        segments.add(textSeg);
        return textSeg;
    }

    /**
     * Get all text content concatenated.
     */
    public String getFullText() {
        StringBuilder sb = new StringBuilder();
        for (ContentSegment seg : segments) {
            if (seg instanceof TextSegment) {
                sb.append(((TextSegment) seg).getText());
            }
        }
        return sb.toString();
    }

    /**
     * Find a tool call segment by its tool ID.
     */
    public ToolCallSegment findToolCall(String toolId) {
        for (ContentSegment seg : segments) {
            if (seg instanceof ToolCallSegment) {
                ToolCallSegment tc = (ToolCallSegment) seg;
                if (toolId.equals(tc.getToolId())) {
                    return tc;
                }
            }
        }
        return null;
    }

    // ==================== Content Segment Types ====================

    /**
     * Base class for content segments within a message.
     */
    public static abstract class ContentSegment {
        public abstract String getSegmentType();
    }

    /**
     * A text segment - contains streamed text that accumulates over time.
     */
    public static class TextSegment extends ContentSegment {
        private final StringBuilder text = new StringBuilder();

        @Override
        public String getSegmentType() { return "text"; }

        public void appendText(String delta) {
            text.append(delta);
        }

        public String getText() {
            return text.toString();
        }

        public int getLength() {
            return text.length();
        }
    }

    /**
     * An image segment - raw image bytes (PNG, JPG, etc.) + a display name.
     * Used for inline image rendering in user message bubbles when the user
     * pastes/attaches an image. The bytes are sent to the CLI as base64 in
     * the JSON message; the UI renders a thumbnail from the same bytes.
     */
    public static class ImageSegment extends ContentSegment {
        private final byte[] bytes;
        private final String name;
        private final String mediaType;

        public ImageSegment(byte[] bytes, String name, String mediaType) {
            this.bytes = bytes;
            this.name = name;
            this.mediaType = mediaType;
        }

        @Override
        public String getSegmentType() { return "image"; }

        public byte[] getBytes() { return bytes; }
        public String getName() { return name; }
        public String getMediaType() { return mediaType; }
    }

    /**
     * A tool call segment - represents Claude using a tool (Read, Edit, Bash, etc.).
     */
    public static class ToolCallSegment extends ContentSegment {
        private String toolId;
        private String toolName;
        private String input;           // Tool input (JSON string or description)
        private volatile String output;  // Tool result text
        private volatile ToolStatus status = ToolStatus.RUNNING;
        private final StringBuilder inputBuilder = new StringBuilder();

        @Override
        public String getSegmentType() { return "tool_use"; }

        public String getToolId() { return toolId; }
        public void setToolId(String toolId) { this.toolId = toolId; }

        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }

        public String getInput() { return input; }
        public void setInput(String input) { this.input = input; }

        public String getOutput() { return output; }
        public void setOutput(String output) { this.output = output; }

        public ToolStatus getStatus() { return status; }
        public void setStatus(ToolStatus status) { this.status = status; }

        /**
         * Append incremental tool input (from input_json_delta stream events).
         */
        public void appendInput(String delta) {
            inputBuilder.append(delta);
            this.input = inputBuilder.toString();
        }

        /**
         * Get a human-readable display name for the tool.
         */
        public String getDisplayName() {
            if (toolName == null) return "Unknown Tool";
            switch (toolName) {
                case "Read": return "Read File";
                case "Edit": return "Edit File";
                case "Write": return "Write File";
                case "Bash": return "Run Command";
                case "Grep": return "Search Code";
                case "Glob": return "Find Files";
                case "WebSearch": return "Web Search";
                case "WebFetch": return "Fetch URL";
                case "Task": return "Sub-Agent";
                case "TodoWrite": return "Update Tasks";
                default: return toolName;
            }
        }

        /**
         * Get a short summary of what the tool is doing.
         */
        public String getSummary() {
            if (input == null || input.isEmpty()) return getDisplayName();
            // Try to extract the key parameter for common tools
            try {
                if ("Read".equals(toolName) || "Write".equals(toolName)) {
                    int idx = input.indexOf("file_path");
                    if (idx >= 0) {
                        String path = extractJsonStringValue(input, "file_path");
                        if (path != null) {
                            // Show just the filename
                            int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
                            return getDisplayName() + ": " + (lastSlash >= 0 ? path.substring(lastSlash + 1) : path);
                        }
                    }
                } else if ("Edit".equals(toolName)) {
                    String path = extractJsonStringValue(input, "file_path");
                    if (path != null) {
                        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
                        return getDisplayName() + ": " + (lastSlash >= 0 ? path.substring(lastSlash + 1) : path);
                    }
                } else if ("Bash".equals(toolName)) {
                    String cmd = extractJsonStringValue(input, "command");
                    if (cmd != null) {
                        return getDisplayName() + ": " + (cmd.length() > 60 ? cmd.substring(0, 57) + "..." : cmd);
                    }
                } else if ("Grep".equals(toolName)) {
                    String pattern = extractJsonStringValue(input, "pattern");
                    if (pattern != null) {
                        return getDisplayName() + ": " + pattern;
                    }
                }
            } catch (Exception ignored) {}
            return getDisplayName();
        }

        private String extractJsonStringValue(String json, String key) {
            String marker = "\"" + key + "\":\"";
            int start = json.indexOf(marker);
            if (start < 0) {
                // Try with space after colon
                marker = "\"" + key + "\": \"";
                start = json.indexOf(marker);
            }
            if (start < 0) return null;
            start += marker.length();
            int end = json.indexOf("\"", start);
            if (end < 0) return null;
            return json.substring(start, end).replace("\\n", "\n").replace("\\\"", "\"");
        }
    }

    /**
     * A tool result segment - the result returned from a tool call.
     */
    public static class ToolResultSegment extends ContentSegment {
        private String toolUseId;
        private String content;
        private boolean isError;

        @Override
        public String getSegmentType() { return "tool_result"; }

        public String getToolUseId() { return toolUseId; }
        public void setToolUseId(String toolUseId) { this.toolUseId = toolUseId; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public boolean isError() { return isError; }
        public void setError(boolean isError) { this.isError = isError; }
    }
}
