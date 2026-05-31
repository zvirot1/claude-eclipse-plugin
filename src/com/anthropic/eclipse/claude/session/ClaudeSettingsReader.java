package com.anthropic.eclipse.claude.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.anthropic.eclipse.claude.Activator;
import com.anthropic.eclipse.claude.util.JsonParser;

/**
 * Reads Claude CLI settings files and makes them available to the plugin.
 *
 * Priority (highest to lowest):
 *   1. Eclipse plugin preferences (always win)
 *   2. Project-level  .claude/settings.local.json
 *   3. Project-level  .claude/settings.json
 *   4. User-level     ~/.claude/settings.json
 *
 * This allows users who have already configured Claude CLI to get sensible
 * defaults in Eclipse without having to duplicate their configuration.
 */
public class ClaudeSettingsReader {

    private static final String USER_SETTINGS    = ".claude/settings.json";
    private static final String PROJECT_SETTINGS = ".claude/settings.json";
    private static final String PROJECT_LOCAL    = ".claude/settings.local.json";

    /**
     * Load merged settings from all applicable settings files.
     * Lower-priority entries are present but can be overridden by callers
     * using Eclipse preferences.
     *
     * @param projectDir the current project working directory (may be null)
     * @return merged key→value map (never null)
     */
    public Map<String, Object> loadMergedSettings(String projectDir) {
        Map<String, Object> merged = new LinkedHashMap<>();

        // 1. User-level settings (lowest precedence)
        Path userSettings = Paths.get(System.getProperty("user.home"), USER_SETTINGS);
        mergeFile(userSettings, merged);

        // 2. Project-level settings
        if (projectDir != null && !projectDir.isBlank()) {
            mergeFile(Paths.get(projectDir, PROJECT_SETTINGS), merged);
            mergeFile(Paths.get(projectDir, PROJECT_LOCAL), merged);
        }

        return Collections.unmodifiableMap(merged);
    }

    /**
     * Convenience: return only the user-level settings.
     */
    public Map<String, Object> loadUserSettings() {
        Map<String, Object> result = new LinkedHashMap<>();
        mergeFile(Paths.get(System.getProperty("user.home"), USER_SETTINGS), result);
        return Collections.unmodifiableMap(result);
    }

    /**
     * Get a string value from the user settings file.
     *
     * @param key      JSON key to look up (e.g. "model", "permissionMode")
     * @param fallback value to return if the key is absent or the file is missing
     */
    public String getUserSetting(String key, String fallback) {
        Map<String, Object> settings = loadUserSettings();
        String value = JsonParser.getString(settings, key);
        return (value != null) ? value : fallback;
    }

    /**
     * Read the user's default effort level from {@code ~/.claude/settings.json}.
     * Returns null if unset or the value is invalid (callers should treat null
     * as "Auto" — no {@code --effort} flag passed).
     */
    public String getUserEffortLevel() {
        String value = getUserSetting("effortLevel", null);
        if (value == null) return null;
        switch (value) {
            case "low": case "medium": case "high": case "max":
                return value;
            default:
                return null;
        }
    }

    /**
     * Read the user's default permission mode from {@code ~/.claude/settings.json}.
     * Checks {@code permissions.defaultMode} (new layout) then falls back to
     * {@code permissionMode} (legacy key).
     */
    @SuppressWarnings("unchecked")
    public String getUserPermissionMode() {
        Map<String, Object> settings = loadUserSettings();
        Object perms = settings.get("permissions");
        if (perms instanceof Map) {
            Object def = ((Map<String, Object>) perms).get("defaultMode");
            if (def instanceof String) return (String) def;
        }
        return JsonParser.getString(settings, "permissionMode");
    }

    /**
     * Get a boolean value from the user settings file.
     */
    public boolean getUserSettingBoolean(String key, boolean fallback) {
        Map<String, Object> settings = loadUserSettings();
        Object value = settings.get(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String)  return Boolean.parseBoolean((String) value);
        return fallback;
    }

    // ==================== Internal ====================

    // Retry parameters for the case where another process (typically a running
    // Claude CLI — each conversation view now owns its own) is mid-write to
    // the same file and we briefly see truncated / invalid JSON.
    private static final int    MAX_READ_ATTEMPTS   = 4;
    private static final long[] BACKOFF_DELAYS_MS   = { 25L, 75L, 200L };

    @SuppressWarnings("unchecked")
    private void mergeFile(Path path, Map<String, Object> target) {
        if (!Files.exists(path)) return;

        Exception lastError = null;
        for (int attempt = 0; attempt < MAX_READ_ATTEMPTS; attempt++) {
            try {
                String content = Files.readString(path, StandardCharsets.UTF_8).trim();
                if (content.isEmpty()) return;
                Map<String, Object> parsed = JsonParser.parseObject(content);
                target.putAll(parsed);
                return; // success
            } catch (IOException | RuntimeException e) {
                // IOException: file locked / disappeared mid-read
                // RuntimeException (from JsonParser): truncated / invalid JSON
                lastError = e;
                if (attempt < BACKOFF_DELAYS_MS.length) {
                    try {
                        Thread.sleep(BACKOFF_DELAYS_MS[attempt]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // Exhausted retries — log and move on. A missing read just means the
        // caller gets defaults from lower-precedence sources.
        String kind = (lastError instanceof IOException) ? "read" : "parse";
        Activator.logWarning("[ClaudeSettingsReader] Could not " + kind + " " + path
                + " after " + MAX_READ_ATTEMPTS + " attempts: "
                + (lastError != null ? lastError.getMessage() : "unknown"));
    }
}
