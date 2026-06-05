package com.anthropic.eclipse.claude;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import com.anthropic.eclipse.claude.cli.ClaudeCliManager;
import com.anthropic.eclipse.claude.cli.ClaudeCliPidTracker;
import com.anthropic.eclipse.claude.diff.CheckpointManager;
import com.anthropic.eclipse.claude.diff.EditDecisionManager;
import com.anthropic.eclipse.claude.model.ConversationModel;
import com.anthropic.eclipse.claude.preferences.SecureApiKeyStore;
import com.anthropic.eclipse.claude.session.ClaudeSessionManager;
import com.anthropic.eclipse.claude.session.ClaudeSettingsReader;

/**
 * The main plugin activator for the Claude AI Eclipse Plugin.
 * Manages singleton services for CLI management, session management,
 * and edit decision management.
 */
public class Activator extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "com.anthropic.eclipse.claude";

    /**
     * Diagnostic logging toggle. When true, calls to {@link #logDiag(String)}
     * emit messages to the Error Log. When false, they're no-ops.
     *
     * Initial value comes from -Dclaude.diag=true (JVM system property).
     * Updated at runtime by the preference page (DIAGNOSTIC_LOGGING).
     */
    public static volatile boolean DIAG_ENABLED = Boolean.getBoolean("claude.diag");

    private static Activator plugin;

    // Singleton services
    // NOTE: Each conversation session owns its own ClaudeCliManager — the
    // plugin no longer maintains a shared CLI process. See the "active CLI
    // manager" tracking below for status-bar integration.
    private ClaudeSessionManager sessionManager;
    private EditDecisionManager editDecisionManager;
    private CheckpointManager checkpointManager;
    private ClaudeSettingsReader settingsReader;

    // Shared conversation model (set by the active view)
    private ConversationModel conversationModel;
    private final List<Consumer<ConversationModel>> modelChangeListeners = new CopyOnWriteArrayList<>();

    // Active CLI manager — the CLI of the currently focused conversation view.
    // The status-bar contribution listens to this so it reflects the active tab.
    private ClaudeCliManager activeCliManager;
    private final List<Consumer<ClaudeCliManager>> cliManagerChangeListeners = new CopyOnWriteArrayList<>();
    // Every CLI manager that has ever been created but not yet disposed — so we
    // can stop them all on plugin shutdown.
    private final List<ClaudeCliManager> allCliManagers = new CopyOnWriteArrayList<>();

    public Activator() {}

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;

        // Reap any Claude CLI processes left running by a previous Eclipse
        // session that did not shut down cleanly (crash, taskkill, hard
        // logoff). The tracker writes PIDs at spawn time and removes them on
        // normal stop; whatever's left here is orphaned and would otherwise
        // double-charge the corporate AV / Bedrock proxy when the user opens
        // a new tab.
        try {
            int killed = ClaudeCliPidTracker.cleanupOrphans();
            if (killed > 0) {
                logInfo("[PidTracker] Reaped " + killed + " orphaned Claude CLI process(es) from previous session");
            }
        } catch (Throwable t) {
            // Never block plug-in start on tracker hygiene.
            logWarning("[PidTracker] cleanup failed at startup: " + t.getMessage());
        }

        // Initialize singleton services (no CLI manager here — one per session)
        sessionManager = new ClaudeSessionManager();
        editDecisionManager = new EditDecisionManager();
        checkpointManager = new CheckpointManager();
        settingsReader = new ClaudeSettingsReader();

        // Migrate API key from plain preference store to encrypted secure storage (one-time)
        SecureApiKeyStore.migrateIfNeeded();

        // Ensure the configured Local skills folder exists. Without this, the
        // Preferences page's DirectoryFieldEditor for SKILLS_FOLDER refuses to
        // validate ("Value must be an existing directory") and disables Apply
        // for the entire Claude AI preference page — locking the user out of
        // every unrelated setting (model, API key, theme, ...). Idempotent.
        try {
            String configured = getPreferenceStore().getString(
                    com.anthropic.eclipse.claude.preferences.PreferenceConstants.SKILLS_FOLDER);
            if (configured == null || configured.isBlank()) {
                configured = java.nio.file.Paths.get(
                        System.getProperty("user.home"), ".claude", "skills").toString();
            }
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(configured));
        } catch (Throwable t) {
            // Best-effort: a permission error here just leaves the user with
            // the original validation issue, but never breaks plug-in start.
            logWarning("[Activator] could not create skills folder: " + t.getMessage());
        }

        // Initialize DIAG_ENABLED from preference (OR'd with the system-property default)
        try {
            boolean prefEnabled = getPreferenceStore().getBoolean(
                com.anthropic.eclipse.claude.preferences.PreferenceConstants.DIAGNOSTIC_LOGGING);
            DIAG_ENABLED = DIAG_ENABLED || prefEnabled;
            // React to preference changes at runtime
            getPreferenceStore().addPropertyChangeListener(evt -> {
                if (com.anthropic.eclipse.claude.preferences.PreferenceConstants.DIAGNOSTIC_LOGGING
                        .equals(evt.getProperty())) {
                    boolean now = Boolean.parseBoolean(String.valueOf(evt.getNewValue()))
                            || Boolean.getBoolean("claude.diag");
                    DIAG_ENABLED = now;
                    logInfo("[DIAG-START] Diagnostic logging " + (now ? "ENABLED" : "DISABLED")
                            + " via preference at " + System.currentTimeMillis());
                }
            });
            if (DIAG_ENABLED) {
                logInfo("[DIAG-START] Diagnostic logging ENABLED at startup (sysProp="
                        + Boolean.getBoolean("claude.diag") + ", pref=" + prefEnabled + ")");
            }
        } catch (Throwable t) {
            // Best-effort; never block startup over diagnostic plumbing
        }
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        // Shutdown every CLI process that was ever created
        for (ClaudeCliManager mgr : allCliManagers) {
            try { mgr.stop(); } catch (Exception ignored) {}
        }
        allCliManagers.clear();
        plugin = null;
        super.stop(context);
    }

    public static Activator getDefault() {
        return plugin;
    }

    /**
     * Create a brand-new CLI manager dedicated to a single conversation view/session.
     * The caller is responsible for calling {@link ClaudeCliManager#stop()} on dispose.
     * The manager is automatically tracked so it can be stopped on plugin shutdown.
     */
    public ClaudeCliManager createCliManager() {
        ClaudeCliManager mgr = new ClaudeCliManager();
        allCliManagers.add(mgr);
        return mgr;
    }

    /**
     * Unregister a CLI manager when its owning view is disposed.
     * Does NOT call stop() — caller is expected to have done that already.
     */
    public void releaseCliManager(ClaudeCliManager mgr) {
        if (mgr != null) {
            allCliManagers.remove(mgr);
            if (activeCliManager == mgr) {
                setActiveCliManager(null);
            }
        }
    }

    /**
     * Get the CLI manager of the currently active conversation view (may be null).
     * Used by the workbench status bar to display per-tab state.
     */
    public ClaudeCliManager getActiveCliManager() {
        return activeCliManager;
    }

    /**
     * Set the active CLI manager (called by a view when it gains focus or is created).
     */
    public void setActiveCliManager(ClaudeCliManager mgr) {
        this.activeCliManager = mgr;
        for (Consumer<ClaudeCliManager> listener : cliManagerChangeListeners) {
            try { listener.accept(mgr); } catch (Exception ignored) {}
        }
    }

    /**
     * Register a listener that fires whenever the active CLI manager changes.
     */
    public void addActiveCliManagerListener(Consumer<ClaudeCliManager> listener) {
        cliManagerChangeListeners.add(listener);
    }

    public void removeActiveCliManagerListener(Consumer<ClaudeCliManager> listener) {
        cliManagerChangeListeners.remove(listener);
    }

    /**
     * Get the singleton session manager.
     */
    public ClaudeSessionManager getSessionManager() {
        return sessionManager;
    }

    /**
     * Get the singleton edit decision manager.
     */
    public EditDecisionManager getEditDecisionManager() {
        return editDecisionManager;
    }

    /**
     * Get the singleton checkpoint manager.
     */
    public CheckpointManager getCheckpointManager() {
        return checkpointManager;
    }

    /**
     * Get the singleton Claude settings reader (reads ~/.claude/settings.json etc.)
     */
    public ClaudeSettingsReader getSettingsReader() {
        return settingsReader;
    }

    /**
     * Get the currently active ConversationModel (may be null if no view is open).
     */
    public ConversationModel getConversationModel() {
        return conversationModel;
    }

    /**
     * Set the active ConversationModel. Called by ClaudeConversationViewV2 when it
     * creates or destroys its model. Notifies any registered model-change listeners.
     */
    public void setConversationModel(ConversationModel model) {
        this.conversationModel = model;
        for (Consumer<ConversationModel> listener : modelChangeListeners) {
            try { listener.accept(model); } catch (Exception ignored) {}
        }
    }

    /**
     * Register a listener that is called whenever the active ConversationModel changes.
     */
    public void addConversationModelListener(Consumer<ConversationModel> listener) {
        modelChangeListeners.add(listener);
    }

    /**
     * Remove a previously registered model-change listener.
     */
    public void removeConversationModelListener(Consumer<ConversationModel> listener) {
        modelChangeListeners.remove(listener);
    }

    // ==================== Logging Helpers ====================

    /**
     * Log an informational message.
     */
    public static void logInfo(String message) {
        log(IStatus.INFO, message, null);
    }

    /**
     * Diagnostic log — only emitted when DIAG_ENABLED is true.
     * Cheap when disabled (single volatile read).
     * Use this for verbose tracing that should NOT appear in normal usage.
     */
    public static void logDiag(String message) {
        if (DIAG_ENABLED) {
            log(IStatus.INFO, message, null);
        }
    }

    /**
     * Log a warning message.
     */
    public static void logWarning(String message) {
        log(IStatus.WARNING, message, null);
    }

    /**
     * Log an error message.
     */
    public static void logError(String message) {
        log(IStatus.ERROR, message, null);
    }

    /**
     * Log an error message with exception.
     */
    public static void logError(String message, Throwable t) {
        log(IStatus.ERROR, message, t);
    }

    private static void log(int severity, String message, Throwable t) {
        Activator activator = getDefault();
        if (activator != null) {
            ILog logger = activator.getLog();
            logger.log(new Status(severity, PLUGIN_ID, message, t));
        } else {
            // Fallback before plugin is initialized
            System.err.println("[Claude Plugin] " + message);
            if (t != null) {
                t.printStackTrace(System.err);
            }
        }
    }
}
