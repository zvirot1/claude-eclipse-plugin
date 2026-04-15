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

        // Initialize singleton services (no CLI manager here — one per session)
        sessionManager = new ClaudeSessionManager();
        editDecisionManager = new EditDecisionManager();
        checkpointManager = new CheckpointManager();
        settingsReader = new ClaudeSettingsReader();

        // Migrate API key from plain preference store to encrypted secure storage (one-time)
        SecureApiKeyStore.migrateIfNeeded();
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
     * Set the active ConversationModel. Called by ClaudeConversationView when it
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
