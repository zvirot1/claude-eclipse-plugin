package com.anthropic.eclipse.claude.cli;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Manages integration with the Claude Code CLI.
 * Detects installation, manages sessions, and streams output.
 */
public class ClaudeCodeCLI {

    public enum CLIStatus {
        NOT_INSTALLED,
        FOUND,
        RUNNING
    }

    private Process currentProcess;
    private CLIStatus status = CLIStatus.NOT_INSTALLED;
    private String cliPath;
    private File workingDirectory;

    // Known install locations
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

    public ClaudeCodeCLI() {
        detectCLI();
    }

    /**
     * Auto-detect Claude Code CLI installation.
     */
    public final boolean detectCLI() {
        // First try PATH
        String fromPath = findInPath("claude");
        if (fromPath != null) {
            cliPath = fromPath;
            status = CLIStatus.FOUND;
            return true;
        }

        // Try known locations
        for (String path : SEARCH_PATHS) {
            File f = new File(path);
            if (f.exists() && f.canExecute()) {
                cliPath = path;
                status = CLIStatus.FOUND;
                return true;
            }
        }

        status = CLIStatus.NOT_INSTALLED;
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
        if (status == CLIStatus.NOT_INSTALLED) return "Not installed";
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
     * Run a Claude Code CLI command with streaming output.
     * 
     * @param prompt       The prompt/instruction
     * @param workDir      Working directory (project root)
     * @param onOutput     Callback for each output line
     * @param onComplete   Callback when done (receives full output)
     * @param onError      Callback on error
     */
    public void runAsync(String prompt, File workDir, 
                         Consumer<String> onOutput,
                         Consumer<String> onComplete,
                         Consumer<String> onError) {

        if (status == CLIStatus.NOT_INSTALLED) {
            onError.accept("Claude Code CLI not found. Install with: npm install -g @anthropic-ai/claude-code");
            return;
        }

        this.workingDirectory = workDir;

        Thread thread = new Thread(() -> {
            StringBuilder fullOutput = new StringBuilder();
            try {
                List<String> command = new ArrayList<>();
                command.add(cliPath);
                command.add("--print");           // Non-interactive mode
                command.add("--output-format");
                command.add("text");
                command.add(prompt);

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(workDir);
                pb.redirectErrorStream(true);
                pb.environment().put("FORCE_COLOR", "0");  // No ANSI colors

                currentProcess = pb.start();
                status = CLIStatus.RUNNING;

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(currentProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        final String finalLine = line;
                        fullOutput.append(line).append("\n");
                        onOutput.accept(finalLine);
                    }
                }

                currentProcess.waitFor(300, TimeUnit.SECONDS);
                status = CLIStatus.FOUND;
                onComplete.accept(fullOutput.toString());

            } catch (InterruptedException e) {
                status = CLIStatus.FOUND;
                onError.accept("Process interrupted.");
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                status = CLIStatus.FOUND;
                onError.accept("CLI Error: " + e.getMessage());
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Run Claude Code on a specific file with a task.
     */
    public void analyzeFile(Path filePath, String task,
                            Consumer<String> onOutput,
                            Consumer<String> onComplete,
                            Consumer<String> onError) {

        String prompt = task + "\n\nFile: " + filePath.getFileName();
        File workDir = filePath.getParent().toFile();
        runAsync(prompt, workDir, onOutput, onComplete, onError);
    }

    /**
     * Run Claude Code to find all usages of a COBOL field across the codebase.
     */
    public void findFieldUsages(String fieldName, File projectRoot,
                                Consumer<String> onOutput,
                                Consumer<String> onComplete,
                                Consumer<String> onError) {

        String prompt = "Search all COBOL files in this directory for any usage of the field '" + fieldName + "'. " +
                        "List every file and line number where this field is read, written, or referenced. " +
                        "Also list any fields that are MOVE'd to/from this field.";

        runAsync(prompt, projectRoot, onOutput, onComplete, onError);
    }

    /**
     * Stop any running process.
     */
    public void stop() {
        if (currentProcess != null && currentProcess.isAlive()) {
            currentProcess.destroyForcibly();
            status = CLIStatus.FOUND;
        }
    }

    public CLIStatus getStatus() { return status; }
    public String getCLIPath() { return cliPath; }
    public void setCLIPath(String path) {
        this.cliPath = path;
        this.status = (path != null && new File(path).exists()) ? CLIStatus.FOUND : CLIStatus.NOT_INSTALLED;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static String getInstallInstructions() {
        return "To install Claude Code CLI:\n\n" +
               "  npm install -g @anthropic-ai/claude-code\n\n" +
               "Then authenticate:\n" +
               "  claude login\n\n" +
               "Or set your API key:\n" +
               "  export ANTHROPIC_API_KEY=your-key-here\n\n" +
               "More info: https://docs.anthropic.com/en/docs/claude-code";
    }
}
