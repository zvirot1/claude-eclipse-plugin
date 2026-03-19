package com.anthropic.eclipse.claude.diff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.anthropic.eclipse.claude.Activator;

/**
 * Snapshots file contents before Claude CLI write/edit operations so that
 * the user can revert to the original if needed.
 *
 * One snapshot per file path is kept per session. Subsequent tool calls on the
 * same file do not overwrite the original snapshot (preserving the true
 * pre-Claude baseline).
 */
public class CheckpointManager {

    // file path → original content before Claude touched it
    private final Map<String, String> snapshots = new LinkedHashMap<>();

    /**
     * Snapshot the current content of a file if it has not been snapshotted
     * already in this session.
     *
     * @param filePath absolute path to the file
     */
    public void snapshot(String filePath) {
        if (filePath == null || filePath.isBlank()) return;
        if (snapshots.containsKey(filePath)) return; // keep original baseline

        try {
            Path path = Paths.get(filePath);
            if (Files.exists(path) && Files.isRegularFile(path)) {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                snapshots.put(filePath, content);
                Activator.logInfo("[CheckpointManager] Snapshotted " + filePath);
            }
        } catch (IOException e) {
            Activator.logError("[CheckpointManager] Failed to snapshot " + filePath, e);
        }
    }

    /**
     * @return true if a snapshot exists for the given path
     */
    public boolean canRevert(String filePath) {
        return snapshots.containsKey(filePath);
    }

    /**
     * Restore the snapshotted content of a file.
     *
     * @param filePath absolute path to the file
     */
    public void revert(String filePath) {
        String original = snapshots.get(filePath);
        if (original == null) return;
        try {
            Files.writeString(Paths.get(filePath), original, StandardCharsets.UTF_8);
            Activator.logInfo("[CheckpointManager] Reverted " + filePath);
        } catch (IOException e) {
            Activator.logError("[CheckpointManager] Failed to revert " + filePath, e);
        }
    }

    /**
     * @return an unmodifiable view of all snapshotted file paths
     */
    public Map<String, String> getSnapshots() {
        return Collections.unmodifiableMap(snapshots);
    }

    /**
     * Discard all snapshots (call when a new session starts).
     */
    public void clearCheckpoints() {
        snapshots.clear();
    }
}
