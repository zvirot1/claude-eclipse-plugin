package com.anthropic.eclipse.claude.session;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.anthropic.eclipse.claude.model.MessageBlock;
import com.anthropic.eclipse.claude.util.JsonParser;

/**
 * Loads a session's JSONL transcript into a list of {@link MessageBlock}s for
 * UI replay. Extracted from the legacy {@code ClaudeConversationView}'s
 * private {@code loadSessionHistoryFromJsonl} so the webview view can reuse
 * the same parsing logic without duplicating it.
 *
 * <p>Handles:
 * <ul>
 *   <li>User messages — plain text content, with prepended file-context
 *       blocks stripped so the bubble shows only what the user typed.
 *   <li>Assistant messages — final turns only (where {@code stop_reason} is
 *       set), with text and tool_use segments.
 *   <li>Tool calls — marked COMPLETED on replay.
 * </ul>
 *
 * <p>Skips tool_result wrapping user turns, thinking segments, and queue
 * operation entries. Caps line scanning at 20,000 lines so a runaway JSONL
 * doesn't lock the UI.
 */
public final class JsonlHistoryLoader {

    private JsonlHistoryLoader() {}

    /**
     * Scan {@code ~/.claude/projects/&lt;dir&gt;/&lt;sessionId&gt;.jsonl} and parse
     * the transcript into MessageBlocks. Returns an empty list if the file
     * isn't found or if parsing fails — never throws.
     */
    @SuppressWarnings("unchecked")
    public static List<MessageBlock> load(String sessionId) {
        List<MessageBlock> blocks = new ArrayList<>();
        if (sessionId == null || sessionId.isEmpty()) return blocks;
        try {
            File claudeProjects = new File(System.getProperty("user.home") + "/.claude/projects");
            if (!claudeProjects.exists()) return blocks;

            // Same sessionId can live in MULTIPLE project dirs — the CLI's
            // --resume mechanism creates a fresh JSONL in each new cwd it
            // sees while keeping the same sessionId. Pick the most recently
            // MODIFIED match so we load the continuation that has the
            // user's latest turns, not the original transcript.
            File jsonlFile = null;
            long bestMtime = Long.MIN_VALUE;
            File[] projectDirs = claudeProjects.listFiles(File::isDirectory);
            if (projectDirs == null) return blocks;
            for (File dir : projectDirs) {
                File candidate = new File(dir, sessionId + ".jsonl");
                if (!candidate.exists()) continue;
                long mt = candidate.lastModified();
                if (mt > bestMtime) {
                    bestMtime = mt;
                    jsonlFile = candidate;
                }
            }
            if (jsonlFile == null) return blocks;

            final int MAX_LINES = 20_000;
            int linesScanned = 0;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(jsonlFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (linesScanned++ >= MAX_LINES) break;
                    if (line.isEmpty()) continue;
                    if (line.indexOf("\"type\":\"user\"")      < 0
                     && line.indexOf("\"type\":\"assistant\"") < 0
                     && line.indexOf("\"role\":\"assistant\"") < 0) continue;
                    try {
                        Map<String, Object> obj = JsonParser.parseObject(line);
                        String type = JsonParser.getString(obj, "type");
                        Map<String, Object> msg = JsonParser.getMap(obj, "message");
                        if (msg == null) continue;

                        String role = JsonParser.getString(msg, "role");
                        if (type == null && role != null) type = role;

                        long entryTimestamp = 0L;
                        String tsStr = JsonParser.getString(obj, "timestamp");
                        if (tsStr != null) {
                            try {
                                entryTimestamp = java.time.Instant.parse(tsStr).toEpochMilli();
                            } catch (Exception ignored) {}
                        }

                        if ("user".equals(type) && "user".equals(role)) {
                            Object content = msg.get("content");
                            String text = null;
                            if (content instanceof String) {
                                text = (String) content;
                            } else if (content instanceof List) {
                                List<Object> contentList = (List<Object>) content;
                                if (contentList.isEmpty()) continue;
                                Object first = contentList.get(0);
                                if (first instanceof Map &&
                                        "tool_result".equals(JsonParser.getString((Map<String, Object>) first, "type"))) {
                                    continue;
                                }
                                StringBuilder sb = new StringBuilder();
                                for (Object item : contentList) {
                                    if (item instanceof Map) {
                                        Map<String, Object> itemMap = (Map<String, Object>) item;
                                        if ("text".equals(JsonParser.getString(itemMap, "type"))) {
                                            String t = JsonParser.getString(itemMap, "text");
                                            if (t != null) sb.append(t);
                                        }
                                    }
                                }
                                text = sb.toString();
                            }
                            text = stripPrependedFileBlocks(text);
                            if (text != null && !text.trim().isEmpty()) {
                                MessageBlock block = new MessageBlock(MessageBlock.Role.USER, entryTimestamp);
                                MessageBlock.TextSegment seg = new MessageBlock.TextSegment();
                                seg.appendText(text);
                                block.addSegment(seg);
                                blocks.add(block);
                            }

                        } else if ("assistant".equals(type) && "assistant".equals(role)) {
                            String stopReason = JsonParser.getString(msg, "stop_reason");
                            if (stopReason == null) continue;
                            Object content = msg.get("content");
                            if (!(content instanceof List)) continue;
                            List<Object> contentList = (List<Object>) content;

                            MessageBlock block = new MessageBlock(MessageBlock.Role.ASSISTANT, entryTimestamp);
                            for (Object item : contentList) {
                                if (!(item instanceof Map)) continue;
                                Map<String, Object> itemMap = (Map<String, Object>) item;
                                String itemType = JsonParser.getString(itemMap, "type");

                                if ("text".equals(itemType)) {
                                    String text = JsonParser.getString(itemMap, "text");
                                    if (text != null && !text.isEmpty()) {
                                        MessageBlock.TextSegment seg = new MessageBlock.TextSegment();
                                        seg.appendText(text);
                                        block.addSegment(seg);
                                    }
                                } else if ("tool_use".equals(itemType)) {
                                    MessageBlock.ToolCallSegment toolSeg = new MessageBlock.ToolCallSegment();
                                    toolSeg.setToolId(JsonParser.getString(itemMap, "id"));
                                    toolSeg.setToolName(JsonParser.getString(itemMap, "name"));
                                    Object inputObj = itemMap.get("input");
                                    if (inputObj != null) {
                                        toolSeg.setInput(JsonParser.toJson(inputObj));
                                    }
                                    toolSeg.setStatus(MessageBlock.ToolStatus.COMPLETED);
                                    block.addSegment(toolSeg);
                                }
                            }
                            if (!block.getSegments().isEmpty()) {
                                block.setComplete(true);
                                blocks.add(block);
                            }
                        }
                    } catch (Exception lineEx) {
                        // Skip invalid lines silently
                    }
                }
            }
        } catch (Exception ignored) {
            // Return whatever we managed to parse
        }
        return blocks;
    }

    /**
     * Strip {@code [Active editor context: ...]} prefixes and
     * {@code <file path="...">...</file>} blocks that the plugin's input
     * handler prepends to user messages, so the displayed bubble shows
     * only what the user actually typed.
     */
    private static String stripPrependedFileBlocks(String s) {
        if (s == null || s.isEmpty()) return s;
        s = s.replaceAll("(?is)^\\s*\\[Active editor context:[^\\]]*\\]\\s*", "");
        s = s.replaceAll("(?is)^(?:\\s*<file\\s+path=\"[^\"]*\"[^>]*>.*?</file>\\s*)+", "");
        return s;
    }
}
