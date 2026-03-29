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

    // ==================== ICliMessageListener Implementation ====================

    @Override
    public void onMessage(CliMessage message) {
        if (message instanceof CliMessage.SystemInit) {
            handleSystemInit((CliMessage.SystemInit) message);
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
        // When stream events are active, ignore ALL assistant messages. The stream events
        // (message_start/content_block_*/message_stop) are authoritative and already drive the UI.
        if (usingStreamEvents) {
            return;
        }
        // Also ignore if the last message is already ASSISTANT (complete or still streaming).
        // The CLI sends redundant assistant snapshots mid-stream and after result.
        synchronized (messages) {
            if (!messages.isEmpty()) {
                MessageBlock lastMsg = messages.get(messages.size() - 1);
                if (lastMsg.getRole() == MessageBlock.Role.ASSISTANT) {
                    return; // Already have an assistant message — skip duplicate
                }
            }
        }
        // Also skip if there's an active streaming block
        if (currentStreamingBlock != null) {
            return;
        }

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
        // Start a new assistant message — but don't fire onAssistantMessageStarted yet.
        // We defer that until the first content block arrives so we never show an empty bubble.
        usingStreamEvents = true; // stream events are now driving this response
        currentStreamingBlock = new MessageBlock(MessageBlock.Role.ASSISTANT);
        synchronized (messages) {
            messages.add(currentStreamingBlock);
        }
        activeToolCalls.clear();
    }

    private void handleContentBlockStart(CliMessage.StreamEvent event) {
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
            // Append text to the current text segment
            MessageBlock.TextSegment textSeg = currentStreamingBlock.getOrCreateLastTextSegment();
            textSeg.appendText(delta.getText());
            fireStreamingTextAppended(currentStreamingBlock, delta.getText());

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
            fireAssistantMessageCompleted(currentStreamingBlock);
            currentStreamingBlock = null;
        }
    }

    private void handleResult(CliMessage.ResultMessage result) {
        // NOTE: Do NOT reset usingStreamEvents here. The CLI sends a redundant
        // assistant snapshot AFTER the result message. If we reset the flag,
        // handleAssistantMessage would process that snapshot and duplicate the text.
        // The flag is reset in handleMessageStart when a new streaming turn begins.

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
