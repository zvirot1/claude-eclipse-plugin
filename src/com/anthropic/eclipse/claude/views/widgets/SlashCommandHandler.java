package com.anthropic.eclipse.claude.views.widgets;

import java.util.*;

/**
 * Handles slash commands typed in the conversation input.
 * Some commands are handled locally, others are forwarded to the CLI.
 */
public class SlashCommandHandler {

    /**
     * Info about a slash command.
     */
    public static class CommandInfo {
        public final String name;
        public final String description;
        public final boolean localOnly; // true = handled by plugin, false = forwarded to CLI

        public CommandInfo(String name, String description, boolean localOnly) {
            this.name = name;
            this.description = description;
            this.localOnly = localOnly;
        }
    }

    private static final List<CommandInfo> COMMANDS = new ArrayList<>();

    static {
        // Local commands (handled by the plugin)
        COMMANDS.add(new CommandInfo("/new", "Start a new conversation", true));
        COMMANDS.add(new CommandInfo("/clear", "Clear the conversation display", true));
        COMMANDS.add(new CommandInfo("/cost", "Show token usage and cost summary", true));
        COMMANDS.add(new CommandInfo("/help", "Show available commands", true));
        COMMANDS.add(new CommandInfo("/stop", "Stop the current query", true));
        COMMANDS.add(new CommandInfo("/resume", "Resume a previous session", true));
        COMMANDS.add(new CommandInfo("/model", "Show or switch the model", true));
        COMMANDS.add(new CommandInfo("/rules", "Manage Claude rules and permissions", true));
        COMMANDS.add(new CommandInfo("/mcp", "Manage MCP server configurations", true));
        COMMANDS.add(new CommandInfo("/hooks", "Manage hooks (pre/post tool use)", true));
        COMMANDS.add(new CommandInfo("/memory", "Edit project memory and context", true));
        COMMANDS.add(new CommandInfo("/history", "Browse and search session history", true));
        COMMANDS.add(new CommandInfo("/skills", "Manage skills and plugins", true));

        // CLI-forwarded commands (sent to Claude as regular messages)
        COMMANDS.add(new CommandInfo("/compact", "Compact the conversation context (CLI built-in)", false));
        COMMANDS.add(new CommandInfo("/commit", "Generate a git commit message", false));
        COMMANDS.add(new CommandInfo("/review-pr", "Review a pull request", false));
        COMMANDS.add(new CommandInfo("/explain", "Explain the current file or selection", false));
        COMMANDS.add(new CommandInfo("/fix", "Fix bugs in the current file", false));
        COMMANDS.add(new CommandInfo("/test", "Generate tests for the current code", false));
        COMMANDS.add(new CommandInfo("/refactor", "Refactor the current code", false));
    }

    /**
     * Check if a command is a local command (handled by the plugin).
     */
    public static boolean isLocalCommand(String command) {
        String cmd = command.split("\\s+")[0].toLowerCase();
        for (CommandInfo info : COMMANDS) {
            if (info.name.equals(cmd) && info.localOnly) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get all available commands.
     */
    public static List<CommandInfo> getAllCommands() {
        return Collections.unmodifiableList(COMMANDS);
    }

    /**
     * Get auto-complete suggestions for a prefix.
     */
    public static List<String> getSuggestions(String prefix) {
        List<String> suggestions = new ArrayList<>();
        String lowerPrefix = prefix.toLowerCase();
        for (CommandInfo cmd : COMMANDS) {
            if (cmd.name.startsWith(lowerPrefix)) {
                suggestions.add(cmd.name + " - " + cmd.description);
            }
        }
        return suggestions;
    }

    /**
     * Format a help message with all available commands.
     */
    public static String formatHelp() {
        StringBuilder sb = new StringBuilder();
        sb.append("Available Commands:\n\n");
        sb.append("Local Commands (handled by plugin):\n");
        for (CommandInfo cmd : COMMANDS) {
            if (cmd.localOnly) {
                sb.append(String.format("  %-12s %s\n", cmd.name, cmd.description));
            }
        }
        sb.append("\nClaude Commands (forwarded to CLI):\n");
        for (CommandInfo cmd : COMMANDS) {
            if (!cmd.localOnly) {
                sb.append(String.format("  %-12s %s\n", cmd.name, cmd.description));
            }
        }
        return sb.toString();
    }
}
