package com.anthropic.eclipse.claude.model;

/**
 * Listener for conversation model changes.
 * All callbacks may be called from a background thread -
 * UI implementations must use Display.asyncExec() for SWT operations.
 */
public interface IConversationListener {

    /**
     * Called when the CLI session is initialized (system init message received).
     */
    default void onSessionInitialized(SessionInfo info) {}

    /**
     * Called once at the start of {@code loadHistory} when the chat view is
     * about to receive FEWER bubble events than the session actually has —
     * the oldest blocks are being skipped to stay under the SWT/Win32 32767px
     * child-coordinate limit. The view should render a banner at the top.
     */
    default void onHistoryTruncated(int total, int displayed, int hidden) {}

    /**
     * Called when a user message is added to the conversation.
     */
    default void onUserMessageAdded(MessageBlock block) {}

    /**
     * Called when a new assistant message starts streaming.
     */
    default void onAssistantMessageStarted(MessageBlock block) {}

    /**
     * Called when streaming text is appended to the current assistant message.
     * @param block The message block being updated
     * @param delta The new text delta appended
     */
    default void onStreamingTextAppended(MessageBlock block, String delta) {}

    /**
     * Called when a tool call starts within the current assistant message.
     */
    default void onToolCallStarted(MessageBlock block, MessageBlock.ToolCallSegment toolCall) {}

    /**
     * Called when a tool call's input is being streamed (partial JSON).
     */
    default void onToolCallInputDelta(MessageBlock block, MessageBlock.ToolCallSegment toolCall, String delta) {}

    /**
     * Called when a tool call's input streaming is fully complete (content_block_stop),
     * BEFORE the tool actually executes. The full tool input is available here.
     * This is the right place to snapshot files for revert/diff.
     */
    default void onToolCallInputComplete(MessageBlock block, MessageBlock.ToolCallSegment toolCall) {}

    /**
     * Called when a tool call completes (result received).
     */
    default void onToolCallCompleted(MessageBlock block, MessageBlock.ToolCallSegment toolCall) {}

    /**
     * Called when the current assistant message is complete.
     */
    default void onAssistantMessageCompleted(MessageBlock block) {}

    /**
     * Called when a query result is received (cost, usage, duration).
     */
    default void onResultReceived(UsageInfo usage) {}

    /**
     * Called when the CLI requests permission to execute a tool.
     * The UI should show a permission banner and respond via the CLI manager.
     * @param toolUseId  tool_use_id for old-format responses (may be null for control_request)
     * @param toolName   The tool name (e.g., "Write", "Edit", "Bash")
     * @param description Description of what the tool wants to do
     * @param requestId  request_id for new control_request format (null for old format)
     * @param toolInput  the original tool input (Map) — echoed back in control_response as updatedInput
     */
    default void onPermissionRequested(String toolUseId, String toolName, String description,
                                       String requestId, Object toolInput) {}

    /**
     * Called when an extended-thinking block starts streaming.
     * The assistant is performing internal reasoning before generating its text response.
     * The UI should update the "thinking" indicator to convey this state.
     */
    default void onExtendedThinkingStarted() {}

    /**
     * Called when the extended-thinking phase ends and the assistant begins generating
     * the visible text response. Fired just before onAssistantMessageStarted().
     */
    default void onExtendedThinkingEnded() {}

    /**
     * Called when an assistant message is removed (e.g. replaced by stream events).
     */
    default void onAssistantMessageRemoved(MessageBlock block) {}

    /**
     * Called when an error occurs.
     */
    default void onError(String error) {}

    /**
     * Called when the model detects a silent-empty result (CLI returned with
     * 0 tokens and no streaming events) on the FIRST attempt of a turn. The
     * UI should re-send {@code lastUserPrompt} once — corporate hooks like
     * AIM are non-deterministic and a retry frequently succeeds. If the retry
     * also yields a silent-empty result, the model will fire {@link #onError}
     * with a block-explanation message instead.
     */
    default void onSilentEmptyShouldRetry(String lastUserPrompt) {}

    /**
     * Called when the conversation is cleared.
     */
    default void onConversationCleared() {}

    /**
     * Called for EVERY stream event the model receives — fired before any
     * type-specific handler. Used by the view as the canonical "stream is
     * alive" signal so the streaming-timeout check doesn't false-fire during
     * a long extended-thinking phase or a tool-input being built (both of
     * those produce content_block_delta events that don't carry visible text
     * and therefore never reach onStreamingTextAppended).
     *
     * @param deltaType  for content_block_delta: one of "text_delta",
     *                   "input_json_delta", "thinking_delta", "signature_delta", null;
     *                   for other events ("message_start", "content_block_start",
     *                   "content_block_stop", "message_delta", "message_stop"): null
     * @param toolName   when a tool_use content block is being built, the
     *                   tool's name (e.g. "Write", "Bash"); null otherwise
     */
    default void onStreamEvent(String eventType, String deltaType, String toolName) {}
}
