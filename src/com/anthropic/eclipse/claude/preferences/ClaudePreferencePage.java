package com.anthropic.eclipse.claude.preferences;

import org.eclipse.jface.preference.*;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import com.anthropic.eclipse.claude.Activator;

/**
 * Preference page for Claude AI Plugin settings.
 * Accessible via Window > Preferences > Claude AI
 */
public class ClaudePreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    public ClaudePreferencePage() {
        super(GRID);
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription("Configure Claude AI Plugin settings.\n" +
                       "Get your API key from https://console.anthropic.com/");
    }

    @Override
    public void createFieldEditors() {
        // === API Settings ===
        // API key is stored in encrypted secure-preferences, not plain IPreferenceStore
        addField(new SecureStringFieldEditor(
            PreferenceConstants.API_KEY,
            "Anthropic API Key (stored encrypted):",
            getFieldEditorParent()
        ));

        addField(new EditableComboFieldEditor(
            PreferenceConstants.MODEL,
            "Model:",
            new String[] {
                "default", "sonnet", "opus", "haiku",
                "claude-sonnet-4-6", "claude-opus-4-6", "claude-haiku-4-5",
                "claude-sonnet-4-6-20250514", "claude-opus-4-6-20250514",
            },
            getFieldEditorParent()
        ));

        addField(new IntegerFieldEditor(
            PreferenceConstants.MAX_TOKENS,
            "Max Response Tokens:",
            getFieldEditorParent()
        ));

        // === CLI Settings ===
        addField(new StringFieldEditor(
            PreferenceConstants.CLI_PATH,
            "Claude CLI Path (auto-detected if empty):",
            getFieldEditorParent()
        ));

        addField(new ComboFieldEditor(
            PreferenceConstants.PERMISSION_MODE,
            "Permission Mode:",
            new String[][] {
                {"Default (ask for each action)", "default"},
                {"Accept Edits (auto-approve file edits)", "acceptEdits"},
                {"Bypass Permissions (allow all)", "bypassPermissions"},
                {"Plan Mode (read-only)", "plan"},
            },
            getFieldEditorParent()
        ));

        addField(new StringFieldEditor(
            PreferenceConstants.AUTO_APPROVE_TOOLS,
            "Auto-Approve Tools (comma-separated):",
            getFieldEditorParent()
        ));

        addField(new IntegerFieldEditor(
            PreferenceConstants.MAX_TURNS,
            "Max Turns (0 = unlimited):",
            getFieldEditorParent()
        ));

        // === UI Settings ===
        addField(new ComboFieldEditor(
            PreferenceConstants.THEME_MODE,
            "Theme:",
            new String[][] {
                {"Follow Eclipse Theme (Auto)", "auto"},
                {"Light", "light"},
                {"Dark", "dark"},
            },
            getFieldEditorParent()
        ));

        addField(new BooleanFieldEditor(
            PreferenceConstants.SHOW_STREAMING,
            "Show token streaming (real-time text)",
            getFieldEditorParent()
        ));

        addField(new BooleanFieldEditor(
            PreferenceConstants.SHOW_COST,
            "Show cost/usage in status bar",
            getFieldEditorParent()
        ));

        addField(new BooleanFieldEditor(
            PreferenceConstants.USE_CLI_MODE,
            "Use Claude CLI (recommended; disable for direct API mode)",
            getFieldEditorParent()
        ));

        // === Advanced ===
        addField(new StringFieldEditor(
            PreferenceConstants.SYSTEM_PROMPT,
            "System Prompt:",
            40,
            StringFieldEditor.VALIDATE_ON_FOCUS_LOST,
            getFieldEditorParent()
        ));

        addField(new IntegerFieldEditor(
            PreferenceConstants.SESSION_HISTORY_LIMIT,
            "Max Stored Sessions:",
            getFieldEditorParent()
        ));

        // Local skills folder — used by the Skills & Plugins dialog.
        // Default is ~/.claude/skills/ (matches Claude CLI).
        addField(new DirectoryFieldEditor(
            PreferenceConstants.SKILLS_FOLDER,
            "Local skills folder:",
            getFieldEditorParent()
        ));

        // Tab-title strategy
        addField(new ComboFieldEditor(
            PreferenceConstants.TAB_TITLE_STRATEGY,
            "Tab title strategy:",
            new String[][] {
                {"Self-generated topic (LLM-written, 3-5 words)", "self_generated"},
                {"First user message (truncated to 30 chars)",     "first_message"},
            },
            getFieldEditorParent()
        ));

        // Diagnostic logging — only enable when investigating bugs.
        // Emits verbose [DIAG] entries to the Error Log.
        addField(new BooleanFieldEditor(
            PreferenceConstants.DIAGNOSTIC_LOGGING,
            "Enable diagnostic logging (verbose [DIAG] entries in Error Log)",
            getFieldEditorParent()
        ));
    }

    @Override
    public void init(IWorkbench workbench) {}
}
