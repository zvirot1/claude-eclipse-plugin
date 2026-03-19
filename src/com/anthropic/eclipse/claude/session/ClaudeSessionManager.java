package com.anthropic.eclipse.claude.session;

import java.util.*;

import com.anthropic.eclipse.claude.Activator;
import com.anthropic.eclipse.claude.model.ConversationModel;
import com.anthropic.eclipse.claude.model.SessionInfo;
import com.anthropic.eclipse.claude.preferences.PreferenceConstants;

/**
 * Manages Claude conversation sessions.
 * Handles creating, resuming, and persisting sessions.
 */
public class ClaudeSessionManager {

    private final SessionStore store;
    private SessionInfo currentSession;
    private static final int DEFAULT_MAX_STORED_SESSIONS = 50;

    public ClaudeSessionManager() {
        this.store = new SessionStore();
    }

    /**
     * Start a new session.
     */
    public SessionInfo startNewSession(String workingDirectory) {
        currentSession = new SessionInfo(UUID.randomUUID().toString());
        currentSession.setWorkingDirectory(workingDirectory);
        store.saveSession(currentSession);
        store.cleanupOldSessions(getMaxStoredSessions());
        return currentSession;
    }

    /**
     * Resume a previous session by ID.
     */
    public SessionInfo resumeSession(String sessionId) {
        SessionInfo info = store.loadSession(sessionId);
        if (info != null) {
            currentSession = info;
            currentSession.touch();
            store.saveSession(currentSession);
        }
        return info;
    }

    /**
     * Continue the most recent session.
     */
    public SessionInfo continueLastSession() {
        List<SessionInfo> sessions = store.listAllSessions();
        if (!sessions.isEmpty()) {
            return resumeSession(sessions.get(0).getSessionId());
        }
        return null;
    }

    /**
     * List available sessions.
     */
    public List<SessionInfo> listSessions() {
        return store.listAllSessions();
    }

    /**
     * Save current session state from a ConversationModel.
     */
    public void saveCurrentSession(ConversationModel model) {
        if (currentSession == null) return;

        SessionInfo modelSession = model.getSessionInfo();
        if (modelSession != null) {
            currentSession.setSessionId(modelSession.getSessionId());
            currentSession.setModel(modelSession.getModel());
            if (modelSession.getWorkingDirectory() != null) {
                currentSession.setWorkingDirectory(modelSession.getWorkingDirectory());
            }
        }
        currentSession.setMessageCount(model.getMessageCount());
        currentSession.touch();

        // Generate summary from first user message
        if (currentSession.getSummary() == null && !model.getMessages().isEmpty()) {
            for (var msg : model.getMessages()) {
                if (msg.getRole() == com.anthropic.eclipse.claude.model.MessageBlock.Role.USER) {
                    String text = msg.getFullText();
                    if (text.length() > 60) {
                        text = text.substring(0, 57) + "...";
                    }
                    currentSession.setSummary(text);
                    break;
                }
            }
        }

        store.saveSession(currentSession);
    }

    /**
     * Get the current session.
     */
    public SessionInfo getCurrentSession() {
        return currentSession;
    }

    /**
     * Delete a stored session.
     */
    public void deleteSession(String sessionId) {
        store.deleteSession(sessionId);
    }

    /**
     * Get max stored sessions from preferences.
     */
    private int getMaxStoredSessions() {
        try {
            if (Activator.getDefault() != null) {
                int limit = Activator.getDefault().getPreferenceStore()
                    .getInt(PreferenceConstants.SESSION_HISTORY_LIMIT);
                if (limit > 0) return limit;
            }
        } catch (Exception ignored) {}
        return DEFAULT_MAX_STORED_SESSIONS;
    }
}
