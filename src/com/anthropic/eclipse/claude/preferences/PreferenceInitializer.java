package com.anthropic.eclipse.claude.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;
import com.anthropic.eclipse.claude.Activator;

public class PreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();

        // Existing API settings
        store.setDefault(PreferenceConstants.API_KEY, "");
        store.setDefault(PreferenceConstants.MODEL, "sonnet");
        store.setDefault(PreferenceConstants.MAX_TOKENS, 4096);
        store.setDefault(PreferenceConstants.SYSTEM_PROMPT,
            "You are a helpful coding assistant. When analyzing code, be concise and precise. " +
            "When you see COBOL code, apply your knowledge of enterprise COBOL patterns.");

        // CLI settings
        store.setDefault(PreferenceConstants.CLI_PATH, "");
        store.setDefault(PreferenceConstants.PERMISSION_MODE, "acceptEdits");
        store.setDefault(PreferenceConstants.AUTO_APPROVE_TOOLS, "Read,Grep,Glob");
        store.setDefault(PreferenceConstants.MAX_TURNS, 0);

        // UI settings
        store.setDefault(PreferenceConstants.SHOW_STREAMING, true);
        store.setDefault(PreferenceConstants.SHOW_COST, true);
        store.setDefault(PreferenceConstants.DEFAULT_WORKING_DIR, "");
        store.setDefault(PreferenceConstants.THEME_MODE, "auto");

        // Mode
        store.setDefault(PreferenceConstants.USE_CLI_MODE, true);
        store.setDefault(PreferenceConstants.SESSION_HISTORY_LIMIT, 50);

        // Input behavior
        store.setDefault(PreferenceConstants.ENTER_TO_SEND, true);
        store.setDefault(PreferenceConstants.AUTO_SAVE_BEFORE_TOOLS, true);

        // Diagnostic logging — off by default. Enable via preferences or -Dclaude.diag=true.
        store.setDefault(PreferenceConstants.DIAGNOSTIC_LOGGING, false);

        // Auto-attach active editor file (Amazon-Q / IntelliJ style "active file" pinning).
        // ON by default — every newly focused editor file is auto-pinned. The user
        // can click the × on a chip to dismiss it for that specific file path
        // (per-path memory in the view), but the global preference stays on so a
        // new file is again auto-pinned when the user switches to it.
        store.setDefault(PreferenceConstants.ATTACH_ACTIVE_FILE, true);
    }
}
