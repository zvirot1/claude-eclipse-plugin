package com.anthropic.eclipse.claude.cli;

import java.io.IOException;

/**
 * Listener for NDJSON messages received from the Claude CLI process.
 */
public interface ICliMessageListener {

    /**
     * Called when a complete NDJSON message is parsed from CLI stdout.
     * @param message The parsed message (SystemInit, AssistantMessage, StreamEvent, etc.)
     */
    void onMessage(CliMessage message);

    /**
     * Called when a line from stdout could not be parsed as valid NDJSON.
     * @param rawLine The raw line that failed to parse
     * @param error The parse exception
     */
    default void onParseError(String rawLine, Exception error) {
        // Default: ignore parse errors
    }

    /**
     * Called when the connection to the CLI process is lost.
     * @param error The IOException that caused the disconnection
     */
    default void onConnectionError(IOException error) {
        // Default: ignore connection errors
    }
}
