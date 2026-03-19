package com.anthropic.eclipse.claude.cli;

/**
 * Listener for CLI process state changes.
 */
public interface ICliStateListener {

    /**
     * Called when the CLI process state changes.
     * @param oldState The previous state
     * @param newState The new state
     */
    void onStateChanged(ClaudeCliManager.ProcessState oldState, ClaudeCliManager.ProcessState newState);
}
