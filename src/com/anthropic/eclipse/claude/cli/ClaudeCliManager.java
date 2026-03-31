package com.anthropic.eclipse.claude.cli;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import org.eclipse.jface.preference.IPreferenceStore;

import com.anthropic.eclipse.claude.Activator;
import com.anthropic.eclipse.claude.preferences.PreferenceConstants;
import com.anthropic.eclipse.claude.preferences.SecureApiKeyStore;

/**
 * Manages the lifecycle of an interactive Claude CLI process.
 * Replaces the old one-shot ClaudeCodeCLI with a persistent,
 * bidirectional stream-json communication channel.
 */
public class ClaudeCliManager {

    public enum ProcessState {
        NOT_STARTED,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED,
        ERROR
    }

    // Known install locations (reused from ClaudeCodeCLI)
    private static final String[] SEARCH_PATHS = {
        "/usr/local/bin/claude",
        "/usr/bin/claude",
        System.getProperty("user.home") + "/.local/bin/claude",
        System.getProperty("user.home") + "/bin/claude",
        System.getProperty("user.home") + "/.npm-global/bin/claude",
        // Windows paths
        System.getProperty("user.home") + "/AppData/Roaming/npm/claude.cmd",
        System.getProperty("user.home") + "/AppData/Local/Programs/claude/claude.exe",
        "C:/Program Files/claude/claude.exe",
    };

    private Process cliProcess;
    private volatile ProcessState state = ProcessState.NOT_STARTED;
    private NdjsonProtocolHandler protocolHandler;
    private CliProcessConfig config;
    private ScheduledExecutorService healthChecker;
    private String detectedCliPath;

    private final List<ICliStateListener> stateListeners = new CopyOnWriteArrayList<>();
    private final List<ICliMessageListener> messageListeners = new CopyOnWriteArrayList<>();

    public ClaudeCliManager() {
        detectCLI();
    }

    // ==================== CLI Detection ====================

    /**
     * Auto-detect Claude Code CLI installation.
     * @return true if CLI was found
     */
    public boolean detectCLI() {
        // First try PATH
        String fromPath = findInPath("claude");
        if (fromPath != null) {
            detectedCliPath = fromPath;
            return true;
        }

        // Try known locations
        for (String path : SEARCH_PATHS) {
            File f = new File(path);
            if (f.exists() && f.canExecute()) {
                detectedCliPath = path;
                return true;
            }
        }

        return false;
    }

    private String findInPath(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                isWindows() ? "where" : "which", command
            );
            Process p = pb.start();
            String result = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor(5, TimeUnit.SECONDS);
            if (!result.isEmpty() && new File(result.split("\n")[0]).exists()) {
                return result.split("\n")[0].trim();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Get CLI version string.
     */
    public String getVersion() {
        String cliPath = getCliPath();
        if (cliPath == null) return "Not installed";
        try {
            ProcessBuilder pb = new ProcessBuilder(cliPath, "--version");
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor(5, TimeUnit.SECONDS);
            return out.isEmpty() ? "Unknown" : out;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Check if CLI is available.
     */
    public boolean isCliAvailable() {
        return getCliPath() != null;
    }

    /**
     * Returns true if the CLI is authenticated via OAuth (claude auth login),
     * meaning it can make API calls without an ANTHROPIC_API_KEY env var.
     * Runs "claude auth status --json" and checks the "loggedIn" field.
     */
    public boolean isOAuthAuthenticated() {
        String cliPath = getCliPath();
        if (cliPath == null) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder(cliPath, "auth", "status", "--json");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor(5, TimeUnit.SECONDS);
            // Simple check: look for "loggedIn":true in the JSON output
            return out.contains("\"loggedIn\":true") || out.contains("\"loggedIn\": true");
        } catch (Exception e) {
            Activator.logWarning("[ClaudeCliManager] Could not check OAuth status: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get the CLI path (preference-configured, or auto-detected).
     */
    public String getCliPath() {
        // Check preferences first
        try {
            if (Activator.getDefault() != null) {
                IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();
                String configuredPath = prefs.getString(PreferenceConstants.CLI_PATH);
                if (configuredPath != null && !configuredPath.isBlank()) {
                    File f = new File(configuredPath);
                    if (f.exists() && f.canExecute()) {
                        return configuredPath;
                    }
                }
            }
        } catch (Exception ignored) {}
        return detectedCliPath;
    }

    /**
     * Set CLI path manually.
     */
    public void setCliPath(String path) {
        this.detectedCliPath = path;
    }

    // ==================== Process Lifecycle ====================

    /**
     * Start interactive CLI process with stream-json mode.
     */
    public synchronized void start(CliProcessConfig config) throws CliException {
        if (state == ProcessState.RUNNING) {
            throw new CliException("CLI process is already running");
        }

        this.config = config;
        ProcessState oldState = state;
        state = ProcessState.STARTING;
        fireStateChanged(oldState, ProcessState.STARTING);

        try {
            List<String> command = buildCommand(config);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(config.getWorkingDirectory()));
            pb.environment().put("FORCE_COLOR", "0");  // No ANSI colors
            pb.environment().remove("CLAUDECODE");      // Allow nested Claude CLI sessions
            pb.environment().remove("NODE_OPTIONS");     // Prevent Node.js conflicts (like VS Code does)
            // Always identify as eclipse-plugin so the CLI knows its host (overrides any parent
            // CLAUDE_CODE_ENTRYPOINT from an outer Claude Desktop / Claude Code session).
            pb.environment().put("CLAUDE_CODE_ENTRYPOINT", "eclipse-plugin");
            // Remove OAuth token inherited from outer Claude sessions — use API key auth only
            pb.environment().remove("CLAUDE_CODE_OAUTH_TOKEN");

            // Pass API key from secure storage to CLI process environment
            String apiKey = SecureApiKeyStore.getApiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                pb.environment().put("ANTHROPIC_API_KEY", apiKey);
            }

            pb.redirectErrorStream(false); // separate stderr for error logging

            cliProcess = pb.start();

            // Wire up the protocol handler
            protocolHandler = new NdjsonProtocolHandler(
                cliProcess.getInputStream(),
                cliProcess.getOutputStream(),
                cliProcess.getErrorStream()
            );

            // Forward all message listeners to the protocol handler
            for (ICliMessageListener listener : messageListeners) {
                protocolHandler.addListener(listener);
            }

            protocolHandler.startReading();
            startHealthMonitor();

            state = ProcessState.RUNNING;
            fireStateChanged(ProcessState.STARTING, ProcessState.RUNNING);

        } catch (IOException e) {
            state = ProcessState.ERROR;
            fireStateChanged(ProcessState.STARTING, ProcessState.ERROR);
            throw new CliException("Failed to start CLI process: " + e.getMessage(), e);
        }
    }

    /**
     * Send a user message to the running CLI process.
     */
    public void sendMessage(String userMessage) {
        if (state != ProcessState.RUNNING || protocolHandler == null) {
            throw new IllegalStateException("CLI process is not running");
        }
        protocolHandler.writeMessage(userMessage);
    }

    /**
     * Send a raw NDJSON message to the CLI process.
     */
    public void sendRawNdjson(String ndjsonLine) {
        if (state != ProcessState.RUNNING || protocolHandler == null) {
            throw new IllegalStateException("CLI process is not running");
        }
        protocolHandler.writeRawNdjson(ndjsonLine);
    }

    /**
     * Gracefully stop the CLI process.
     * NOTE: This blocks the calling thread for up to 7 seconds while
     * waiting for the process to exit.  For a non-blocking abort that
     * is safe to call from the UI thread, use {@link #interruptCurrentQuery()}.
     */
    public synchronized void stop() {
        if (cliProcess == null || !cliProcess.isAlive()) {
            state = ProcessState.STOPPED;
            return;
        }

        ProcessState oldState = state;
        state = ProcessState.STOPPING;
        fireStateChanged(oldState, ProcessState.STOPPING);

        // Stop health monitor
        if (healthChecker != null) {
            healthChecker.shutdown();
            healthChecker = null;
        }

        // Stop protocol handler immediately so no more messages are dispatched
        if (protocolHandler != null) {
            protocolHandler.stop();
        }

        // Try graceful shutdown first
        cliProcess.destroy();
        try {
            if (!cliProcess.waitFor(5, TimeUnit.SECONDS)) {
                cliProcess.destroyForcibly();
                cliProcess.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            cliProcess.destroyForcibly();
            Thread.currentThread().interrupt();
        }

        state = ProcessState.STOPPED;
        fireStateChanged(ProcessState.STOPPING, ProcessState.STOPPED);
    }

    /**
     * Non-blocking interrupt of the current query.
     * <p>
     * Immediately stops the protocol handler so no further messages reach
     * the UI, then kills the CLI process on a background thread so the
     * SWT UI thread is never blocked.  After the process dies the manager
     * automatically restarts a fresh CLI session.
     * <p>
     * This is the preferred method for the Stop button and /stop command.
     */
    public void interruptCurrentQuery() {
        // 1. Stop the protocol handler RIGHT NOW – this prevents any
        //    buffered stdout data from being dispatched to listeners.
        if (protocolHandler != null) {
            protocolHandler.stop();
        }

        // 2. Kill the process and restart on a background thread so we
        //    never block the SWT UI thread.
        Thread killer = new Thread(() -> {
            try {
                synchronized (this) {
                    if (cliProcess == null || !cliProcess.isAlive()) {
                        state = ProcessState.STOPPED;
                        fireStateChanged(state, ProcessState.STOPPED);
                        return;
                    }

                    ProcessState oldState = state;
                    state = ProcessState.STOPPING;
                    fireStateChanged(oldState, ProcessState.STOPPING);

                    // Stop health monitor
                    if (healthChecker != null) {
                        healthChecker.shutdown();
                        healthChecker = null;
                    }

                    // On Windows, destroyForcibly() (TerminateProcess) is more
                    // reliable than destroy() which only posts WM_CLOSE.
                    cliProcess.destroyForcibly();
                    try {
                        cliProcess.waitFor(3, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }

                    state = ProcessState.STOPPED;
                    fireStateChanged(ProcessState.STOPPING, ProcessState.STOPPED);
                }

                // 3. Auto-restart so the user doesn't have to reconnect manually.
                if (config != null) {
                    start(config);
                }
            } catch (Exception e) {
                Activator.logError("Error during interrupt/restart", e);
            }
        }, "Claude-CLI-Interrupt");
        killer.setDaemon(true);
        killer.start();
    }

    /**
     * Force-kill and restart with same config.
     */
    public synchronized void restart() throws CliException {
        stop();
        if (config != null) {
            start(config);
        }
    }

    /**
     * Get current process state.
     */
    public ProcessState getState() {
        return state;
    }

    /**
     * Check if the process is currently running.
     */
    public boolean isRunning() {
        return state == ProcessState.RUNNING && cliProcess != null && cliProcess.isAlive();
    }

    /**
     * Return the exit code of the most recent CLI process, or -1 if the process
     * has not yet terminated or was never started.
     */
    public int getLastExitCode() {
        if (cliProcess == null) return -1;
        try {
            return cliProcess.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1; // still running
        }
    }

    // ==================== Listener Management ====================

    public void addMessageListener(ICliMessageListener listener) {
        messageListeners.add(listener);
        // If protocol handler is already running, add to it too
        if (protocolHandler != null) {
            protocolHandler.addListener(listener);
        }
    }

    public void removeMessageListener(ICliMessageListener listener) {
        messageListeners.remove(listener);
        if (protocolHandler != null) {
            protocolHandler.removeListener(listener);
        }
    }

    public void addStateListener(ICliStateListener listener) {
        stateListeners.add(listener);
    }

    public void removeStateListener(ICliStateListener listener) {
        stateListeners.remove(listener);
    }

    // ==================== Internal Methods ====================

    private List<String> buildCommand(CliProcessConfig config) {
        List<String> command = new ArrayList<>();
        command.add(config.getCliPath());
        // NOTE: Do NOT use -p (print mode) — that disables multi-turn tool use.
        // Interactive mode with stream-json allows Claude to use tools (Read, Edit, Write, Bash).
        command.add("--output-format");
        command.add("stream-json");
        command.add("--input-format");
        command.add("stream-json");
        command.add("--verbose");
        command.add("--include-partial-messages");

        // Critical: Tell CLI to send permission prompts via stdin/stdout JSON
        // (not via terminal UI). This is how VS Code extension handles permissions.
        command.add("--permission-prompt-tool");
        command.add("stdio");

        if (config.getPermissionMode() != null && !config.getPermissionMode().isEmpty()) {
            command.add("--permission-mode");
            command.add(config.getPermissionMode());
        }
        if (config.getModel() != null && !config.getModel().isEmpty()) {
            command.add("--model");
            command.add(config.getModel());
        }
        if (config.getSessionId() != null && !config.getSessionId().isEmpty()) {
            command.add("--session-id");
            command.add(config.getSessionId());
        }
        if (config.isContinueSession()) {
            command.add("--continue");
        }
        if (config.getResumeSessionId() != null && !config.getResumeSessionId().isEmpty()) {
            command.add("--resume");
            command.add(config.getResumeSessionId());
        }
        if (config.getAllowedTools() != null && config.getAllowedTools().length > 0) {
            command.add("--allowedTools");
            command.add(String.join(",", config.getAllowedTools()));
        }
        if (config.getMaxTurns() > 0) {
            command.add("--max-turns");
            command.add(String.valueOf(config.getMaxTurns()));
        }
        if (config.getAppendSystemPrompt() != null && !config.getAppendSystemPrompt().isEmpty()) {
            command.add("--append-system-prompt");
            command.add(config.getAppendSystemPrompt());
        }
        if (config.getAdditionalDirs() != null) {
            for (String dir : config.getAdditionalDirs()) {
                command.add("--add-dir");
                command.add(dir);
            }
        }

        return command;
    }

    private void startHealthMonitor() {
        if (healthChecker != null) {
            healthChecker.shutdown();
        }
        healthChecker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Claude-CLI-Health");
            t.setDaemon(true);
            return t;
        });
        healthChecker.scheduleAtFixedRate(() -> {
            if (cliProcess != null && !cliProcess.isAlive() && state == ProcessState.RUNNING) {
                ProcessState oldState = state;
                state = ProcessState.ERROR;
                int exitCode = cliProcess.exitValue();
                Activator.logError("[Claude CLI] Process exited unexpectedly with code " + exitCode);
                fireStateChanged(oldState, ProcessState.ERROR);
            }
        }, 2, 2, TimeUnit.SECONDS);
    }

    private void fireStateChanged(ProcessState oldState, ProcessState newState) {
        for (ICliStateListener listener : stateListeners) {
            try {
                listener.onStateChanged(oldState, newState);
            } catch (Exception e) {
                Activator.logError("[Claude CLI] Error in state listener: " + e.getMessage(), e);
            }
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * Get installation instructions for the Claude CLI.
     */
    public static String getInstallInstructions() {
        return "To install Claude Code CLI:\n\n" +
               "  npm install -g @anthropic-ai/claude-code\n\n" +
               "Then authenticate:\n" +
               "  claude login\n\n" +
               "Or set your API key:\n" +
               "  export ANTHROPIC_API_KEY=your-key-here\n\n" +
               "More info: https://docs.anthropic.com/en/docs/claude-code";
    }

    /**
     * Custom exception for CLI errors.
     */
    public static class CliException extends Exception {
        private static final long serialVersionUID = 1L;

        public CliException(String message) {
            super(message);
        }

        public CliException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
