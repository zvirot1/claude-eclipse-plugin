package com.anthropic.eclipse.claude.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.anthropic.eclipse.claude.Activator;

/**
 * Tracks the PIDs of Claude-CLI processes this plug-in has launched so they
 * can be reaped after an Eclipse crash or forced shutdown.
 *
 * <p>Why this is needed: when Eclipse exits cleanly, {@link
 * com.anthropic.eclipse.claude.Activator#stop(org.osgi.framework.BundleContext)}
 * calls {@code stop()} on every registered {@link ClaudeCliManager}, which in
 * turn kills the underlying Node.js process tree. But if Eclipse crashes, is
 * killed via Task Manager, or the user logs off Windows abruptly, the JVM
 * never gets the chance to run {@code stop()} — the CLI node processes are
 * orphaned and keep running in the background. The next time Eclipse starts
 * we see them on the system, and (because of the cost of cold-starting a new
 * CLI) we end up running multiple of them in parallel.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>{@link #registerPid(long)} appends a line to
 *       {@code ~/.claude/.eclipse-cli-pids} as soon as a CLI is spawned.</li>
 *   <li>{@link #unregisterPid(long)} removes the line on normal stop.</li>
 *   <li>{@link #cleanupOrphans()} runs once at plug-in startup: for every PID
 *       still listed, if a process with that PID is alive <em>and</em> its
 *       command line looks like a Claude CLI (node + path containing
 *       {@code claude-code} or executable named {@code claude}), the entire
 *       process tree is killed. The file is then truncated.</li>
 * </ul>
 * <p>Safety: we only kill a PID when the command line confirms it is a Claude
 * CLI process — never on PID alone, because Windows readily reuses PIDs.
 */
public final class ClaudeCliPidTracker {

    private static final Path PID_FILE =
            Paths.get(System.getProperty("user.home"), ".claude", ".eclipse-cli-pids");

    private ClaudeCliPidTracker() {}

    /** Append a PID to the tracker file. Best-effort; failures are logged but not thrown. */
    public static synchronized void registerPid(long pid) {
        try {
            Files.createDirectories(PID_FILE.getParent());
            String line = pid + "\n";
            Files.write(PID_FILE, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Activator.logDiag("[DIAG-PIDTRACK] registered pid=" + pid);
        } catch (IOException e) {
            Activator.logWarning("[PidTracker] register failed for " + pid + ": " + e.getMessage());
        }
    }

    /** Remove a PID from the tracker file. Best-effort. */
    public static synchronized void unregisterPid(long pid) {
        if (!Files.exists(PID_FILE)) return;
        try {
            List<String> existing = Files.readAllLines(PID_FILE, StandardCharsets.UTF_8);
            String pidStr = Long.toString(pid);
            List<String> kept = new ArrayList<>(existing.size());
            boolean removed = false;
            for (String line : existing) {
                if (line.trim().equals(pidStr)) {
                    removed = true;
                    continue;
                }
                kept.add(line);
            }
            if (removed) {
                Files.write(PID_FILE, kept, StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
                Activator.logDiag("[DIAG-PIDTRACK] unregistered pid=" + pid);
            }
        } catch (IOException e) {
            Activator.logWarning("[PidTracker] unregister failed for " + pid + ": " + e.getMessage());
        }
    }

    /**
     * On plug-in start: kill every PID still listed in the tracker file whose
     * process is alive and looks like a Claude CLI. Returns the number of
     * processes killed (for logging / UX).
     */
    public static synchronized int cleanupOrphans() {
        if (!Files.exists(PID_FILE)) return 0;
        Set<Long> pids = new LinkedHashSet<>();
        try {
            for (String line : Files.readAllLines(PID_FILE, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    pids.add(Long.parseLong(trimmed));
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException e) {
            Activator.logWarning("[PidTracker] read failed: " + e.getMessage());
            return 0;
        }
        int killed = 0;
        for (long pid : pids) {
            try {
                Optional<ProcessHandle> handle = ProcessHandle.of(pid);
                if (handle.isEmpty()) continue;
                ProcessHandle ph = handle.get();
                if (!ph.isAlive()) continue;
                if (!looksLikeClaudeCli(ph)) {
                    Activator.logDiag("[DIAG-PIDTRACK] skipping pid=" + pid
                            + " (not Claude CLI: " + ph.info().command().orElse("?") + ")");
                    continue;
                }
                // Kill children first, then the root.
                ph.descendants().forEach(child -> {
                    try { child.destroyForcibly(); } catch (Exception ignored) {}
                });
                ph.destroyForcibly();
                Activator.logInfo("[PidTracker] killed orphaned Claude CLI pid=" + pid);
                killed++;
            } catch (Throwable t) {
                Activator.logWarning("[PidTracker] kill failed for pid=" + pid + ": " + t.getMessage());
            }
        }
        // Reset file regardless — every PID is either dead, killed, or
        // confirmed not ours; no point keeping the entries around.
        try {
            Files.write(PID_FILE, new byte[0],
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
        } catch (IOException e) {
            Activator.logWarning("[PidTracker] truncate failed: " + e.getMessage());
        }
        return killed;
    }

    /**
     * A process is considered a Claude CLI if its command path contains
     * {@code claude-code} OR its executable name is exactly {@code claude}
     * or {@code claude.exe} or {@code claude.cmd}. This is conservative:
     * we never kill a process whose command we cannot read.
     */
    private static boolean looksLikeClaudeCli(ProcessHandle ph) {
        Optional<String> cmd = ph.info().command();
        if (cmd.isEmpty()) return false;
        String c = cmd.get().toLowerCase();
        if (c.contains("claude-code")) return true;
        if (c.endsWith("\\claude") || c.endsWith("/claude")) return true;
        if (c.endsWith("\\claude.exe") || c.endsWith("/claude.exe")) return true;
        if (c.endsWith("\\claude.cmd") || c.endsWith("/claude.cmd")) return true;
        // Node processes with claude-code in their full command-line — fall back to arguments.
        Optional<String> cmdLine = ph.info().commandLine();
        if (cmdLine.isPresent() && cmdLine.get().toLowerCase().contains("claude-code")) {
            return true;
        }
        return false;
    }
}
