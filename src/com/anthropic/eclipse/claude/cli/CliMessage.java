package com.anthropic.eclipse.claude.cli;

import java.util.*;

import com.anthropic.eclipse.claude.util.JsonParser;

/**
 * Base class for all NDJSON messages from/to the Claude CLI.
 * Each subclass represents a different message type in the stream-json protocol.
 */
public abstract class CliMessage {

    private final String type;

    protected CliMessage(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }

    // ==================== Message Types ====================

    /**
     * "system" init message - emitted once at session start.
     * Contains session info, available tools, model, and working directory.
     */
    public static class SystemInit extends CliMessage {
        private String subtype;
        private String sessionId;
        private String model;
        private String cwd;
        private List<String> tools;
        private String permissionMode;

        public SystemInit() { super("system"); }

        public String getSubtype() { return subtype; }
        public void setSubtype(String subtype) { this.subtype = subtype; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getCwd() { return cwd; }
        public void setCwd(String cwd) { this.cwd = cwd; }
        public List<String> getTools() { return tools; }
        public void setTools(List<String> tools) { this.tools = tools; }
        public String getPermissionMode() { return permissionMode; }
        public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }
    }

    /**
     * Non-init system message — hooks (SessionStart, UserPromptSubmit, etc.),
     * compact_boundary, and other notifications the CLI emits with type=system
     * and a non-init subtype.
     *
     * In particular, enterprise installations frequently configure SessionStart
     * hooks that talk to AWS SSO / Bedrock for auth; if the token has expired,
     * the hook surfaces the error here via {@code stderr} while still reporting
     * {@code outcome:"success"} — so {@code stderr} is the only signal.
     */
    public static class SystemNotification extends CliMessage {
        private String subtype;       // hook_started, hook_progress, hook_response, compact_boundary, etc.
        private String hookName;
        private String hookEvent;
        private String hookId;
        private String stdout;
        private String stderr;
        private Integer exitCode;
        private String outcome;
        private String sessionId;
        private String rawJson;       // full JSON snippet for diagnostics

        public SystemNotification() { super("system"); }

        public String getSubtype() { return subtype; }
        public void setSubtype(String s) { this.subtype = s; }
        public String getHookName() { return hookName; }
        public void setHookName(String s) { this.hookName = s; }
        public String getHookEvent() { return hookEvent; }
        public void setHookEvent(String s) { this.hookEvent = s; }
        public String getHookId() { return hookId; }
        public void setHookId(String s) { this.hookId = s; }
        public String getStdout() { return stdout; }
        public void setStdout(String s) { this.stdout = s; }
        public String getStderr() { return stderr; }
        public void setStderr(String s) { this.stderr = s; }
        public Integer getExitCode() { return exitCode; }
        public void setExitCode(Integer e) { this.exitCode = e; }
        public String getOutcome() { return outcome; }
        public void setOutcome(String s) { this.outcome = s; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String s) { this.sessionId = s; }
        public String getRawJson() { return rawJson; }
        public void setRawJson(String s) { this.rawJson = s; }

        /**
         * True if {@code stderr} or {@code stdout} contains a recognizable error pattern.
         * Used to decide whether to surface this notification to the user.
         */
        public boolean hasErrorIndicator() {
            String s = (stderr != null ? stderr : "") + " " + (stdout != null ? stdout : "");
            String low = s.toLowerCase();
            return low.contains("[error]")
                || low.contains("error:")
                || low.contains("token has expired")
                || low.contains("authentication failed")
                || low.contains("unauthorized")
                || low.contains("permission denied")
                || (exitCode != null && exitCode != 0);
        }
    }

    /**
     * "assistant" message - a full assistant turn with content blocks.
     * Content can include text blocks and tool_use blocks.
     */
    public static class AssistantMessage extends CliMessage {
        private List<ContentBlock> content = new ArrayList<>();
        private String stopReason;
        private UsageData usage;

        public AssistantMessage() { super("assistant"); }

        public List<ContentBlock> getContent() { return content; }
        public void setContent(List<ContentBlock> content) { this.content = content; }
        public String getStopReason() { return stopReason; }
        public void setStopReason(String stopReason) { this.stopReason = stopReason; }
        public UsageData getUsage() { return usage; }
        public void setUsage(UsageData usage) { this.usage = usage; }
    }

    /**
     * "user" message - tool results echoed back (tool_result content blocks).
     */
    public static class UserMessage extends CliMessage {
        private List<ContentBlock> content = new ArrayList<>();

        public UserMessage() { super("user"); }

        public List<ContentBlock> getContent() { return content; }
        public void setContent(List<ContentBlock> content) { this.content = content; }
    }

    /**
     * "result" message - final result with cost, usage, and duration.
     * Emitted when a query (all turns) completes.
     */
    public static class ResultMessage extends CliMessage {
        private String subtype; // "success" or "error"
        private String result;  // final text result
        private String sessionId;
        private double costUsd;
        private int inputTokens;
        private int outputTokens;
        private long durationMs;
        private int numTurns;
        private boolean isError;
        private UsageData usage;

        public ResultMessage() { super("result"); }

        public String getSubtype() { return subtype; }
        public void setSubtype(String subtype) { this.subtype = subtype; }
        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public double getCostUsd() { return costUsd; }
        public void setCostUsd(double costUsd) { this.costUsd = costUsd; }
        public int getInputTokens() { return inputTokens; }
        public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }
        public int getOutputTokens() { return outputTokens; }
        public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
        public int getNumTurns() { return numTurns; }
        public void setNumTurns(int numTurns) { this.numTurns = numTurns; }
        public boolean isError() { return isError; }
        public void setError(boolean isError) { this.isError = isError; }
        public UsageData getUsage() { return usage; }
        public void setUsage(UsageData usage) { this.usage = usage; }
    }

    /**
     * "stream_event" - token-by-token streaming delta.
     * Follows the Claude API streaming protocol events.
     */
    public static class StreamEvent extends CliMessage {
        private String eventType;     // "message_start", "content_block_start", "content_block_delta",
                                       // "content_block_stop", "message_delta", "message_stop"
        private int index;            // content block index
        private ContentBlock contentBlock; // for content_block_start
        private Delta delta;          // for content_block_delta and message_delta
        private String sessionId;
        private String uuid;

        public StreamEvent() { super("stream_event"); }

        public String getEventType() { return eventType; }
        public void setEventType(String eventType) { this.eventType = eventType; }
        public int getIndex() { return index; }
        public void setIndex(int index) { this.index = index; }
        public ContentBlock getContentBlock() { return contentBlock; }
        public void setContentBlock(ContentBlock contentBlock) { this.contentBlock = contentBlock; }
        public Delta getDelta() { return delta; }
        public void setDelta(Delta delta) { this.delta = delta; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
    }

    // ==================== Content Types ====================

    /**
     * A content block within a message - can be text, tool_use, or tool_result.
     */
    public static class ContentBlock {
        private String type;   // "text", "tool_use", "tool_result"
        private String text;   // for text blocks
        private String id;     // for tool_use (tool call ID)
        private String name;   // for tool_use (tool name: Read, Edit, Bash, etc.)
        private Object input;  // for tool_use (tool input, can be Map or String)
        private String content; // for tool_result (result content)
        private String toolUseId; // for tool_result (references the tool_use id)
        private boolean isError;  // for tool_result

        public ContentBlock() {}

        public ContentBlock(String type) {
            this.type = type;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Object getInput() { return input; }
        public void setInput(Object input) { this.input = input; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getToolUseId() { return toolUseId; }
        public void setToolUseId(String toolUseId) { this.toolUseId = toolUseId; }
        public boolean isError() { return isError; }
        public void setError(boolean isError) { this.isError = isError; }

        /**
         * Get the input as a string (for display purposes).
         */
        public String getInputAsString() {
            if (input == null) return "";
            if (input instanceof String) return (String) input;
            return input.toString();
        }
    }

    /**
     * A delta within a stream event - incremental text or tool input update.
     */
    public static class Delta {
        private String type;  // "text_delta", "input_json_delta"
        private String text;  // partial text for text_delta
        private String partialJson; // partial JSON for input_json_delta
        private String stopReason;  // for message_delta

        public Delta() {}

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getPartialJson() { return partialJson; }
        public void setPartialJson(String partialJson) { this.partialJson = partialJson; }
        public String getStopReason() { return stopReason; }
        public void setStopReason(String stopReason) { this.stopReason = stopReason; }
    }

    /**
     * Token usage data.
     */
    public static class UsageData {
        private int inputTokens;
        private int outputTokens;

        public UsageData() {}
        public UsageData(int inputTokens, int outputTokens) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
        }

        public int getInputTokens() { return inputTokens; }
        public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }
        public int getOutputTokens() { return outputTokens; }
        public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
    }

    // ==================== Permission Request ====================

    /**
     * Permission request message - sent by CLI when it needs approval
     * to execute a tool (in stream-json mode with non-bypass permission modes).
     *
     * Possible NDJSON types from CLI:
     *   - old format: "tool_use_permission", "permission_request", "tool_permission"
     *     → respond with: {"type":"permission_response","tool_use_id":"...","permission":"allow"}
     *   - new format: "control_request" with subtype "can_use_tool"
     *     → respond with: {"type":"control_response","request_id":"...","response":{"behavior":"allow"}}
     */
    public static class PermissionRequest extends CliMessage {
        private String toolUseId;     // for old-format responses
        private String requestId;     // for new control_request responses
        private boolean controlRequest; // true when type == "control_request"
        private String toolName;
        private String description;
        private Object toolInput;
        private Map<String, Object> rawJson;

        public PermissionRequest() { super("permission_request"); }

        public String getToolUseId() { return toolUseId; }
        public void setToolUseId(String toolUseId) { this.toolUseId = toolUseId; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }
        public boolean isControlRequest() { return controlRequest; }
        public void setControlRequest(boolean controlRequest) { this.controlRequest = controlRequest; }
        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Object getToolInput() { return toolInput; }
        public void setToolInput(Object toolInput) { this.toolInput = toolInput; }
        public Map<String, Object> getRawJson() { return rawJson; }
        public void setRawJson(Map<String, Object> rawJson) { this.rawJson = rawJson; }
    }

    // ==================== Rate Limit Event ====================

    /**
     * Informational event about API rate limits.
     * {@code status} is "allowed" when the request proceeds normally,
     * and something else (e.g. "rejected", "throttled") when actually blocked.
     * {@code overageStatus} describes the POLICY for overage, not the current request.
     */
    public static class RateLimitEvent extends CliMessage {
        private String status;
        private String overageStatus;

        public RateLimitEvent() { super("rate_limit_event"); }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getOverageStatus() { return overageStatus; }
        public void setOverageStatus(String overageStatus) { this.overageStatus = overageStatus; }

        /** True when the API actually rejected/throttled the request. */
        public boolean isRejected() {
            return status != null && !"allowed".equals(status);
        }
    }

    // ==================== User Input Message (for stdin) ====================

    /**
     * Creates a user input message JSON string for sending to CLI stdin.
     */
    public static String createUserInputJson(String userContent) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"");
        sb.append(JsonParser.escapeJsonString(userContent));
        sb.append("\"}}");
        return sb.toString();
    }

    /**
     * Creates a user input message with structured content (e.g., for tool results).
     */
    public static String createUserInputJsonWithContent(List<ContentBlock> contentBlocks) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[");
        for (int i = 0; i < contentBlocks.size(); i++) {
            ContentBlock block = contentBlocks.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"type\":\"").append(JsonParser.escapeJsonString(block.getType())).append("\"");
            if (block.getToolUseId() != null) {
                sb.append(",\"tool_use_id\":\"").append(JsonParser.escapeJsonString(block.getToolUseId())).append("\"");
            }
            if (block.getContent() != null) {
                sb.append(",\"content\":\"").append(JsonParser.escapeJsonString(block.getContent())).append("\"");
            }
            if (block.isError()) {
                sb.append(",\"is_error\":true");
            }
            sb.append("}");
        }
        sb.append("]}}");
        return sb.toString();
    }

    /**
     * Creates a user input message with text AND base64-encoded PNG images.
     * Used when the user attaches images via the 📎 button.
     */
    public static String createUserInputJsonRich(String textContent, List<byte[]> imageDataList) {
        if (imageDataList == null || imageDataList.isEmpty()) {
            return createUserInputJson(textContent);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[");
        boolean first = true;
        for (byte[] imageBytes : imageDataList) {
            if (!first) sb.append(",");
            first = false;
            String base64 = java.util.Base64.getEncoder().encodeToString(imageBytes);
            sb.append("{\"type\":\"image\",\"source\":{");
            sb.append("\"type\":\"base64\",");
            sb.append("\"media_type\":\"image/png\",");
            sb.append("\"data\":\"").append(base64).append("\"}}");
        }
        if (!first) sb.append(",");
        sb.append("{\"type\":\"text\",\"text\":\"").append(JsonParser.escapeJsonString(textContent)).append("\"}");
        sb.append("]}}");
        return sb.toString();
    }

    /**
     * Creates a permission response (old format) to allow/deny a tool execution.
     * Used when the CLI sent a "permission_request" / "tool_use_permission" message.
     */
    public static String createPermissionResponse(String toolUseId, boolean allow) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"permission_response\"");
        if (toolUseId != null) {
            sb.append(",\"tool_use_id\":\"").append(JsonParser.escapeJsonString(toolUseId)).append("\"");
        }
        sb.append(",\"permission\":\"").append(allow ? "allow" : "deny").append("\"");
        sb.append("}");
        return sb.toString();
    }

    /**
     * Creates a control response (new format) to allow/deny a tool execution.
     * Used when the CLI sent a "control_request" with subtype "can_use_tool".
     *
     * Correct format (from Claude Code SDK Zod schema):
     *
     * Allow:
     * {
     *   "type": "control_response",
     *   "response": {
     *     "subtype": "success",
     *     "request_id": "<same as control_request.request_id>",
     *     "response": {"updatedInput": <original_tool_input_or_empty_object>}
     *   }
     * }
     *
     * Deny:
     * {
     *   "type": "control_response",
     *   "response": {
     *     "subtype": "success",
     *     "request_id": "<same as control_request.request_id>",
     *     "response": {"behavior": "deny", "message": "User denied"}
     *   }
     * }
     *
     * The Zod union requires EITHER {updatedInput: record} (allow) OR {behavior:"deny", message:string} (deny).
     *
     * @param requestId   The request_id from the control_request message
     * @param allow       true to allow, false to deny
     * @param toolInput   The original tool input from control_request.request.input (may be null → uses {})
     */
    public static String createControlResponse(String requestId, boolean allow, Object toolInput) {
        String escapedId = requestId != null ? JsonParser.escapeJsonString(requestId) : "";
        String innerResponse;
        if (allow) {
            // CLI schema requires BOTH behavior:"allow" AND updatedInput (the original tool input)
            // This is confirmed from the CLI source: checkPermissions(T) { return {behavior:"allow",updatedInput:T} }
            String inputJson = (toolInput != null) ? JsonParser.toJson(toolInput) : "{}";
            innerResponse = "{\"behavior\":\"allow\",\"updatedInput\":" + inputJson + "}";
        } else {
            innerResponse = "{\"behavior\":\"deny\",\"message\":\"User denied\"}";
        }
        return "{\"type\":\"control_response\",\"response\":{\"subtype\":\"success\",\"request_id\":\""
            + escapedId + "\",\"response\":" + innerResponse + "}}";
    }

    /**
     * Convenience overload with no toolInput (uses empty object for updatedInput).
     */
    public static String createControlResponse(String requestId, boolean allow) {
        return createControlResponse(requestId, allow, null);
    }

}
