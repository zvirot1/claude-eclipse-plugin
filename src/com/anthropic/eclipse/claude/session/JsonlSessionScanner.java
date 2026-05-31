package com.anthropic.eclipse.claude.session;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.anthropic.eclipse.claude.Activator;
import com.anthropic.eclipse.claude.model.SessionInfo;
import com.anthropic.eclipse.claude.util.JsonParser;

/**
 * Scans Claude CLI's JSONL transcript files in {@code ~/.claude/projects/} to produce
 * a {@link SessionInfo} list — a direct analogue of VS Code extension's
 * {@code listSessions()} (which calls {@code @anthropic-ai/claude-agent-sdk}'s
 * {@code listSessions()}).
 *
 * <p>This is the single source of truth for conversation history — the plugin's
 * {@link SessionStore} is only a secondary cache of display summaries.
 *
 * <p>Project directory name convention mirrors the CLI:
 * {@code C:\dev\foo} → {@code C--dev-foo}.
 */
public class JsonlSessionScanner {

    /**
     * Returns all sessions across all projects, newest-first by file mtime.
     * If {@code projectDir} is non-null, restricts to sessions whose project
     * directory matches that path.
     */
    /**
     * Fast variant: returns SessionInfo for every JSONL with ONLY filename +
     * mtime — no file content read. Used to populate the Session History list
     * instantly. Call {@link #fillSessionDetails(SessionInfo)} on demand to
     * fetch summary/model/messageCount for a row the user is interested in.
     */
    public static List<SessionInfo> listSessionsFast(String projectDir) {
        long t0 = System.currentTimeMillis();
        List<SessionInfo> result = new ArrayList<>();
        File projectsRoot = new File(System.getProperty("user.home") + "/.claude/projects");
        if (!projectsRoot.isDirectory()) return result;
        File[] projectDirs = projectsRoot.listFiles(File::isDirectory);
        if (projectDirs == null) return result;
        String matchPrefix = (projectDir != null && !projectDir.isEmpty())
                ? encodeProjectKey(projectDir).toLowerCase() : null;
        for (File dir : projectDirs) {
            if (matchPrefix != null) {
                String lower = dir.getName().toLowerCase();
                if (!lower.equals(matchPrefix) && !lower.startsWith(matchPrefix + "-")) continue;
            }
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                String n = f.getName();
                if (!n.endsWith(".jsonl")) continue;
                String sessionId = n.substring(0, n.length() - 6);
                if (!isUuid(sessionId)) continue;
                SessionInfo info = new SessionInfo(sessionId);
                info.setLastActiveTime(f.lastModified());
                info.setStartTime(f.lastModified());
                info.setWorkingDirectory(decodeProjectKey(dir.getName()));
                info.setMessageCount(0); // unknown until detailed read
                result.add(info);
            }
        }
        result.sort(Comparator.comparingLong(SessionInfo::getLastActiveTime).reversed());
        Activator.logDiag("[DIAG-PERF] listSessionsFast elapsed="
                + (System.currentTimeMillis() - t0) + "ms files=" + result.size()
                + " projectDir=" + projectDir);
        return result;
    }

    /**
     * Lazily read summary/model/messageCount for a single SessionInfo by
     * locating its .jsonl on disk. Mutates the input. No-op if not found.
     */
    public static void fillSessionDetails(SessionInfo info) {
        if (info == null || info.getSessionId() == null) return;
        SessionInfo full = findSessionById(info.getSessionId());
        if (full == null) return;
        if (full.getSummary() != null) info.setSummary(full.getSummary());
        if (full.getModel() != null)   info.setModel(full.getModel());
        if (full.getMessageCount() > 0) info.setMessageCount(full.getMessageCount());
        if (full.getStartTime() > 0)   info.setStartTime(full.getStartTime());
    }

    public static List<SessionInfo> listSessions(String projectDir) {
        List<SessionInfo> result = new ArrayList<>();
        File projectsRoot = new File(System.getProperty("user.home") + "/.claude/projects");
        if (!projectsRoot.isDirectory()) return result;

        File[] projectDirs = projectsRoot.listFiles(File::isDirectory);
        if (projectDirs == null) return result;

        String matchPrefix = null;
        if (projectDir != null && !projectDir.isEmpty()) {
            matchPrefix = encodeProjectKey(projectDir);
        }

        for (File dir : projectDirs) {
            if (matchPrefix != null) {
                String name = dir.getName();
                String lower = name.toLowerCase();
                String lowerPrefix = matchPrefix.toLowerCase();
                // Match either exact or with worktree suffix ("foo-bar")
                if (!lower.equals(lowerPrefix)
                        && !lower.startsWith(lowerPrefix + "-")) {
                    continue;
                }
            }
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                String n = f.getName();
                if (!n.endsWith(".jsonl")) continue;
                String sessionId = n.substring(0, n.length() - 6);
                if (!isUuid(sessionId)) continue; // skip subagent-*.jsonl etc.
                try {
                    SessionInfo info = buildSessionInfo(f, sessionId, dir.getName());
                    if (info != null) result.add(info);
                } catch (Exception e) {
                    // Skip unreadable / malformed JSONL
                }
            }
        }

        // Newest first
        result.sort(Comparator.comparingLong(SessionInfo::getLastActiveTime).reversed());
        return result;
    }

    /**
     * Build a SessionInfo from a .jsonl file: read up to the first user message
     * to extract a summary, then use file mtime as lastActiveTime and file size
     * as proxy for messageCount (not perfect but cheap).
     */
    @SuppressWarnings("unchecked")
    /**
     * Look up the SessionInfo (including summary) for a given sessionId by
     * searching every project under {@code ~/.claude/projects/} for a
     * matching {@code <id>.jsonl}. Returns null when no such file exists.
     * Used by Resume to derive the tab title without regenerating it.
     */
    public static SessionInfo findSessionById(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return null;
        String home = System.getProperty("user.home");
        java.io.File root = new java.io.File(home, ".claude/projects");
        if (!root.exists() || !root.isDirectory()) return null;
        java.io.File[] dirs = root.listFiles(java.io.File::isDirectory);
        if (dirs == null) return null;
        for (java.io.File dir : dirs) {
            java.io.File candidate = new java.io.File(dir, sessionId + ".jsonl");
            if (!candidate.exists()) continue;
            try {
                return buildSessionInfo(candidate, sessionId, dir.getName());
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static SessionInfo buildSessionInfo(File jsonl, String sessionId, String projectKey) {
        long t0 = System.currentTimeMillis();
        SessionInfo info = new SessionInfo(sessionId);
        info.setLastActiveTime(jsonl.lastModified());
        info.setWorkingDirectory(decodeProjectKey(projectKey));

        String firstUserSummary = null;
        String cliSummary = null; // last {"type":"summary",...} wins
        int messageCount = 0;
        long createdAt = 0L;
        String model = null;

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(jsonl), StandardCharsets.UTF_8))) {
            String line;
            int linesRead = 0;
            // Cap at 500 lines per file. With many GB of JSONL across all
            // projects, full scans block the dialog for minutes. The first
            // 500 lines reliably contain the first user message and any
            // early CLI summary; messageCount becomes a lower bound.
            final int MAX_LINES_PER_FILE = 500;
            while ((line = br.readLine()) != null && linesRead++ < MAX_LINES_PER_FILE) {
                if (line.isEmpty()) continue;
                // Fast-path: skip JSON parse on lines that aren't of a type we
                // care about. Tool-use / tool-result lines can be megabytes
                // long; parsing them just to discard the result was the main
                // hot-spot when scanning big workspaces.
                // Cheap pre-filter: we only care about user/assistant/summary
                // entries. Skip lines that don't mention any of those values.
                // CLI 2.1.107+ omits top-level type for assistant turns —
                // detect them via "role":"assistant" inside the message obj.
                if (line.indexOf("\"type\":\"user\"")      < 0
                 && line.indexOf("\"type\":\"assistant\"") < 0
                 && line.indexOf("\"type\":\"summary\"")   < 0
                 && line.indexOf("\"role\":\"assistant\"") < 0) continue;
                try {
                    Map<String, Object> obj = JsonParser.parseObject(line);
                    String type = JsonParser.getString(obj, "type");
                    if (type == null) {
                        // CLI 2.1.107+: derive type from message.role for
                        // assistant turns that no longer carry top-level type.
                        Map<String, Object> mm = JsonParser.getMap(obj, "message");
                        if (mm != null) {
                            String role = JsonParser.getString(mm, "role");
                            if ("assistant".equals(role) || "user".equals(role)) {
                                type = role;
                            }
                        }
                        if (type == null) continue;
                    }
                    // CLI writes timestamp per entry as ISO-8601 string — use first one as createdAt
                    if (createdAt == 0L) {
                        String ts = JsonParser.getString(obj, "timestamp");
                        if (ts != null) {
                            try {
                                createdAt = java.time.Instant.parse(ts).toEpochMilli();
                            } catch (Exception ignored) {}
                        }
                    }
                    // Backport from IntelliJ commit 3233fd4: prefer the CLI's
                    // auto-generated {"type":"summary","summary":"…"} entries as
                    // the session title. These are tight LLM-written summaries
                    // the CLI itself produces — much better than the raw first
                    // user message. Last summary wins (CLI may emit several).
                    if ("summary".equals(type)) {
                        String s = JsonParser.getString(obj, "summary");
                        if (s != null && !s.isEmpty()) {
                            cliSummary = s;
                        }
                        continue;
                    }
                    // First user message → fallback summary (used only if CLI
                    // hasn't written a {"type":"summary"} entry yet).
                    if (firstUserSummary == null && "user".equals(type)) {
                        Map<String, Object> msg = JsonParser.getMap(obj, "message");
                        if (msg != null) {
                            Object content = msg.get("content");
                            String text = extractUserText(content);
                            // Strip the file-XML prefix (handleInput prepends
                            // <file path=…>…</file> blocks for active-file pin
                            // and @-mentions — they are not part of what the
                            // user typed and should not appear in the title).
                            text = stripPrependedFileBlocksForSummary(text);
                            if (text != null && !text.isEmpty()) {
                                firstUserSummary = text.length() > 60
                                        ? text.substring(0, 57) + "..."
                                        : text;
                            }
                        }
                    }
                    if ("user".equals(type) || "assistant".equals(type)) {
                        messageCount++;
                    }
                    if (model == null) {
                        Map<String, Object> msg = JsonParser.getMap(obj, "message");
                        if (msg != null) {
                            String m = JsonParser.getString(msg, "model");
                            if (m != null) model = m;
                        }
                    }
                } catch (Exception lineEx) {
                    // Skip malformed line
                }
            }
        } catch (Exception e) {
            return null;
        }

        if (messageCount == 0) {
            Activator.logDiag("[DIAG-PERF] buildSessionInfo elapsed="
                    + (System.currentTimeMillis() - t0) + "ms session=" + sessionId
                    + " result=empty fileSize=" + jsonl.length());
            return null; // not really a conversation
        }

        // Prefer CLI's auto-generated summary; fall back to first user message
        String summary = (cliSummary != null && !cliSummary.isEmpty())
                ? (cliSummary.length() > 60 ? cliSummary.substring(0, 57) + "..." : cliSummary)
                : firstUserSummary;
        info.setSummary(summary);
        info.setMessageCount(messageCount);
        info.setModel(model);
        if (createdAt > 0) info.setStartTime(createdAt);
        Activator.logDiag("[DIAG-PERF] buildSessionInfo elapsed="
                + (System.currentTimeMillis() - t0) + "ms session=" + sessionId
                + " msgs=" + messageCount + " fileSize=" + jsonl.length());
        return info;
    }

    /**
     * Strip leading {@code <file path="…">…</file>} blocks from text when used
     * as a summary. Mirrors {@code stripPrependedFileBlocks} in
     * ClaudeConversationView. Idempotent on already-clean text.
     */
    static String stripPrependedFileBlocksForSummary(String s) {
        if (s == null || s.isEmpty()) return s;
        // (?is) = case-insensitive + dotall
        s = s.replaceAll("(?is)^\\s*\\[Active editor context:[^\\]]*\\]\\s*", "");
        s = s.replaceAll("(?is)^(?:\\s*<file\\s+path=\"[^\"]*\"[^>]*>.*?</file>\\s*)+", "");
        return s;
    }

    @SuppressWarnings("unchecked")
    private static String extractUserText(Object content) {
        if (content instanceof String) return (String) content;
        if (content instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object part : (List<Object>) content) {
                if (part instanceof Map) {
                    Map<String, Object> p = (Map<String, Object>) part;
                    Object t = p.get("type");
                    if ("text".equals(t)) {
                        Object txt = p.get("text");
                        if (txt != null) sb.append(txt.toString());
                    }
                }
            }
            return sb.toString();
        }
        return null;
    }

    /**
     * Convert project path like {@code C:\dev\foo} to {@code C--dev-foo}
     * (matches Claude CLI's encoding).
     */
    private static String encodeProjectKey(String path) {
        return path.replace(':', '-').replace('\\', '-').replace('/', '-');
    }

    /**
     * Reverse of encodeProjectKey: {@code C--dev-foo} → {@code C:\dev\foo}.
     * Used for display/debugging only; best-effort — we can't fully recover
     * the original separators.
     */
    private static String decodeProjectKey(String key) {
        // CLI encoding is lossy (drive letter colon + separators collapse),
        // so we can only take a guess. Leave as-is for display.
        return key;
    }

    private static boolean isUuid(String s) {
        if (s == null || s.length() != 36) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') return false;
            } else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    /** For diagnostics: log one-liner summary of scan results. */
    public static void logScanSummary(String projectDir) {
        try {
            List<SessionInfo> s = listSessions(projectDir);
            Activator.logInfo("[JsonlSessionScanner] Found " + s.size()
                + " sessions" + (projectDir != null ? " for " + projectDir : ""));
        } catch (Exception e) {
            Activator.logError("[JsonlSessionScanner] scan failed", e);
        }
    }
}
