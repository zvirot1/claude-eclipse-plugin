package com.anthropic.eclipse.claude.cli;

/**
 * Immutable configuration for launching a Claude CLI process.
 * Use the Builder pattern to construct instances.
 */
public class CliProcessConfig {

    private final String cliPath;
    private final String workingDirectory;
    private final String permissionMode;      // "default", "acceptEdits", "bypassPermissions", "plan"
    private final String model;               // "sonnet", "opus", or full model name; null for default
    private final String sessionId;           // null for new session
    private final boolean continueSession;    // --continue flag
    private final String resumeSessionId;     // --resume <id>
    private final String[] allowedTools;      // --allowedTools
    private final String appendSystemPrompt;  // --append-system-prompt
    private final int maxTurns;               // --max-turns, 0 for unlimited
    private final String[] additionalDirs;    // --add-dir
    private final String effortLevel;         // --effort: "low", "medium", "high", "max", or null for Auto

    private CliProcessConfig(Builder builder) {
        this.cliPath = builder.cliPath;
        this.workingDirectory = builder.workingDirectory;
        this.permissionMode = builder.permissionMode;
        this.model = builder.model;
        this.sessionId = builder.sessionId;
        this.continueSession = builder.continueSession;
        this.resumeSessionId = builder.resumeSessionId;
        this.allowedTools = builder.allowedTools;
        this.appendSystemPrompt = builder.appendSystemPrompt;
        this.maxTurns = builder.maxTurns;
        this.additionalDirs = builder.additionalDirs;
        this.effortLevel = builder.effortLevel;
    }

    public String getCliPath() { return cliPath; }
    public String getWorkingDirectory() { return workingDirectory; }
    public String getPermissionMode() { return permissionMode; }
    public String getModel() { return model; }
    public String getSessionId() { return sessionId; }
    public boolean isContinueSession() { return continueSession; }
    public String getResumeSessionId() { return resumeSessionId; }
    public String[] getAllowedTools() { return allowedTools; }
    public String getAppendSystemPrompt() { return appendSystemPrompt; }
    public int getMaxTurns() { return maxTurns; }
    public String[] getAdditionalDirs() { return additionalDirs; }
    public String getEffortLevel() { return effortLevel; }

    /**
     * Create a new config that resumes the given session ID, preserving all
     * other settings (model, permission mode, max turns, etc.) from this config.
     * Used when restarting after an interrupt to maintain conversation memory.
     */
    public CliProcessConfig withResume(String resumeId) {
        return toBuilder().resumeSessionId(resumeId).build();
    }

    /**
     * Create a new config with a different permission mode (and optionally
     * effort level), preserving every other setting and adding --resume so
     * the session memory is carried over. Used for hot-swapping the mode
     * without losing conversation state.
     *
     * @param newMode         new permission mode (CLI value) — null leaves unchanged
     * @param newEffort       new effort level (low/medium/high/max) — null = Auto (no flag)
     * @param resumeSessionId session ID to resume — non-null to preserve memory
     */
    public CliProcessConfig withModeAndEffort(String newMode, String newEffort,
                                              String resumeSessionId) {
        Builder b = toBuilder().effort(newEffort);
        if (newMode != null) b.permissionMode(newMode);
        if (resumeSessionId != null && !resumeSessionId.isEmpty()) {
            b.resumeSessionId(resumeSessionId);
        }
        return b.build();
    }

    /** Copy this config into a fresh Builder. */
    public Builder toBuilder() {
        Builder b = new Builder(cliPath, workingDirectory)
            .permissionMode(permissionMode)
            .model(model)
            .maxTurns(maxTurns)
            .effort(effortLevel);
        if (appendSystemPrompt != null) b.appendSystemPrompt(appendSystemPrompt);
        if (allowedTools != null) b.allowedTools(allowedTools);
        if (additionalDirs != null) b.additionalDirs(additionalDirs);
        if (sessionId != null) b.sessionId(sessionId);
        if (resumeSessionId != null) b.resumeSessionId(resumeSessionId);
        if (continueSession) b.continueSession(true);
        return b;
    }

    /**
     * Builder for CliProcessConfig.
     */
    public static class Builder {
        private String cliPath;
        private String workingDirectory;
        private String permissionMode;
        private String model;
        private String sessionId;
        private boolean continueSession;
        private String resumeSessionId;
        private String[] allowedTools;
        private String appendSystemPrompt;
        private int maxTurns;
        private String[] additionalDirs;
        private String effortLevel;

        public Builder(String cliPath, String workingDirectory) {
            this.cliPath = cliPath;
            this.workingDirectory = workingDirectory;
        }

        public Builder permissionMode(String permissionMode) {
            this.permissionMode = permissionMode;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder continueSession(boolean continueSession) {
            this.continueSession = continueSession;
            return this;
        }

        public Builder resumeSessionId(String resumeSessionId) {
            this.resumeSessionId = resumeSessionId;
            return this;
        }

        public Builder allowedTools(String... allowedTools) {
            this.allowedTools = allowedTools;
            return this;
        }

        public Builder appendSystemPrompt(String appendSystemPrompt) {
            this.appendSystemPrompt = appendSystemPrompt;
            return this;
        }

        public Builder maxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        public Builder additionalDirs(String... additionalDirs) {
            this.additionalDirs = additionalDirs;
            return this;
        }

        /**
         * Effort level for the session. Accepted values: {@code null} (Auto,
         * no flag passed), {@code "low"}, {@code "medium"}, {@code "high"},
         * {@code "max"}. Any other value throws {@link IllegalArgumentException}.
         */
        public Builder effort(String effortLevel) {
            if (effortLevel != null && !effortLevel.isEmpty()) {
                switch (effortLevel) {
                    case "low": case "medium": case "high": case "max":
                        break;
                    default:
                        throw new IllegalArgumentException(
                            "effort must be one of low/medium/high/max, got: " + effortLevel);
                }
            }
            this.effortLevel = (effortLevel == null || effortLevel.isEmpty()) ? null : effortLevel;
            return this;
        }

        public CliProcessConfig build() {
            if (cliPath == null || cliPath.isEmpty()) {
                throw new IllegalArgumentException("CLI path is required");
            }
            if (workingDirectory == null || workingDirectory.isEmpty()) {
                throw new IllegalArgumentException("Working directory is required");
            }
            return new CliProcessConfig(this);
        }
    }
}
