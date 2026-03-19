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

    @SuppressWarnings("unchecked")
    private void mergeFile(Path path, Map<String, Object> target) {
        if (!Files.exists(path)) return;
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) return;
            Map<String, Object> parsed = JsonParser.parseObject(content);
            target.putAll(parsed);
        } catch (IOException e) {
            Activator.logWarning("[ClaudeSettingsReader] Could not read " + path + ": " + e.getMessage());
        } catch (Exception e) {
            Activator.logWarning("[ClaudeSettingsReader] Could not parse " + path + ": " + e.getMessage());
        }
    }
}
