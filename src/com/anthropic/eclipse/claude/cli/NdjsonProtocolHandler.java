package com.anthropic.eclipse.claude.cli;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import com.anthropic.eclipse.claude.Activator;
import com.anthropic.eclipse.claude.util.JsonParser;

/**
 * Handles the NDJSON (Newline-Delimited JSON) protocol for communication
 * with the Claude CLI process in stream-json mode.
 *
 * Reads NDJSON lines from CLI stdout and parses them into CliMessage objects.
 * Writes NDJSON messages to CLI stdin for multi-turn conversation.
 */
public class NdjsonProtocolHandler {

    private final InputStream stdout;
    private final OutputStream stdin;
    private final InputStream stderr;

    private final List<ICliMessageListener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean running = false;
    private Thread readerThread;
    private Thread errorThread;

    public NdjsonProtocolHandler(InputStream stdout, OutputStream stdin, InputStream stderr) {
        this.stdout = stdout;
        this.stdin = stdin;
        this.stderr = stderr;
    }

    /**
     * Start background threads reading from stdout and stderr.
     */
    public void startReading() {
        running = true;

        readerThread = new Thread(this::readLoop, "Claude-CLI-NDJSON-Reader");
        readerThread.setDaemon(true);
        readerThread.start();

        errorThread = new Thread(this::errorLoop, "Claude-CLI-Error-Reader");
        errorThread.setDaemon(true);
        errorThread.start();
    }

    /**
     * Write a user message to stdin as NDJSON.
     */
    public synchronized void writeMessage(String userContent) {
        String json = CliMessage.createUserInputJson(userContent);
        writeRawNdjson(json);
    }

    /**
     * Write a raw NDJSON line to stdin.
     */
    public synchronized void writeRawNdjson(String jsonLine) {
        long t0 = System.currentTimeMillis();
        int bytesWritten = 0;
        try {
            byte[] bytes = (jsonLine + "\n").getBytes(StandardCharsets.UTF_8);
            bytesWritten = bytes.length;
            stdin.write(bytes);
            stdin.flush();
            Activator.logDiag("[DIAG-PERF] writeRawNdjson elapsed="
                    + (System.currentTimeMillis() - t0) + "ms bytes=" + bytesWritten);
        } catch (IOException e) {
            Activator.logDiag("[DIAG-PERF] writeRawNdjson elapsed="
                    + (System.currentTimeMillis() - t0) + "ms bytes=" + bytesWritten
                    + " err=" + e.getMessage());
            for (ICliMessageListener listener : listeners) {
                listener.onConnectionError(e);
            }
        }
    }

    /**
     * Stop reading threads and prevent any further message dispatch.
     * <p>
     * This method is safe to call from the UI thread.  It does NOT close
     * the underlying streams (that would deadlock on Windows because the
     * reader thread holds a lock on the InputStream).  Instead it:
     * <ol>
     *   <li>Sets {@code running = false} so the while-condition fails on
     *       the next iteration of the read loop</li>
     *   <li>Clears the listener list so even an in-flight dispatch is a
     *       no-op — no messages can reach the UI</li>
     *   <li>Interrupts the threads (belt-and-suspenders)</li>
     * </ol>
     * The caller is expected to kill the CLI process afterwards
     * ({@code destroyForcibly()}), which closes the pipes and causes
     * {@code readLine()} to return null, ending the read loop.
     */
    public void stop() {
        running = false;

        // Remove all listeners so no messages reach the UI even if the
        // read loop manages one more iteration before seeing running==false.
        listeners.clear();

        if (readerThread != null) {
            readerThread.interrupt();
        }
        if (errorThread != null) {
            errorThread.interrupt();
        }
    }

    /**
     * Add a listener for parsed messages.
     */
    public void addListener(ICliMessageListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a listener.
     */
    public void removeListener(ICliMessageListener listener) {
        listeners.remove(listener);
    }

    // ==================== Internal Read Loops ====================

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stdout, StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    CliMessage message = parseLine(line);
                    if (message != null) {
                        for (ICliMessageListener listener : listeners) {
                            listener.onMessage(message);
                        }
                    }
                } catch (Exception e) {
                    for (ICliMessageListener listener : listeners) {
                        listener.onParseError(line, e);
                    }
                }
            }
        } catch (IOException e) {
            if (running) {
                for (ICliMessageListener listener : listeners) {
                    listener.onConnectionError(e);
                }
            }
        }
    }

    private void errorLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stderr, StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                // Log stderr to System.err for debugging
                Activator.logInfo("[Claude CLI stderr] " + line);
            }
        } catch (IOException e) {
            // stderr closed - normal during shutdown
        }
    }

    // ==================== NDJSON Parsing ====================

    /**
     * Parse a single NDJSON line into the appropriate CliMessage subclass.
     */
    CliMessage parseLine(String jsonLine) {
        Map<String, Object> json = JsonParser.parseObject(jsonLine);
        String type = JsonParser.getString(json, "type");
        if (type == null) return null;

        switch (type) {
            case "system":
                {
                    String subtype = JsonParser.getString(json, "subtype");
                    if (subtype == null || "init".equals(subtype)) {
                        return parseSystemInit(json);
                    }
                    return parseSystemNotification(json, jsonLine);
                }
            case "assistant":
                return parseAssistantMessage(json);
            case "user":
                return parseUserMessage(json);
            case "result":
                return parseResultMessage(json);
            case "stream_event":
                return parseStreamEvent(json);
            case "tool_use_permission":
            case "permission_request":
            case "tool_permission":
                return parsePermissionRequest(json, type);
            case "control_request":
                // New CLI format for permission prompts (can_use_tool subtype)
                return parseControlRequest(json);
            case "rate_limit_event":
                Map<String, Object> rlInfo = JsonParser.getMap(json, "rate_limit_info");
                String rlStatus = JsonParser.getString(rlInfo, "status");
                String rlOverage = JsonParser.getString(rlInfo, "overageStatus");
                Activator.logInfo("[NDJSON] rate_limit_event: status=" + rlStatus
                    + " overageStatus=" + rlOverage);
                CliMessage.RateLimitEvent rlEvent = new CliMessage.RateLimitEvent();
                rlEvent.setStatus(rlStatus);
                rlEvent.setOverageStatus(rlOverage);
                return rlEvent;
            case "tool_use_summary":
                // Informational summary of a completed tool use — no action needed
                return null;
            default:
                // Log unknown message types for debugging
                Activator.logWarning("[NDJSON] Unknown message type: " + type
                    + " - raw: " + jsonLine.substring(0, Math.min(200, jsonLine.length())));
                // Check if this might be a permission-related message
                if (type.contains("permission") || type.contains("Permission")) {
                    return parsePermissionRequest(json, type);
                }
                return null;
        }
    }

    private CliMessage.SystemNotification parseSystemNotification(Map<String, Object> json, String rawLine) {
        CliMessage.SystemNotification n = new CliMessage.SystemNotification();
        n.setSubtype(JsonParser.getString(json, "subtype"));
        n.setHookName(JsonParser.getString(json, "hook_name"));
        n.setHookEvent(JsonParser.getString(json, "hook_event"));
        n.setHookId(JsonParser.getString(json, "hook_id"));
        n.setStdout(JsonParser.getString(json, "stdout"));
        n.setStderr(JsonParser.getString(json, "stderr"));
        Object ec = json.get("exit_code");
        if (ec instanceof Number) n.setExitCode(((Number) ec).intValue());
        n.setOutcome(JsonParser.getString(json, "outcome"));
        n.setSessionId(JsonParser.getString(json, "session_id"));
        n.setRawJson(rawLine.length() > 1000 ? rawLine.substring(0, 1000) : rawLine);
        return n;
    }

    private CliMessage.SystemInit parseSystemInit(Map<String, Object> json) {
        CliMessage.SystemInit msg = new CliMessage.SystemInit();
        msg.setSubtype(JsonParser.getString(json, "subtype"));
        msg.setSessionId(JsonParser.getString(json, "session_id"));
        msg.setModel(JsonParser.getString(json, "model"));
        msg.setCwd(JsonParser.getString(json, "cwd"));
        msg.setPermissionMode(JsonParser.getString(json, "permission_mode"));

        List<Object> toolsRaw = JsonParser.getList(json, "tools");
        if (toolsRaw != null) {
            List<String> tools = new ArrayList<>();
            for (Object t : toolsRaw) {
                if (t != null) tools.add(t.toString());
            }
            msg.setTools(tools);
        }
        return msg;
    }

    private CliMessage.AssistantMessage parseAssistantMessage(Map<String, Object> json) {
        CliMessage.AssistantMessage msg = new CliMessage.AssistantMessage();

        Map<String, Object> messageObj = JsonParser.getMap(json, "message");
        if (messageObj != null) {
            msg.setStopReason(JsonParser.getString(messageObj, "stop_reason"));
            msg.setContent(parseContentBlocks(messageObj));
            msg.setUsage(parseUsage(JsonParser.getMap(messageObj, "usage")));
        } else {
            // Some formats put content directly on the message
            msg.setContent(parseContentBlocks(json));
            msg.setStopReason(JsonParser.getString(json, "stop_reason"));
        }
        return msg;
    }

    private CliMessage.UserMessage parseUserMessage(Map<String, Object> json) {
        CliMessage.UserMessage msg = new CliMessage.UserMessage();

        Map<String, Object> messageObj = JsonParser.getMap(json, "message");
        if (messageObj != null) {
            msg.setContent(parseContentBlocks(messageObj));
        } else {
            msg.setContent(parseContentBlocks(json));
        }
        return msg;
    }

    private CliMessage.ResultMessage parseResultMessage(Map<String, Object> json) {
        CliMessage.ResultMessage msg = new CliMessage.ResultMessage();
        msg.setSubtype(JsonParser.getString(json, "subtype"));
        msg.setResult(JsonParser.getString(json, "result"));
        msg.setSessionId(JsonParser.getString(json, "session_id"));
        msg.setCostUsd(JsonParser.getDouble(json, "cost_usd", 0.0));
        msg.setDurationMs(JsonParser.getLong(json, "duration_ms", 0));
        msg.setNumTurns(JsonParser.getInt(json, "num_turns", 0));
        msg.setError("error".equals(msg.getSubtype()));

        Map<String, Object> usage = JsonParser.getMap(json, "usage");
        if (usage != null) {
            msg.setInputTokens(JsonParser.getInt(usage, "input_tokens", 0));
            msg.setOutputTokens(JsonParser.getInt(usage, "output_tokens", 0));
            msg.setUsage(parseUsage(usage));
        }
        return msg;
    }

    @SuppressWarnings("unchecked")
    private CliMessage.StreamEvent parseStreamEvent(Map<String, Object> json) {
        CliMessage.StreamEvent msg = new CliMessage.StreamEvent();
        msg.setSessionId(JsonParser.getString(json, "session_id"));
        msg.setUuid(JsonParser.getString(json, "uuid"));

        Map<String, Object> event = JsonParser.getMap(json, "event");
        if (event == null) {
            // Sometimes the event data is directly on the message
            event = json;
        }

        msg.setEventType(JsonParser.getString(event, "type"));
        msg.setIndex(JsonParser.getInt(event, "index", 0));

        // Parse content_block for content_block_start
        Map<String, Object> contentBlock = JsonParser.getMap(event, "content_block");
        if (contentBlock != null) {
            msg.setContentBlock(parseSingleContentBlock(contentBlock));
        }

        // Parse delta for content_block_delta and message_delta
        Map<String, Object> deltaObj = JsonParser.getMap(event, "delta");
        if (deltaObj != null) {
            CliMessage.Delta delta = new CliMessage.Delta();
            delta.setType(JsonParser.getString(deltaObj, "type"));
            delta.setText(JsonParser.getString(deltaObj, "text"));
            delta.setPartialJson(JsonParser.getString(deltaObj, "partial_json"));
            delta.setStopReason(JsonParser.getString(deltaObj, "stop_reason"));
            msg.setDelta(delta);
        }

        return msg;
    }

    /**
     * Parse a "control_request" message (new CLI permission format).
     * Format: {"type":"control_request","request_id":"...","request":{"subtype":"can_use_tool","tool_name":"...","input":{...}}}
     * Response expected: {"type":"control_response","request_id":"...","response":{"behavior":"allow"}}
     */
    private CliMessage.PermissionRequest parseControlRequest(Map<String, Object> json) {
        CliMessage.PermissionRequest msg = new CliMessage.PermissionRequest();
        msg.setRawJson(json);
        msg.setControlRequest(true);

        // Top-level request_id — used to correlate the control_response
        String requestId = JsonParser.getString(json, "request_id");
        msg.setRequestId(requestId);

        // Nested "request" object contains the tool details
        Map<String, Object> request = JsonParser.getMap(json, "request");
        if (request != null) {
            msg.setToolName(JsonParser.getString(request, "tool_name"));
            msg.setToolInput(request.get("input"));

            // Build a human-readable description from the input
            Object inputObj = request.get("input");
            if (inputObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> inputMap = (Map<String, Object>) inputObj;
                // For Bash, show the command; for others show first key
                String cmd = JsonParser.getString(inputMap, "command");
                if (cmd != null) {
                    msg.setDescription("Run: " + (cmd.length() > 80 ? cmd.substring(0, 77) + "..." : cmd));
                } else if (!inputMap.isEmpty()) {
                    Map.Entry<String, Object> first = inputMap.entrySet().iterator().next();
                    String val = first.getValue() != null ? first.getValue().toString() : "";
                    msg.setDescription(first.getKey() + ": " + (val.length() > 80 ? val.substring(0, 77) + "..." : val));
                }
            }
        }

        Activator.logInfo("[NDJSON] control_request received: tool=" + msg.getToolName()
            + " request_id=" + requestId);
        return msg;
    }

    private CliMessage.PermissionRequest parsePermissionRequest(Map<String, Object> json, String type) {
        CliMessage.PermissionRequest msg = new CliMessage.PermissionRequest();
        msg.setRawJson(json);

        // Try common field names for the tool info
        msg.setToolUseId(JsonParser.getString(json, "tool_use_id"));
        if (msg.getToolUseId() == null) {
            msg.setToolUseId(JsonParser.getString(json, "id"));
        }

        msg.setToolName(JsonParser.getString(json, "tool"));
        if (msg.getToolName() == null) {
            msg.setToolName(JsonParser.getString(json, "tool_name"));
        }
        if (msg.getToolName() == null) {
            msg.setToolName(JsonParser.getString(json, "name"));
        }

        msg.setDescription(JsonParser.getString(json, "description"));
        if (msg.getDescription() == null) {
            msg.setDescription(JsonParser.getString(json, "message"));
        }

        msg.setToolInput(json.get("input"));
        if (msg.getToolInput() == null) {
            msg.setToolInput(json.get("tool_input"));
        }

        Activator.logInfo("[NDJSON] Permission request received: tool=" + msg.getToolName()
            + " id=" + msg.getToolUseId() + " desc=" + msg.getDescription());

        return msg;
    }

    // ==================== Helper Parsing Methods ====================

    @SuppressWarnings("unchecked")
    private List<CliMessage.ContentBlock> parseContentBlocks(Map<String, Object> parent) {
        List<CliMessage.ContentBlock> blocks = new ArrayList<>();
        Object contentObj = parent.get("content");

        if (contentObj instanceof List) {
            List<Object> contentList = (List<Object>) contentObj;
            for (Object item : contentList) {
                if (item instanceof Map) {
                    blocks.add(parseSingleContentBlock((Map<String, Object>) item));
                }
            }
        } else if (contentObj instanceof String) {
            // Simple string content
            CliMessage.ContentBlock textBlock = new CliMessage.ContentBlock("text");
            textBlock.setText((String) contentObj);
            blocks.add(textBlock);
        }
        return blocks;
    }

    private CliMessage.ContentBlock parseSingleContentBlock(Map<String, Object> blockMap) {
        CliMessage.ContentBlock block = new CliMessage.ContentBlock();
        block.setType(JsonParser.getString(blockMap, "type"));
        block.setText(JsonParser.getString(blockMap, "text"));
        block.setId(JsonParser.getString(blockMap, "id"));
        block.setName(JsonParser.getString(blockMap, "name"));
        block.setContent(JsonParser.getString(blockMap, "content"));
        block.setToolUseId(JsonParser.getString(blockMap, "tool_use_id"));
        block.setError(JsonParser.getBoolean(blockMap, "is_error", false));

        // Input can be a complex object
        Object inputObj = blockMap.get("input");
        if (inputObj != null) {
            block.setInput(inputObj);
        }

        return block;
    }

    private CliMessage.UsageData parseUsage(Map<String, Object> usage) {
        if (usage == null) return null;
        return new CliMessage.UsageData(
            JsonParser.getInt(usage, "input_tokens", 0),
            JsonParser.getInt(usage, "output_tokens", 0)
        );
    }
}
