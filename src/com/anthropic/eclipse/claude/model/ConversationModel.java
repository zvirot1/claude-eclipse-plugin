package com.anthropic.eclipse.claude.model;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.anthropic.eclipse.claude.Activator;
import com.anthropic.eclipse.claude.cli.CliMessage;
import com.anthropic.eclipse.claude.cli.ICliMessageListener;

/**
 * Central conversation model that bridges the CLI protocol handler and the UI.
 * Implements ICliMessageListener to receive messages from the CLI process,
 * and notifies IConversationListeners (typically the UI view) of changes.
 *
 * Maintains the complete conversation state in memory including all messages,
 * tool calls, and usage information.
 *
 * Thread safety: onMessage() is called from the NDJSON reader thread while
 * getMessages(), isStreaming(), etc. may be called from the UI thread.
 * All access to mutable state is synchronized on {@code this}.
 */
public class ConversationModel implements ICliMessageListener {

    private final List<MessageBlock> messages = new ArrayList<>();
    private final List<IConversationListener> listeners = new CopyOnWriteArrayList<>();
    private volatile SessionInfo sessionInfo;
    private final UsageInfo cumulativeUsage = new UsageInfo();

    // Streaming state
    private volatile MessageBlock currentStreamingBlock;
    private final Map<Integer, MessageBlock.ToolCallSegment> activeToolCalls = new ConcurrentHashMap<>();
    /**
     * True while stream events (message_start, content_block_start/delta/stop, message_stop)
     * are driving the current assistant response. Reset to false by handleResult().
     *
     * When true, all "assistant" NDJSON messages are treated as redundant snapshots sent by
     * --include-partial-messages and are ignored. The stream events are authoritative.
     */
    private volatile boolean usingStreamEvents = false;

    /**
     * True if the current turn produced any visible text (assistant text segments
     * via stream events). Reset on each new user input. Used by handleResult to
     * detect "empty result" cases (CLI returned without producing any output —
     * e.g. AWS SSO token expired in a SessionStart hook).
     */
    private volatile boolean hadTextInCurrentTurn = false;

    /**
     * Last hook-related notification with an error indicator (e.g. "Token has
     * expired"). Cleared on each new user input. Surfaced via onError() if the
     * subsequent result is empty.
     */
    private volatile CliMessage.SystemNotification lastErrorNotification;

    // ==================== ICliMessageListener Implementation ====================

    @Override
    public void onMessage(CliMessage message) {
        String type = message != null ? message.getClass().getSimpleName() : "null";
        String extra = "";
        if (message instanceof CliMessage.StreamEvent) {
            extra = " eventType=" + ((CliMessage.StreamEvent) message).getEventType()
                  + " idx=" + ((CliMessage.StreamEvent) message).getIndex();
        } else if (message instanceof CliMessage.ResultMessage) {
            CliMessage.ResultMessage r = (CliMessage.ResultMessage) message;
            extra = " subtype=" + r.getSubtype() + " isError=" + r.isError();
        }
        Activator.logDiag("[DIAG-MSG] model=" + System.identityHashCode(this)
                + " recv=" + type + extra);
        if (message instanceof CliMessage.SystemInit) {
            handleSystemInit((CliMessage.SystemInit) message);
        } else if (message instanceof CliMessage.SystemNotification) {
            handleSystemNotification((CliMessage.SystemNotification) message);
        } else if (message instanceof CliMessage.AssistantMessage) {
            handleAssistantMessage((CliMessage.AssistantMessage) message);
        } else if (message instanceof CliMessage.UserMessage) {
            handleUserMessage((CliMessage.UserMessage) message);
        } else if (message instanceof CliMessage.StreamEvent) {
            handleStreamEvent((CliMessage.StreamEvent) message);
        } else if (message instanceof CliMessage.ResultMessage) {
            handleResult((CliMessage.ResultMessage) message);
        } else if (message instanceof CliMessage.PermissionRequest) {
            handlePermissionRequest((CliMessage.PermissionRequest) message);
        } else if (message instanceof CliMessage.RateLimitEvent) {
            handleRateLimitEvent((CliMessage.RateLimitEvent) message);
        }
    }

    @Override
    public void onParseError(String rawLine, Exception error) {
        Activator.logError("[ConversationModel] Parse error: " + error.getMessage() + " - Line: " + rawLine, error);
    }

    @Override
    public void onConnectionError(IOException error) {
        markActiveToolCallsFailed("Connection lost");
        fireError("Connection to Claude CLI lost: " + error.getMessage());
    }

    /**
     * Mark all currently-RUNNING tool calls as FAILED.
     * Called when the CLI stream closes unexpectedly (connection error, process crash, timeout).
     * This prevents tool call widgets from staying in "Running..." forever.
     */
    public void markActiveToolCallsFailed(String reason) {
        for (Map.Entry<Integer, MessageBlock.ToolCallSegment> entry : activeToolCalls.entrySet()) {
            MessageBlock.ToolCallSegment seg = entry.getValue();
            if (seg.getStatus() == MessageBlock.ToolStatus.RUNNING) {
                seg.setStatus(MessageBlock.ToolStatus.FAILED);
                seg.setOutput("⚠ " + reason);
                // Find the parent block and fire toolCallCompleted so UI updates
                List<MessageBlock> snapshot;
                synchronized (messages) { snapshot = new ArrayList<>(messages); }
                for (MessageBlock block : snapshot) {
                    if (block.findToolCall(seg.getToolId()) != null) {
                        fireToolCallCompleted(block, seg);
                        break;
                    }
                }
            }
        }
        activeToolCalls.clear();
    }

    /**
     * Returns true if any tool call is currently in RUNNING state.
     * Used by the streaming timeout check to avoid false timeouts during
     * long-running tool executions (e.g. a slow Maven build).
     */
    public boolean hasRunningToolCalls() {
        for (MessageBlock.ToolCallSegment seg : activeToolCalls.values()) {
            if (seg.getStatus() == MessageBlock.ToolStatus.RUNNING) return true;
        }
        return false;
    }

    // ==================== Public API ====================

    /**
     * Add a user message to the conversation (before sending to CLI).
     */
    public void addUserMessage(String content) {
        // Reset per-turn diagnostic state so handleResult can detect empty turns.
        hadTextInCurrentTurn = false;
        lastErrorNotification = null;

        MessageBlock block = new MessageBlock(MessageBlock.Role.USER);
        MessageBlock.TextSegment textSeg = new MessageBlock.TextSegment();
        textSeg.appendText(content);
        block.addSegment(textSeg);
        synchronized (messages) {
            messages.add(block);
        }
        fireUserMessageAdded(block);
    }

    /**
     * Get all messages in the conversation (snapshot copy for thread safety).
     */
    public List<MessageBlock> getMessages() {
        synchronized (messages) {
            return Collections.unmodifiableList(new ArrayList<>(messages));
        }
    }

    /**
     * Get the current session info.
     */
    public SessionInfo getSessionInfo() {
        return sessionInfo;
    }

    /**
     * Get cumulative usage information.
     */
    public UsageInfo getCumulativeUsage() {
        return cumulativeUsage;
    }

    /**
     * Get the currently streaming assistant block (may have partial content).
     */
    public MessageBlock getCurrentStreamingBlock() {
        return currentStreamingBlock;
    }

    /**
     * Check if the model is currently streaming a response.
     */
    public boolean isStreaming() {
        return currentStreamingBlock != null;
    }

    /**
     * Clear the conversation.
     */
    public void clear() {
        synchronized (messages) {
            messages.clear();
        }
        currentStreamingBlock = null;
        usingStreamEvents = false;
        activeToolCalls.clear();
        cumulativeUsage.reset();
        fireConversationCleared();
    }

    /**
     * Load historical messages into the model and replay them as UI events.
     * Used when resuming a session to pre-populate the chat with past messages.
     * Fires onUserMessageAdded / onAssistantMessageStarted+Completed for each block
     * so the view builds corresponding widgets.
     *
     * Must be called on a fresh (empty) model — does NOT clear existing messages first.
     */
    public void loadHistory(List<MessageBlock> historicalBlocks) {
        synchronized (messages) {
            messages.addAll(historicalBlocks);
        }
        for (MessageBlock block : historicalBlocks) {
            if (block.getRole() == MessageBlock.Role.USER) {
                fireUserMessageAdded(block);
            } else if (block.getRole() == MessageBlock.Role.ASSISTANT) {
                fireAssistantMessageStarted(block);
                fireAssistantMessageCompleted(block);
            }
        }
    }

    /**
     * Get message count.
     */
    public int getMessageCount() {
        synchronized (messages) {
            return messages.size();
        }
    }

    // ==================== Listener Management ====================

    public void addListener(IConversationListener listener) {
        listeners.add(listener);
    }

    public void removeListener(IConversationListener listener) {
        listeners.remove(listener);
    }

    // ==================== Message Handlers ====================

    private void handleSystemInit(CliMessage.SystemInit init) {
        sessionInfo = new SessionInfo(init.getSessionId());
        sessionInfo.setModel(init.getModel());
        sessionInfo.setWorkingDirectory(init.getCwd());
        sessionInfo.setPermissionMode(init.getPermissionMode());
        fireSessionInitialized(sessionInfo);
    }

    private void handleAssistantMessage(CliMessage.AssistantMessage msg) {
        String preview = "";
        try {
            String t = extractTextFromContent(msg.getContent());
            if (t != null) preview = t.substring(0, Math.min(40, t.length())).replace("\n", "\\n");
        } catch (Throwable ignored) {}
        Activator.logDiag("[DIAG] handleAssistantMessage: usingStreamEvents=" + usingStreamEvents
                + " currentStreamingBlock=" + (currentStreamingBlock != null)
                + " preview='" + preview + "'");
        // When stream events are active, ignore ALL assistant messages. The stream events
        // (message_start/content_block_*/message_stop) are authoritative and already drive the UI.
        if (usingStreamEvents) {
            Activator.logDiag("[DIAG] handleAssistantMessage SKIPPED: usingStreamEvents=true");
            return;
        }
        // Also skip if there's an active streaming block
        if (currentStreamingBlock != null) {
            Activator.logDiag("[DIAG] handleAssistantMessage SKIPPED: currentStreamingBlock != null");
            return;
        }
        // Also ignore if the last message is already ASSISTANT (complete or still streaming).
        // The CLI sends redundant assistant snapshots mid-stream and after result.
        // Additionally, check content-based dedup: on some backends (e.g. Bedrock),
        // the CLI may send the same content via both 'assistant' and stream events,
        // or send multiple 'assistant' snapshots with identical text.
        synchronized (messages) {
            if (!messages.isEmpty()) {
                MessageBlock lastMsg = messages.get(messages.size() - 1);
                if (lastMsg.getRole() == MessageBlock.Role.ASSISTANT) {
                    Activator.logDiag("[DIAG] handleAssistantMessage SKIPPED: last msg is ASSISTANT");
                    return; // Already have an assistant message — skip duplicate
                }
            }
            // Content-based dedup: if ANY recent assistant message has identical text,
            // skip this one. Catches duplicates regardless of message ordering.
            String incomingText = extractTextFromContent(msg.getContent());
            if (incomingText != null && !incomingText.isEmpty()) {
                for (int i = messages.size() - 1; i >= Math.max(0, messages.size() - 5); i--) {
                    MessageBlock existing = messages.get(i);
                    if (existing.getRole() == MessageBlock.Role.ASSISTANT
                            && incomingText.equals(existing.getFullText())) {
                        Activator.logDiag("[DIAG] handleAssistantMessage SKIPPED: content duplicate");
                        return; // Content duplicate — skip
                    }
                }
            }
        }
        Activator.logDiag("[DIAG] handleAssistantMessage PROCESSING (snapshot mode): creating new block");

        // If we received a full message directly (non-streaming), create a block for it
        if (msg.getContent() != null && !msg.getContent().isEmpty()) {
            MessageBlock block = new MessageBlock(MessageBlock.Role.ASSISTANT);
            for (CliMessage.ContentBlock contentBlock : msg.getContent()) {
                if ("text".equals(contentBlock.getType())) {
                    MessageBlock.TextSegment seg = new MessageBlock.TextSegment();
                    seg.appendText(contentBlock.getText() != null ? contentBlock.getText() : "");
                    block.addSegment(seg);
                } else if ("tool_use".equals(contentBlock.getType())) {
                    MessageBlock.ToolCallSegment seg = new MessageBlock.ToolCallSegment();
                    seg.setToolId(contentBlock.getId());
                    seg.setToolName(contentBlock.getName());
                    seg.setInput(contentBlock.getInputAsString());
                    seg.setStatus(MessageBlock.ToolStatus.COMPLETED);
                    block.addSegment(seg);
                }
            }
            synchronized (messages) {
                messages.add(block);
            }
            fireAssistantMessageStarted(block);
            fireAssistantMessageCompleted(block);
        }
    }

    /**
     * Surface hook-related system notifications to the user when they carry an
     * error indicator. Common case: enterprise SessionStart hooks that talk to
     * AWS SSO and emit "Token has expired" on stderr while still reporting
     * outcome:"success" — without this handler the failure is invisible.
     */
    private void handleSystemNotification(CliMessage.SystemNotification n) {
        Activator.logDiag("[DIAG] SystemNotification subtype=" + n.getSubtype()
                + " hook=" + n.getHookName()
                + " hasError=" + n.hasErrorIndicator());

        // Only act on hook_response (the final outcome of a hook) to avoid spamming.
        if (!"hook_response".equals(n.getSubtype())) {
            return;
        }

        if (n.hasErrorIndicator()) {
            // Cache so handleResult can correlate an empty result with this error.
            lastErrorNotification = n;

            // Build a friendly message
            String hook = n.getHookName() != null ? n.getHookName() : "hook";
            String detail = n.getStderr();
            if (detail == null || detail.isBlank()) detail = n.getStdout();
            if (detail == null) detail = "";
            detail = detail.trim();
            // Trim CR/LF noise but keep readable
            if (detail.length() > 400) detail = detail.substring(0, 400) + " …";

            String hint = "";
            String low = detail.toLowerCase();
            if (low.contains("token has expired") || low.contains("sso")) {
                hint = "\nFix: refresh your AWS SSO token (run `aws sso login`) and reopen this Claude tab.";
            } else if (low.contains("unauthorized") || low.contains("authentication failed")) {
                hint = "\nFix: re-authenticate (check your API key / SSO session) and reopen this Claude tab.";
            }

            fireError("⚠ Hook '" + hook + "' reported an error:\n" + detail + hint);
        }
    }

    private void handleRateLimitEvent(CliMessage.RateLimitEvent event) {
        if (event.isRejected()) {
            fireError("⚠ Rate limit reached — your request may be delayed or rejected. "
                    + "Please wait a moment before sending another message.");
        }
    }

    private void handleUserMessage(CliMessage.UserMessage msg) {
        // Tool results from the CLI - update the corresponding tool call
        if (msg.getContent() != null) {
            for (CliMessage.ContentBlock contentBlock : msg.getContent()) {
                if ("tool_result".equals(contentBlock.getType())) {
                    updateToolCallResult(contentBlock);
                }
            }
        }
    }

    private void handleStreamEvent(CliMessage.StreamEvent event) {
        String eventType = event.getEventType();
        if (eventType == null) return;

        switch (eventType) {
            case "message_start":
                handleMessageStart(event);
                break;
            case "content_block_start":
                handleContentBlockStart(event);
                break;
            case "content_block_delta":
                handleContentBlockDelta(event);
                break;
            case "content_block_stop":
                handleContentBlockStop(event);
                break;
            case "message_delta":
                handleMessageDelta(event);
                break;
            case "message_stop":
                handleMessageStop(event);
                break;
        }
    }

    private void handleMessageStart(CliMessage.StreamEvent event) {
        Activator.logDiag("[DIAG] handleMessageStart: prev usingStreamEvents=" + usingStreamEvents
                + " currentStreamingBlock=" + (currentStreamingBlock != null));
        // Start a new assistant message — but don't fire onAssistantMessageStarted yet.
        // We defer that until the first content block arrives so we never show an empty bubble.
        usingStreamEvents = true; // stream events are now driving this response

        // Guard: if the CLI already sent a full "assistant" message for this same turn
        // (before the stream events arrived), remove it to avoid duplicate bubbles.
        synchronized (messages) {
            if (!messages.isEmpty()) {
                MessageBlock lastMsg = messages.get(messages.size() - 1);
                if (lastMsg.getRole() == MessageBlock.Role.ASSISTANT && lastMsg != currentStreamingBlock) {
                    String prev = lastMsg.getFullText();
                    Activator.logDiag("[DIAG] handleMessageStart REMOVED prev assistant block, len="
                            + (prev != null ? prev.length() : 0));
                    messages.remove(messages.size() - 1);
                    fireAssistantMessageRemoved(lastMsg);
                }
            }
        }

        currentStreamingBlock = new MessageBlock(MessageBlock.Role.ASSISTANT);
        synchronized (messages) {
            messages.add(currentStreamingBlock);
        }
        activeToolCalls.clear();
    }

    private void handleContentBlockStart(CliMessage.StreamEvent event) {
        CliMessage.ContentBlock cb0 = event.getContentBlock();
        Activator.logDiag("[DIAG] handleContentBlockStart: index=" + event.getIndex()
                + " type=" + (cb0 != null ? cb0.getType() : "null")
                + " currentStreamingBlock=" + (currentStreamingBlock != null));
        boolean fireStarted = false;
        if (currentStreamingBlock == null) {
            // Auto-create streaming block if we missed message_start
            usingStreamEvents = true;
            currentStreamingBlock = new MessageBlock(MessageBlock.Role.ASSISTANT);
            synchronized (messages) {
                messages.add(currentStreamingBlock);
            }
            fireStarted = true;
        } else if (currentStreamingBlock.getSegments().isEmpty()) {
            // First content on a block that was pre-created by handleMessageStart
            fireStarted = true;
        }

        CliMessage.ContentBlock contentBlock = event.getContentBlock();
        if (contentBlock != null) {
            if ("thinking".equals(contentBlock.getType())) {
                // Extended thinking block — the model is reasoning internally.
                // Do NOT add a segment or fire onAssistantMessageStarted yet.
                // Just notify the UI so it can update the indicator text.
                fireExtendedThinkingStarted();
            } else if ("text".equals(contentBlock.getType())) {
                // Visible text is starting — the thinking phase (if any) is now over.
                fireExtendedThinkingEnded();
                // Start a new text segment
                currentStreamingBlock.getOrCreateLastTextSegment();
                if (fireStarted) fireAssistantMessageStarted(currentStreamingBlock);
            } else if ("tool_use".equals(contentBlock.getType())) {
                // Start a new tool call segment
                MessageBlock.ToolCallSegment toolSeg = new MessageBlock.ToolCallSegment();
                toolSeg.setToolId(contentBlock.getId());
                toolSeg.setToolName(contentBlock.getName());
                toolSeg.setStatus(MessageBlock.ToolStatus.RUNNING);
                currentStreamingBlock.addSegment(toolSeg);
                activeToolCalls.put(event.getIndex(), toolSeg);
                // Fire started before tool call so the composite exists first
                if (fireStarted) fireAssistantMessageStarted(currentStreamingBlock);
                fireToolCallStarted(currentStreamingBlock, toolSeg);
            }
        } else if (fireStarted) {
            fireAssistantMessageStarted(currentStreamingBlock);
        }
    }

    private void handleContentBlockDelta(CliMessage.StreamEvent event) {
        if (currentStreamingBlock == null) return;

        CliMessage.Delta delta = event.getDelta();
        if (delta == null) return;

        if ("text_delta".equals(delta.getType()) && delta.getText() != null) {
            String dt = delta.getText();
            String snip = dt.substring(0, Math.min(30, dt.length())).replace("\n", "\\n");
            Activator.logDiag("[DIAG] text_delta idx=" + event.getIndex()
                    + " len=" + dt.length() + " snip='" + snip + "'");
            hadTextInCurrentTurn = true; // any text delta proves the model produced output
            // Append text to the current text segment
            MessageBlock.TextSegment textSeg = currentStreamingBlock.getOrCreateLastTextSegment();
            textSeg.appendText(dt);
            fireStreamingTextAppended(currentStreamingBlock, dt);

        } else if ("input_json_delta".equals(delta.getType())) {
            // Append to tool input
            MessageBlock.ToolCallSegment toolSeg = activeToolCalls.get(event.getIndex());
            if (toolSeg != null) {
                String inputDelta = delta.getPartialJson() != null ? delta.getPartialJson() : delta.getText();
                if (inputDelta != null) {
                    toolSeg.appendInput(inputDelta);
                    fireToolCallInputDelta(currentStreamingBlock, toolSeg, inputDelta);
                }
            }
        }
    }

    private void handleContentBlockStop(CliMessage.StreamEvent event) {
        // Content block finalized - if it was a tool call, it's now waiting for result
        MessageBlock.ToolCallSegment toolSeg = activeToolCalls.get(event.getIndex());
        if (toolSeg != null) {
            // Tool call input is complete, now waiting for execution/result
            toolSeg.setStatus(MessageBlock.ToolStatus.RUNNING);
            // Notify listeners: full input is available, BEFORE tool executes.
            // This is the right place to snapshot files for revert/diff.
            fireToolCallInputComplete(currentStreamingBlock, toolSeg);
        }
    }

    private void handleMessageDelta(CliMessage.StreamEvent event) {
        // Message-level update (stop_reason, usage)
        CliMessage.Delta delta = event.getDelta();
        if (delta != null && delta.getStopReason() != null) {
            // The message is finishing with this stop reason
        }
    }

    private void handleMessageStop(CliMessage.StreamEvent event) {
        // Message is complete
        if (currentStreamingBlock != null) {
            // Content-based dedup: if an earlier assistant message has identical text
            // (e.g. from a non-stream 'assistant' event that arrived first), remove
            // the OLD one and keep this streaming version.
            String streamedText = currentStreamingBlock.getFullText();
            if (streamedText != null && !streamedText.isEmpty()) {
                synchronized (messages) {
                    for (int i = messages.size() - 1; i >= 0; i--) {
                        MessageBlock existing = messages.get(i);
                        if (existing == currentStreamingBlock) continue;
                        if (existing.getRole() == MessageBlock.Role.ASSISTANT
                                && streamedText.equals(existing.getFullText())) {
                            messages.remove(i);
                            fireAssistantMessageRemoved(existing);
                            break;
                        }
                    }
                }
            }
            fireAssistantMessageCompleted(currentStreamingBlock);
            currentStreamingBlock = null;
        }
    }

    private void handleResult(CliMessage.ResultMessage result) {
        String resultText = result.getResult();
        String resultPreview = resultText != null
                ? resultText.substring(0, Math.min(120, resultText.length())).replace("\n", "\\n")
                : "null";
        Activator.logDiag("[DIAG] handleResult: subtype=" + result.getSubtype()
                + " isError=" + result.isError()
                + " cost=" + result.getCostUsd()
                + " duration=" + result.getDurationMs() + "ms"
                + " turns=" + result.getNumTurns()
                + " resultLen=" + (resultText != null ? resultText.length() : 0)
                + " resultPreview='" + resultPreview + "'"
                + " sessionId=" + result.getSessionId());
        // NOTE: Do NOT reset usingStreamEvents here. The CLI sends a redundant
        // assistant snapshot AFTER the result message. If we reset the flag,
        // handleAssistantMessage would process that snapshot and duplicate the text.
        // The flag is reset in handleMessageStart when a new streaming turn begins.

        // Detect "silent failure": result arrived but no text was streamed in this
        // turn. Common cause: SessionStart hook (AWS SSO) blocked or auth failed.
        boolean silentEmpty = !hadTextInCurrentTurn
                && (resultText == null || resultText.isEmpty())
                && result.getOutputTokens() == 0;
        if (silentEmpty) {
            String msg;
            if (lastErrorNotification != null) {
                // We already surfaced a richer error from the hook — don't double-report.
                Activator.logDiag("[DIAG] silentEmpty result, but lastErrorNotification already fired");
            } else if (result.isError() || (result.getSubtype() != null && !"success".equals(result.getSubtype()))) {
                msg = "⚠ CLI returned an error: subtype=" + result.getSubtype()
                    + (resultText != null && !resultText.isEmpty() ? "\n" + resultText : "");
                fireError(msg);
            } else {
                msg = "⚠ Empty response from Claude (no text, no tokens used).\n"
                    + "Possible causes: authentication issue (SSO/API key), a hook blocked the prompt, "
                    + "or the CLI failed to reach the API. Check the Error Log for [Claude CLI stderr] entries.";
                fireError(msg);
            }
        }

        // Update usage
        cumulativeUsage.addUsage(
            result.getInputTokens(),
            result.getOutputTokens(),
            result.getCostUsd(),
            result.getDurationMs(),
            result.getNumTurns()
        );

        // Update session info
        if (sessionInfo != null) {
            sessionInfo.setSessionId(result.getSessionId());
            synchronized (messages) {
                sessionInfo.setMessageCount(messages.size());
            }
            sessionInfo.touch();
        }

        // If it was an error result, add an error message
        if (result.isError() && result.getResult() != null) {
            fireError(result.getResult());
        }

        // Finalize any streaming block
        if (currentStreamingBlock != null) {
            fireAssistantMessageCompleted(currentStreamingBlock);
            currentStreamingBlock = null;
        }

        fireResultReceived(cumulativeUsage);
    }

    private void handlePermissionRequest(CliMessage.PermissionRequest request) {
        String toolUseId = request.getToolUseId();
        String toolName = request.getToolName() != null ? request.getToolName() : "Unknown tool";
        String description = request.getDescription();
        if (description == null) {
            description = "Claude wants to use: " + toolName;
        }
        // requestId is non-null for new control_request format, null for old format
        String requestId = request.getRequestId();
        // toolInput is needed to echo back in control_response as updatedInput (allow path)
        Object toolInput = request.getToolInput();
        firePermissionRequested(toolUseId, toolName, description, requestId, toolInput);
    }

    private void updateToolCallResult(CliMessage.ContentBlock toolResult) {
        String toolUseId = toolResult.getToolUseId();
        if (toolUseId == null) return;

        // Find the matching tool call in recent messages (snapshot for safe iteration)
        List<MessageBlock> snapshot;
        synchronized (messages) {
            snapshot = new ArrayList<>(messages);
        }
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            MessageBlock block = snapshot.get(i);
            MessageBlock.ToolCallSegment toolSeg = block.findToolCall(toolUseId);
            if (toolSeg != null) {
                String content = toolResult.getContent();
                toolSeg.setOutput(content);
                // Also treat <tool_use_error> in the content as a failure
                boolean isFailed = toolResult.isError()
                    || (content != null && content.contains("<tool_use_error>"));
                toolSeg.setStatus(isFailed ?
                    MessageBlock.ToolStatus.FAILED : MessageBlock.ToolStatus.COMPLETED);
                fireToolCallCompleted(block, toolSeg);
                break;
            }
        }
    }

    // ==================== Event Firing ====================

    private void fireSessionInitialized(SessionInfo info) {
        for (IConversationListener l : listeners) {
            try { l.onSessionInitialized(info); } catch (Exception e) { logError(e); }
        }
    }

    /** Extract plain text from CLI content blocks for dedup comparison. */
    private String extractTextFromContent(java.util.List<CliMessage.ContentBlock> content) {
        if (content == null) return null;
        StringBuilder sb = new StringBuilder();
        for (CliMessage.ContentBlock block : content) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                sb.append(block.getText());
            }
        }
        return sb.length() > 0 ? sb.toString().trim() : null;
    }

    private void fireUserMessageAdded(MessageBlock block) {
        for (IConversationListener l : listeners) {
            try { l.onUserMessageAdded(block); } catch (Exception e) { logError(e); }
        }
    }

    private void fireAssistantMessageStarted(MessageBlock block) {
        for (IConversationListener l : listeners) {
            try { l.onAssistantMessageStarted(block); } catch (Exception e) { logError(e); }
        }
    }

    private void fireAssistantMessageRemoved(MessageBlock block) {
        for (IConversationListener l : listeners) {
            try { l.onAssistantMessageRemoved(block); } catch (Exception e) { logError(e); }
        }
    }

    private void fireStreamingTextAppended(MessageBlock block, String delta) {
        for (IConversationListener l : listeners) {
            try { l.onStreamingTextAppended(block, delta); } catch (Exception e) { logError(e); }
        }
    }

    private void fireToolCallStarted(MessageBlock block, MessageBlock.ToolCallSegment toolCall) {
        for (IConversationListener l : listeners) {
            try { l.onToolCallStarted(block, toolCall); } catch (Exception e) { logError(e); }
        }
    }

    private void fireToolCallInputDelta(MessageBlock block, MessageBlock.ToolCallSegment toolCall, String delta) {
        for (IConversationListener l : listeners) {
            try { l.onToolCallInputDelta(block, toolCall, delta); } catch (Exception e) { logError(e); }
        }
    }

    private void fireToolCallInputComplete(MessageBlock block, MessageBlock.ToolCallSegment toolCall) {
        for (IConversationListener l : listeners) {
            try { l.onToolCallInputComplete(block, toolCall); } catch (Exception e) { logError(e); }
        }
    }

    private void fireToolCallCompleted(MessageBlock block, MessageBlock.ToolCallSegment toolCall) {
        for (IConversationListener l : listeners) {
            try { l.onToolCallCompleted(block, toolCall); } catch (Exception e) { logError(e); }
        }
    }

    private void fireAssistantMessageCompleted(MessageBlock block) {
        block.setComplete(true);
        for (IConversationListener l : listeners) {
            try { l.onAssistantMessageCompleted(block); } catch (Exception e) { logError(e); }
        }
    }

    private void fireResultReceived(UsageInfo usage) {
        for (IConversationListener l : listeners) {
            try { l.onResultReceived(usage); } catch (Exception e) { logError(e); }
        }
    }

    private void firePermissionRequested(String toolUseId, String toolName, String description,
                                          String requestId, Object toolInput) {
        for (IConversationListener l : listeners) {
            try { l.onPermissionRequested(toolUseId, toolName, description, requestId, toolInput); } catch (Exception e) { logError(e); }
        }
    }

    private void fireExtendedThinkingStarted() {
        for (IConversationListener l : listeners) {
            try { l.onExtendedThinkingStarted(); } catch (Exception e) { logError(e); }
        }
    }

    private void fireExtendedThinkingEnded() {
        for (IConversationListener l : listeners) {
            try { l.onExtendedThinkingEnded(); } catch (Exception e) { logError(e); }
        }
    }

    private void fireError(String error) {
        for (IConversationListener l : listeners) {
            try { l.onError(error); } catch (Exception e) { logError(e); }
        }
    }

    private void fireConversationCleared() {
        for (IConversationListener l : listeners) {
            try { l.onConversationCleared(); } catch (Exception e) { logError(e); }
        }
    }

    private void logError(Exception e) {
        Activator.logError("[ConversationModel] Listener error: " + e.getMessage(), e);
    }
}
