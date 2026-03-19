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
    private ClaudeCliManager cliManager;
    private ClaudeSessionManager sessionManager;
    private EditDecisionManager editDecisionManager;
    private CheckpointManager checkpointManager;
    private ClaudeSettingsReader settingsReader;

    // Shared conversation model (set by the active view)
    private ConversationModel conversationModel;
    private final List<Consumer<ConversationModel>> modelChangeListeners = new CopyOnWriteArrayList<>();

    public Activator() {}

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;

        // Initialize singleton services
        cliManager = new ClaudeCliManager();
        sessionManager = new ClaudeSessionManager();
        editDecisionManager = new EditDecisionManager();
        checkpointManager = new CheckpointManager();
        settingsReader = new ClaudeSettingsReader();

        // Migrate API key from plain preference store to encrypted secure storage (one-time)
        SecureApiKeyStore.migrateIfNeeded();
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        // Shutdown CLI process
        if (cliManager != null) {
            cliManager.stop();
        }
        plugin = null;
        super.stop(context);
    }

    public static Activator getDefault() {
        return plugin;
    }

    /**
     * Get the singleton CLI manager.
     */
    public ClaudeCliManager getCliManager() {
        return cliManager;
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
