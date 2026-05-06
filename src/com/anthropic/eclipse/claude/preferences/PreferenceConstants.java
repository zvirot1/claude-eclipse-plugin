package com.anthropic.eclipse.claude.preferences;

public class PreferenceConstants {
    // Existing API settings
    public static final String API_KEY = "apiKey";
    public static final String MODEL = "model";
    public static final String MAX_TOKENS = "maxTokens";
    public static final String SYSTEM_PROMPT = "systemPrompt";

    // CLI settings
    public static final String CLI_PATH = "cliPath";
    public static final String PERMISSION_MODE = "permissionMode";
    public static final String AUTO_APPROVE_TOOLS = "autoApproveTools";
    public static final String MAX_TURNS = "maxTurns";

    // UI settings
    public static final String SHOW_STREAMING = "showStreaming";
    public static final String SHOW_COST = "showCost";
    public static final String DEFAULT_WORKING_DIR = "defaultWorkingDir";
    public static final String THEME_MODE = "themeMode"; // "auto", "light", "dark"

    // Mode
    public static final String USE_CLI_MODE = "useCliMode";
    public static final String SESSION_HISTORY_LIMIT = "sessionHistoryLimit";

    // Input behavior
    public static final String ENTER_TO_SEND = "enterToSend";              // true = Enter sends (default)
    public static final String AUTO_SAVE_BEFORE_TOOLS = "autoSaveBeforeTools"; // save dirty editors before CLI file tools

    // Effort level (persisted across sessions)
    public static final String EFFORT_LEVEL = "effortLevel";              // "low","medium","high","max", or "" for Auto

    // Diagnostic logging — when true, the plugin emits verbose [DIAG] logs
    // to the Eclipse Error Log. Useful for investigating bugs without rebuilding.
    // Can also be enabled at startup via -Dclaude.diag=true on the JVM.
    public static final String DIAGNOSTIC_LOGGING = "diagnosticLogging";

    // Active-file pinning — when true, the currently focused editor's file is
    // automatically attached to every outgoing message. UI shows a chip with
    // the file name; clicking the chip toggles this preference.
    public static final String ATTACH_ACTIVE_FILE = "attachActiveFile";

    // Local skills folder — directory the Skills dialog scans for custom skills.
    // Default matches the Claude CLI: ~/.claude/skills/. The user can override
    // either via the Browse button in the Skills dialog or via this preference
    // in Window > Preferences > Claude AI. Both paths write to the same key.
    public static final String SKILLS_FOLDER = "skillsFolder";

    // Tab-title strategy for new conversation tabs. Values:
    //   "self_generated" (default) — after the first user message, run a
    //     one-shot `claude -p` in the background to produce a 3-5 word topic
    //     title and update the tab. Mirrors IntelliJ commit a122d84.
    //   "first_message" — keep the previous behaviour: truncate the first
    //     user message and use that.
    // The CLI's auto-generated session summary is also picked up on Resume
    // and Session History, regardless of this preference.
    public static final String TAB_TITLE_STRATEGY = "tabTitleStrategy";
}
