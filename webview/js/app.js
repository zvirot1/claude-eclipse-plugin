/**
 * Main chat application logic for the Claude JCEF webview.
 *
 * Handles message rendering, input handling, streaming display,
 * permission prompts, tool call rendering, and markdown formatting.
 */
(function () {
    'use strict';

    // ==================== DOM References ====================

    var messagesContainer = document.getElementById('messages');
    var welcomeMessage = document.getElementById('welcome-message');
    var messageInput = document.getElementById('message-input');
    var sendBtn = document.getElementById('send-btn');
    var stopBtn = document.getElementById('stop-btn');
    var newSessionBtn = document.getElementById('new-session-btn');
    var connectionStatus = document.getElementById('connection-status');
    var statusText = connectionStatus.querySelector('.status-text');
    var usageInfo = document.getElementById('usage-info');
    var sessionModel = document.getElementById('session-model');
    var permissionBanner = document.getElementById('permission-banner');
    var permissionToolName = document.getElementById('permission-tool-name');
    var permissionDescription = document.getElementById('permission-description');
    var permissionAcceptBtn = document.getElementById('permission-accept-btn');
    var permissionRejectBtn = document.getElementById('permission-reject-btn');
    var permissionAlwaysBtn = document.getElementById('permission-always-btn');
    var clearBtn = document.getElementById('clear-btn');
    var settingsBtn = document.getElementById('settings-btn');
    var settingsDropdown = document.getElementById('settings-dropdown');
    var statusSessionId = document.getElementById('status-session-id');
    var statusModel = document.getElementById('status-model');
    var statusTokens = document.getElementById('status-tokens');
    var statusCost = document.getElementById('status-cost');
    var reconnectBtn = document.getElementById('reconnect-btn');
    var scrollToBottomBtn = document.getElementById('scroll-to-bottom-btn');
    var dropOverlay = document.getElementById('drop-overlay');
    var historyBtn = document.getElementById('history-btn');
    var headerStopBtn = document.getElementById('header-stop-btn');
    var newTabBtn = document.getElementById('new-tab-btn');
    var closeTabBtn = document.getElementById('close-tab-btn');
    // Active file pin (Amazon Q-style): a chip in #file-chips showing the file
    // currently open in the editor. The user can click X to dismiss it for the
    // current message; it re-appears when they switch tabs.
    var activeFile = { path: null, name: null, dismissed: false };
    var lastAttachments = []; // mirror of last attachments from Java (for re-render)
    var headerTitle = document.getElementById('header-title');
    var modeBadge = document.getElementById('session-mode');
    var modePopup = document.getElementById('mode-popup');
    var modeLabel = document.getElementById('mode-label');

    // ==================== State ====================

    var slashMenuEl = null;
    var slashMenuSelectedIndex = -1;
    var slashMenuItems = [];

    // Local slash command registry (matching SlashCommandHandler.java)
    // Kept client-side so the menu appears instantly without a bridge round-trip.
    var SLASH_COMMANDS = [
        { name: '/new',      description: 'Start a new conversation',            local: true,  hasSubOptions: false },
        { name: '/clear',    description: 'Clear the conversation display',      local: true,  hasSubOptions: false },
        { name: '/cost',     description: 'Show token usage and cost summary',   local: true,  hasSubOptions: false },
        { name: '/help',     description: 'Show available commands',             local: true,  hasSubOptions: false },
        { name: '/stop',     description: 'Stop the current query',              local: true,  hasSubOptions: false },
        { name: '/model',    description: 'Switch to a different model',         local: true,  hasSubOptions: true },
        { name: '/resume',   description: 'Resume a previous session',           local: true,  hasSubOptions: false },
        { name: '/history',  description: 'Browse and search session history',   local: true,  hasSubOptions: false },
        { name: '/compact',  description: 'Compact conversation context',        local: true,  hasSubOptions: false },
        { name: '/rules',   description: 'Manage Claude Code rules',            local: true,  hasSubOptions: false },
        { name: '/mcp',     description: 'Manage MCP servers',                  local: true,  hasSubOptions: false },
        { name: '/hooks',   description: 'Manage hooks',                        local: true,  hasSubOptions: false },
        { name: '/memory',  description: 'Edit project memory',                 local: true,  hasSubOptions: false },
        { name: '/skills',  description: 'Browse installed plugins and skills',  local: true,  hasSubOptions: false },
        { name: '/commit',   description: 'Generate a git commit message',       local: false, hasSubOptions: false },
        { name: '/review-pr',description: 'Review a pull request',               local: false, hasSubOptions: false },
        { name: '/explain',  description: 'Explain the current file or selection', local: false, hasSubOptions: false },
        { name: '/fix',      description: 'Fix bugs in the current file',        local: false, hasSubOptions: false },
        { name: '/test',     description: 'Generate tests for the current code', local: false, hasSubOptions: false },
        { name: '/refactor', description: 'Refactor the current code',           local: false, hasSubOptions: false }
    ];

    // Sub-options for commands that have them (matching SlashCommandHandler.getSubOptions)
    var COMMAND_SUB_OPTIONS = {
        '/model': [
            { value: 'claude-sonnet-4-6', label: 'Sonnet 4.6', description: 'Sonnet 4.6 — Fast, balanced' },
            { value: 'claude-opus-4-6',   label: 'Opus 4.6',   description: 'Opus 4.6 — Most capable' },
            { value: 'claude-haiku-4-5',  label: 'Haiku 4.5',  description: 'Haiku 4.5 — Fastest, lightweight' },
            { value: 'sonnet', label: 'Sonnet', description: 'Claude Sonnet (alias)' },
            { value: 'opus',   label: 'Opus',   description: 'Claude Opus (alias)' },
            { value: 'haiku',  label: 'Haiku',  description: 'Claude Haiku (alias)' }
        ]
    };

    var fileMenuEl = null;
    var fileMenuSelectedIndex = -1;
    var fileMenuItems = [];
    var fileMenuAtPos = -1; // position of the @ that triggered the menu

    var state = {
        isStreaming: false,
        isBusy: false,
        isConnected: false,
        hasMessages: false,
        currentAssistantEl: null,
        currentContentEl: null,
        streamingTextBuffer: '',
        pendingPermission: null,
        autoScrollEnabled: true,
        thinkingEl: null,
        loadingEl: null,
        attachedImages: [],  // Array of { dataUrl, bytes (base64) }
        ctrlEnterToSend: false,  // When true: Ctrl+Enter sends, Enter inserts newline
        messageIndex: 0,  // Counter for message indices (used by fork)
        currentMode: 'default'  // Permission mode: 'default', 'acceptEdits', 'plan'
    };

    // ==================== Initialization ====================

    function init() {
        setupInputHandlers();
        setupButtonHandlers();
        setupScrollHandler();
        setupBridgeListeners();
        setupDragAndDrop();
        autoResizeTextarea();

        // Signal that the webview is ready
        bridge.sendToJava('webview_ready', {});
    }

    // ==================== Input Handling ====================

    function setupInputHandlers() {
        messageInput.addEventListener('keydown', function (e) {
            // Handle popup menu navigation (slash or file menu)
            var activeMenu = getActivePopupMenu();
            if (activeMenu) {
                if (e.key === 'ArrowDown') {
                    e.preventDefault();
                    activeMenu.selectNext();
                    return;
                }
                if (e.key === 'ArrowUp') {
                    e.preventDefault();
                    activeMenu.selectPrev();
                    return;
                }
                if ((e.key === 'Enter' || e.key === 'Tab') && activeMenu.getSelectedIndex() >= 0) {
                    e.preventDefault();
                    activeMenu.selectCurrent();
                    return;
                }
                if (e.key === 'Escape') {
                    e.preventDefault();
                    activeMenu.hide();
                    return;
                }
            }

            // Send message: depends on ctrlEnterToSend preference
            if (e.key === 'Enter') {
                if (state.ctrlEnterToSend) {
                    // Ctrl+Enter or Cmd+Enter sends; plain Enter inserts newline
                    if (e.ctrlKey || e.metaKey) {
                        e.preventDefault();
                        sendMessage();
                        return;
                    }
                } else {
                    // Enter sends (default); Shift+Enter inserts newline
                    if (!e.shiftKey) {
                        e.preventDefault();
                        sendMessage();
                        return;
                    }
                }
            }
            // Shift+Tab: cycle permission mode
            if (e.key === 'Tab' && e.shiftKey) {
                e.preventDefault();
                cycleMode();
                return;
            }
            // Escape: cancel streaming if active, otherwise clear input
            if (e.key === 'Escape') {
                e.preventDefault();
                if (state.isStreaming) {
                    bridge.sendToJava('stop_generation', {});
                } else {
                    messageInput.value = '';
                    autoResizeTextarea();
                    updateSendButton();
                }
                return;
            }
        });

        messageInput.addEventListener('input', function () {
            autoResizeTextarea();
            updateSendButton();
            handleSlashInput();
            handleAtMentionInput();
        });

        messageInput.addEventListener('paste', function () {
            setTimeout(function () {
                autoResizeTextarea();
                updateSendButton();
            }, 0);
        });
    }

    function autoResizeTextarea() {
        messageInput.style.height = 'auto';
        var scrollHeight = messageInput.scrollHeight;
        var maxHeight = 200;
        messageInput.style.height = Math.min(scrollHeight, maxHeight) + 'px';
    }

    function updateSendButton() {
        var hasText = messageInput.value.trim().length > 0;
        sendBtn.disabled = !hasText || state.isStreaming;
    }

    function sendMessage() {
        var text = messageInput.value.trim();
        if (!text || state.isStreaming) return;

        // Active-file pin: if the chip is currently shown (not dismissed),
        // tell Java to attach the active file via the same context pipeline
        // used by @-mention / attach button (no extra "wire format").
        var includeActiveFile = !!(activeFile.name && !activeFile.dismissed);

        // Include attached images if any
        var payload = { message: text, includeActiveFile: includeActiveFile };
        if (state.attachedImages.length > 0) {
            var imageData = [];
            for (var i = 0; i < state.attachedImages.length; i++) {
                imageData.push(state.attachedImages[i].bytes);
            }
            payload.images = imageData;
        }
        bridge.sendToJava('send_message', payload);

        messageInput.value = '';
        messageInput.style.direction = 'ltr';
        messageInput.style.textAlign = 'left';
        clearAttachments();
        autoResizeTextarea();
        // Switch to stop-button state IMMEDIATELY on send. Previously this
        // only happened when `assistant_message_started` arrived from the
        // CLI — which on slow networks (e.g. Bank Leumi's AIM-hook proxy
        // through Bedrock) can be 30-60 s after Send. The user had no way
        // to abort during that window, and the UI looked like nothing was
        // happening yet. `handleError` + `handleAssistantMessageCompleted`
        // + `handleGenerationStopped` all reset this back to send state.
        setStreamingState(true);
        updateSendButton();
        messageInput.focus();
        showLoadingIndicator();
    }

    // ==================== Button Handlers ====================

    function setupButtonHandlers() {
        sendBtn.addEventListener('click', function () {
            sendMessage();
        });

        stopBtn.addEventListener('click', function () {
            bridge.sendToJava('stop_generation', {});
        });

        newSessionBtn.addEventListener('click', function () {
            bridge.sendToJava('new_session', {});
        });


        permissionAcceptBtn.addEventListener('click', function () {
            if (state.pendingPermission) {
                bridge.sendToJava('accept_permission', state.pendingPermission);
                hidePermissionBanner();
            }
        });

        permissionRejectBtn.addEventListener('click', function () {
            if (state.pendingPermission) {
                bridge.sendToJava('reject_permission', state.pendingPermission);
                hidePermissionBanner();
            }
        });

        permissionAlwaysBtn.addEventListener('click', function () {
            if (state.pendingPermission) {
                bridge.sendToJava('always_allow_permission', state.pendingPermission);
                hidePermissionBanner();
            }
        });

        clearBtn.addEventListener('click', function () {
            bridge.sendToJava('clear_conversation', {});
        });

        // Settings dropdown toggle
        settingsBtn.addEventListener('click', function (e) {
            e.stopPropagation();
            settingsDropdown.classList.toggle('hidden');
        });

        // Settings dropdown item click
        settingsDropdown.addEventListener('click', function (e) {
            var item = e.target.closest('.settings-dropdown-item');
            if (item) {
                var dialog = item.getAttribute('data-dialog');
                bridge.sendToJava('open_dialog', { dialog: dialog });
                settingsDropdown.classList.add('hidden');
            }
        });

        // Close dropdown when clicking outside
        document.addEventListener('click', function () {
            if (settingsDropdown && !settingsDropdown.classList.contains('hidden')) {
                settingsDropdown.classList.add('hidden');
            }
            if (modePopup && !modePopup.classList.contains('hidden')) {
                modePopup.classList.add('hidden');
            }
        });

        // Mode selector badge click — toggle popup
        if (modeBadge) {
            modeBadge.addEventListener('click', function (e) {
                e.stopPropagation();
                modePopup.classList.toggle('hidden');
                // Close settings dropdown if open
                settingsDropdown.classList.add('hidden');
            });
        }

        // Mode option click + effort dot click
        if (modePopup) {
            modePopup.addEventListener('click', function (e) {
                e.stopPropagation();
                // Mode option
                var option = e.target.closest('.mode-option');
                if (option) {
                    var mode = option.getAttribute('data-mode');
                    setMode(mode);
                    bridge.sendToJava('change_mode', { mode: mode });
                    modePopup.classList.add('hidden');
                    return;
                }
                // Effort dot
                var dot = e.target.closest('.effort-dot');
                if (dot) {
                    var effort = dot.getAttribute('data-effort');
                    setEffort(effort);
                    bridge.sendToJava('change_effort', { effort: effort });
                }
            });
        }

        // Reconnect button — restart the CLI process for the *current* session.
        // (Don't conflate this with "+" / new_session, which clears the chat.)
        reconnectBtn.addEventListener('click', function () {
            bridge.sendToJava('reconnect', {});
        });

        // Scroll to bottom button
        scrollToBottomBtn.addEventListener('click', function () {
            state.autoScrollEnabled = true;
            messagesContainer.scrollTop = messagesContainer.scrollHeight;
            scrollToBottomBtn.classList.add('hidden');
        });

        // History button
        if (historyBtn) {
            historyBtn.addEventListener('click', function () {
                bridge.sendToJava('open_dialog', { dialog: 'history' });
            });
        }

        // Header stop button
        if (headerStopBtn) {
            headerStopBtn.addEventListener('click', function () {
                bridge.sendToJava('stop_generation', {});
            });
        }

        // New Conversation Window — opens a new tab with a fresh session
        if (newTabBtn) {
            newTabBtn.addEventListener('click', function () {
                bridge.sendToJava('new_tab', {});
            });
        }

        // Double-click on header title to rename tab
        if (headerTitle) {
            headerTitle.style.cursor = 'pointer';
            headerTitle.title = 'Double-click to rename';
            headerTitle.addEventListener('dblclick', function (e) {
                e.preventDefault();
                var currentName = headerTitle.textContent;
                var input = document.createElement('input');
                input.type = 'text';
                input.value = currentName;
                input.className = 'rename-input';
                input.style.cssText = 'font-size:inherit;font-weight:inherit;background:var(--bg-input);color:var(--text-primary);border:1px solid var(--claude-orange);border-radius:4px;padding:1px 4px;outline:none;width:120px;';
                headerTitle.textContent = '';
                headerTitle.appendChild(input);
                input.focus();
                input.select();

                function finishRename() {
                    var newName = input.value.trim();
                    if (!newName) newName = currentName;
                    headerTitle.textContent = newName;
                    bridge.sendToJava('rename_tab', { name: newName });
                }
                input.addEventListener('keydown', function (ev) {
                    if (ev.key === 'Enter') { ev.preventDefault(); finishRename(); }
                    if (ev.key === 'Escape') { ev.preventDefault(); headerTitle.textContent = currentName; }
                });
                input.addEventListener('blur', finishRename);
            });
        }

        // Close Tab — closes this conversation tab
        if (closeTabBtn) {
            closeTabBtn.addEventListener('click', function () {
                bridge.sendToJava('close_tab', {});
            });
        }
    }

    // ==================== Scroll Handling ====================

    function setupScrollHandler() {
        messagesContainer.addEventListener('scroll', function () {
            var el = messagesContainer;
            var distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
            state.autoScrollEnabled = distanceFromBottom < 60;

            // Show/hide scroll-to-bottom button
            if (distanceFromBottom > 200 && state.hasMessages) {
                scrollToBottomBtn.classList.remove('hidden');
            } else {
                scrollToBottomBtn.classList.add('hidden');
            }
        });
    }

    function scrollToBottom() {
        if (state.autoScrollEnabled) {
            requestAnimationFrame(function () {
                messagesContainer.scrollTop = messagesContainer.scrollHeight;
            });
        }
    }

    // ==================== Bridge Event Listeners ====================

    function setupBridgeListeners() {
        bridge.on('cli_state_changed', handleCliStateChanged);
        bridge.on('busy_state_changed', handleBusyStateChanged);
        bridge.on('session_initialized', handleSessionInitialized);
        bridge.on('user_message_added', handleUserMessageAdded);
        bridge.on('assistant_message_started', handleAssistantMessageStarted);
        bridge.on('streaming_text_appended', handleStreamingTextAppended);
        bridge.on('tool_call_started', handleToolCallStarted);
        bridge.on('tool_call_input_delta', handleToolCallInputDelta);
        bridge.on('tool_call_completed', handleToolCallCompleted);
        bridge.on('assistant_message_completed', handleAssistantMessageCompleted);
        bridge.on('result_received', handleResultReceived);
        bridge.on('permission_requested', handlePermissionRequested);
        bridge.on('extended_thinking_started', handleExtendedThinkingStarted);
        bridge.on('extended_thinking_ended', handleExtendedThinkingEnded);
        bridge.on('error', handleError);
        bridge.on('conversation_cleared', handleConversationCleared);
        bridge.on('generation_stopped', handleGenerationStopped);
        bridge.on('system_message', handleSystemMessage);
        bridge.on('slash_suggestions', handleSlashSuggestions);
        bridge.on('file_suggestions', handleFileSuggestions);
        bridge.on('command_picker_options', handleCommandPickerOptions);
        bridge.on('set_theme', handleSetTheme);
        bridge.on('set_show_timestamps', function (data) {
            // Toggle visibility of per-bubble timestamps via body class —
            // CSS hides .message-time when body.hide-timestamps is set.
            if (data && data.visible === false) {
                document.body.classList.add('hide-timestamps');
            } else {
                document.body.classList.remove('hide-timestamps');
            }
        });
        bridge.on('set_enter_mode', function(data) {
            state.ctrlEnterToSend = data.ctrlEnter === true;
            // Update hint text
            var hint = document.querySelector('.input-hint');
            if (hint) {
                hint.textContent = state.ctrlEnterToSend ? 'Ctrl+Enter ↵' : 'Enter ↵ | Shift+Enter ↵';
            }
        });
        bridge.on('toast', function(data) {
            if (data && data.message) showToast(data.message);
        });
        bridge.on('selection_indicator', handleSelectionIndicator);
        bridge.on('paste_from_clipboard', handlePasteFromClipboard);
        bridge.on('active_file_changed', handleActiveFileChanged);
        bridge.on('attach_active_file_changed', handleAttachActiveFileChanged);
        bridge.on('mode_changed', function(data) {
            if (data && data.mode) setMode(data.mode);
        });
        bridge.on('effort_changed', function(data) {
            if (data) setEffort(data.effort || 'medium');
        });
        bridge.on('tab_renamed', function(data) {
            if (data && data.name && headerTitle) {
                headerTitle.textContent = data.name;
            }
        });
        bridge.on('edit_staged', handleEditStaged);
        bridge.on('attachments_updated', handleAttachmentsUpdated);
        bridge.on('attachments_cleared', handleAttachmentsCleared);
    }

    // ==================== Theme & RTL ====================

    function handleSetTheme(data) {
        if (data.theme === 'light') {
            document.body.classList.add('light');
        } else {
            document.body.classList.remove('light');
        }
    }

    // ==================== Selection Indicator ====================

    function handleSelectionIndicator(data) {
        var badge = document.getElementById('selection-badge');
        if (!badge) {
            badge = document.createElement('div');
            badge.id = 'selection-badge';
            badge.className = 'selection-badge';
            var inputArea = document.querySelector('.input-area');
            if (inputArea) inputArea.parentNode.insertBefore(badge, inputArea);
        }
        if (data.lineCount > 0) {
            badge.textContent = data.lineCount + ' lines in ' + data.fileName;
            badge.classList.remove('hidden');
        } else {
            badge.classList.add('hidden');
        }
    }

    // ==================== Active File Pin (Amazon Q style) ====================

    /** Called when the user switches editor tabs in the IDE. The chip is
     *  re-shown automatically (any prior dismiss is reset on tab change). */
    function handleActiveFileChanged(data) {
        var prevPath = activeFile.path;
        if (!data || !data.name) {
            activeFile = { path: null, name: null, dismissed: false };
        } else {
            activeFile = {
                path: data.path,
                name: data.name,
                // If the path changed, undismiss; otherwise keep prior state.
                dismissed: (data.path === prevPath) ? activeFile.dismissed : false
            };
        }
        renderAllChips();
    }

    /** No-op now — kept for back-compat with the Java-side initial-state event. */
    function handleAttachActiveFileChanged(data) {
        // Q-style behavior: the chip is always shown when there is an active file
        // (until the user clicks X). No global toggle.
    }

    // ==================== Edit Staged (Accept/Reject) ====================

    function handleEditStaged(data) {
        var widget = document.createElement('div');
        widget.className = 'edit-staged-widget';
        // The editId is the absolute file path; on Windows it contains
        // backslashes. We MUST NOT interpolate it into an inline onclick
        // attribute — the JS string-literal parser drops unknown escape
        // sequences (\U → U, \T → T, \t → TAB), corrupting the path.
        // Store it on a data attribute (HTML attribute escaping is safe
        // for backslashes) and wire click handlers with addEventListener.
        widget.setAttribute('data-edit-id', data.editId);
        widget.innerHTML = '<span class="edit-staged-icon">✏</span> Edited: <strong>'
            + escapeHtml(data.fileName) + '</strong>'
            + '<div class="edit-staged-actions">'
            + '<button class="edit-btn view-diff-btn" data-action="view_diff">View Diff</button>'
            + '<button class="edit-btn accept-btn" data-action="accept_edit">Accept</button>'
            + '<button class="edit-btn reject-btn" data-action="reject_edit">Reject</button>'
            + '</div>';

        // Wire click handlers that read editId from the widget's data
        // attribute. This roundtrips the path without escape-sequence loss.
        var buttons = widget.querySelectorAll('.edit-btn');
        for (var i = 0; i < buttons.length; i++) {
            buttons[i].addEventListener('click', function (ev) {
                var action = ev.currentTarget.getAttribute('data-action');
                var editId = widget.getAttribute('data-edit-id');
                bridge.sendToJava(action, { editId: editId });
                if (action !== 'view_diff') {
                    widget.remove();
                }
            });
        }

        // Insert after the current assistant message
        var msgs = document.getElementById('messages');
        if (msgs) msgs.appendChild(widget);
        scrollToBottom();
    }

    // ==================== Fork Context Menu ====================

    var activeContextMenu = null;

    function showForkContextMenu(e, messageEl) {
        e.preventDefault();
        dismissContextMenu();

        var idx = messageEl.getAttribute('data-message-index');
        if (idx === null) return;

        var menu = document.createElement('div');
        menu.className = 'fork-context-menu';

        var item = document.createElement('div');
        item.className = 'fork-context-menu-item';
        item.innerHTML = '<span class="fork-icon">\u2442</span> Fork from here';
        item.addEventListener('click', function() {
            bridge.sendToJava('fork_from_message', { messageIndex: parseInt(idx, 10) });
            dismissContextMenu();
        });
        menu.appendChild(item);

        // Position at cursor
        menu.style.left = e.clientX + 'px';
        menu.style.top = e.clientY + 'px';
        document.body.appendChild(menu);

        // Adjust if overflowing viewport
        var rect = menu.getBoundingClientRect();
        if (rect.right > window.innerWidth) {
            menu.style.left = (window.innerWidth - rect.width - 8) + 'px';
        }
        if (rect.bottom > window.innerHeight) {
            menu.style.top = (window.innerHeight - rect.height - 8) + 'px';
        }

        activeContextMenu = menu;
    }

    function dismissContextMenu() {
        if (activeContextMenu) {
            activeContextMenu.remove();
            activeContextMenu = null;
        }
    }

    // Dismiss on click outside or Escape
    document.addEventListener('click', dismissContextMenu);
    document.addEventListener('keydown', function(e) {
        if (e.key === 'Escape') {
            dismissContextMenu();
            // Cancel streaming from anywhere on the page
            if (state.isStreaming) {
                e.preventDefault();
                bridge.sendToJava('stop_generation', {});
            }
        }
    });

    // Attach context menu to messages container (event delegation)
    messagesContainer.addEventListener('contextmenu', function(e) {
        var msgEl = e.target.closest('.message');
        if (msgEl && msgEl.hasAttribute('data-message-index')) {
            showForkContextMenu(e, msgEl);
        }
    });

    // ==================== RTL Detection ====================

    /**
     * Updates the input textarea direction based on the first significant character.
     * Called on every input event so the textarea flips to RTL when typing Hebrew/Arabic.
     */
    function updateInputDirection() {
        var text = messageInput.value;
        var firstChar = text.replace(/[\s\n\r\t@#/\-`>]/g, '').charAt(0);
        if (firstChar && /[\u0590-\u05FF\u0600-\u06FF\u0700-\u074F\uFB50-\uFDFF\uFE70-\uFEFF]/.test(firstChar)) {
            messageInput.style.direction = 'rtl';
            messageInput.style.textAlign = 'right';
        } else {
            messageInput.style.direction = 'ltr';
            messageInput.style.textAlign = 'left';
        }
    }

    // Attach input direction listener
    messageInput.addEventListener('input', updateInputDirection);

    /**
     * Detects RTL text and applies dir="rtl" to message content elements.
     *
     * <p>Looks at the FIRST significant text character that ISN'T inside
     * a code/pre block. Otherwise a Hebrew message that begins with a
     * Java code block would inspect the code's first letter (English),
     * conclude LTR, and leave the surrounding Hebrew explanation
     * left-aligned. Mirrors the typical reading direction the user
     * actually sees.
     */
    function applyRtlIfNeeded(el) {
        var firstChar = findFirstSignificantNonCodeChar(el);
        if (firstChar && /[\u0590-\u05FF\u0600-\u06FF\u0700-\u074F\uFB50-\uFDFF\uFE70-\uFEFF]/.test(firstChar)) {
            el.setAttribute('dir', 'rtl');
        } else {
            el.setAttribute('dir', 'auto');
        }
    }

    /** Walk the DOM and find the first non-whitespace text char that
     *  lives OUTSIDE of any <code>/<pre>/.code-block-wrapper. */
    function findFirstSignificantNonCodeChar(root) {
        try {
            var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
                acceptNode: function (n) {
                    var p = n.parentNode;
                    while (p && p !== root) {
                        var tag = p.tagName ? p.tagName.toLowerCase() : '';
                        if (tag === 'code' || tag === 'pre') return NodeFilter.FILTER_REJECT;
                        if (p.classList && (p.classList.contains('code-block-wrapper')
                                         || p.classList.contains('code-block-header'))) {
                            return NodeFilter.FILTER_REJECT;
                        }
                        p = p.parentNode;
                    }
                    return NodeFilter.FILTER_ACCEPT;
                }
            });
            var node;
            while ((node = walker.nextNode())) {
                var t = (node.nodeValue || '').replace(/[\s\n\r\t#*\-`>]/g, '');
                if (t.length > 0) return t.charAt(0);
            }
        } catch (e) {
            // TreeWalker not supported (very old) \u2014 fall back to textContent
            var text = (root.textContent || '').replace(/[\s\n\r\t#*\-`>]/g, '');
            return text.charAt(0);
        }
        return '';
    }

    // ==================== Timestamp helpers ====================

    /**
     * Format a millis-epoch timestamp for display next to a message bubble.
     * ALWAYS show date + time so the user can identify resumed-session
     * turns at a glance. Format: "DD/MM HH:MM:SS" (same year), or
     * "DD/MM/YY HH:MM:SS" if from a previous year.
     */
    function formatBubbleTime(tsMs) {
        if (!tsMs || tsMs <= 0) return '';
        var d = new Date(tsMs);
        var pad = function (n) { return n < 10 ? '0' + n : '' + n; };
        var hh = pad(d.getHours()), mm = pad(d.getMinutes()), ss = pad(d.getSeconds());
        var time = hh + ':' + mm + ':' + ss;
        var today = new Date();
        var sameYear = d.getFullYear() === today.getFullYear();
        var datePart = pad(d.getDate()) + '/' + pad(d.getMonth() + 1);
        if (!sameYear) datePart += '/' + String(d.getFullYear()).slice(-2);
        return datePart + ' ' + time;
    }

    /**
     * Human-readable duration from milliseconds — "8s", "1m 23s", "1h 5m".
     * Matches V1's formatDuration in MessageComposite.
     */
    function formatDuration(ms) {
        if (ms < 0) return '';
        if (ms < 1000) return ms + 'ms';
        var s = Math.round(ms / 1000);
        if (s < 60) return s + 's';
        var m = Math.floor(s / 60);
        var rs = s % 60;
        if (m < 60) return m + 'm ' + rs + 's';
        var h = Math.floor(m / 60);
        var rm = m % 60;
        return h + 'h ' + rm + 'm';
    }

    /** Create a <time> element holding the bubble's HH:MM:SS label. */
    function createTimeElement(tsMs) {
        var t = document.createElement('time');
        t.className = 'message-time';
        t.setAttribute('data-ts', String(tsMs || 0));
        t.textContent = formatBubbleTime(tsMs);
        return t;
    }

    /**
     * Find the timestamp of the user message immediately preceding the
     * given assistant message element. Used to compute the assistant's
     * response duration. Returns 0 if not found.
     */
    function findPrecedingUserTimestamp(assistantEl) {
        var prev = assistantEl.previousElementSibling;
        while (prev) {
            if (prev.classList && prev.classList.contains('message-user')) {
                var t = prev.querySelector('.message-time');
                if (t) {
                    var v = parseInt(t.getAttribute('data-ts') || '0', 10);
                    return isNaN(v) ? 0 : v;
                }
                return 0;
            }
            prev = prev.previousElementSibling;
        }
        return 0;
    }

    // ==================== Event Handlers ====================

    function handleCliStateChanged(data) {
        state.isConnected = data.state === 'connected';
        connectionStatus.className = 'status-indicator ' + data.state;

        switch (data.state) {
            case 'connecting':
                // Synthetic state Java fires while the CLI process is starting.
                // Hide Reconnect (no error to recover from yet) and show progress.
                statusText.textContent = 'Connecting…';
                reconnectBtn.classList.add('hidden');
                break;
            case 'connected':
                statusText.textContent = 'Connected';
                reconnectBtn.classList.add('hidden');
                break;
            case 'disconnected':
                statusText.textContent = 'Disconnected';
                reconnectBtn.classList.remove('hidden');
                setStreamingState(false);
                removeLoadingIndicator();
                break;
            case 'error':
                statusText.textContent = 'Error';
                reconnectBtn.classList.remove('hidden');
                setStreamingState(false);
                removeLoadingIndicator();
                break;
        }
    }

    function handleBusyStateChanged(data) {
        state.isBusy = data.busy;
        setStreamingState(data.busy);
    }

    function handleSessionInitialized(data) {
        if (data.model) {
            sessionModel.textContent = data.model;
            sessionModel.classList.add('visible');
            statusModel.textContent = data.model;
            statusModel.classList.remove('hidden');
        }
        if (data.sessionId) {
            statusSessionId.textContent = data.sessionId.substring(0, 8);
            statusSessionId.title = data.sessionId;
            statusSessionId.classList.remove('hidden');
        }
    }

    function handleUserMessageAdded(data) {
        hideWelcome();
        var el = createMessageElement('user', data);
        el.setAttribute('data-message-index', state.messageIndex++);
        messagesContainer.appendChild(el);
        scrollToBottom();
    }

    function handleAssistantMessageStarted(data) {
        hideWelcome();
        removeThinkingIndicator();
        // Keep the "Thinking..." indicator visible until the first text
        // chunk actually arrives — message_started fires before the model
        // has produced any visible content, so removing it here would
        // blink the affordance away too soon.
        setStreamingState(true);

        var el = document.createElement('div');
        el.className = 'message message-assistant';

        // Per-bubble timestamp at the top of the bubble. data.timestamp is
        // ALWAYS provided (Java passes block.getTimestamp() — original
        // JSONL ts for replayed history, System.currentTimeMillis() for
        // live messages). Duration suffix gets appended in
        // handleAssistantMessageCompleted once we know the finish time.
        if (data && data.timestamp) {
            el.appendChild(createTimeElement(data.timestamp));
        }

        var contentEl = document.createElement('div');
        contentEl.className = 'message-content streaming-cursor';
        el.appendChild(contentEl);

        el.setAttribute('data-message-index', state.messageIndex++);
        state.currentAssistantEl = el;
        state.currentContentEl = contentEl;
        state.streamingTextBuffer = '';

        // Render any segments that already exist (e.g., replayed messages)
        if (data.segments) {
            for (var i = 0; i < data.segments.length; i++) {
                var seg = data.segments[i];
                if (seg.type === 'text' && seg.text) {
                    state.streamingTextBuffer += seg.text;
                } else if (seg.type === 'tool_use') {
                    // Flush text before tool call
                    if (state.streamingTextBuffer) {
                        renderMarkdown(contentEl, state.streamingTextBuffer);
                        state.streamingTextBuffer = '';
                    }
                    appendToolCallElement(el, seg);
                }
            }
            if (state.streamingTextBuffer) {
                renderMarkdown(contentEl, state.streamingTextBuffer);
            }
        }

        messagesContainer.appendChild(el);
        scrollToBottom();
    }

    function handleStreamingTextAppended(data) {
        if (!state.currentContentEl) return;

        // First text chunk arrived — drop the "Thinking..." indicator.
        if (state.loadingEl) removeLoadingIndicator();

        state.streamingTextBuffer += data.delta;
        renderMarkdown(state.currentContentEl, state.streamingTextBuffer);
        scrollToBottom();
    }

    function handleToolCallStarted(data) {
        console.log('[TOOL_STARTED] toolId=' + data.toolId + ' toolName=' + data.toolName);
        // First real activity (tool call before any text) — drop the indicator.
        if (state.loadingEl) removeLoadingIndicator();
        if (!state.currentAssistantEl) return;

        // Flush any buffered text into the content element before the tool call
        if (state.streamingTextBuffer && state.currentContentEl) {
            state.currentContentEl.classList.remove('streaming-cursor');
            renderMarkdown(state.currentContentEl, state.streamingTextBuffer);
            state.streamingTextBuffer = '';
        }

        appendToolCallElement(state.currentAssistantEl, data);
        scrollToBottom();
    }

    function handleToolCallInputDelta(data) {
        var toolEl = findToolCallElement(data.toolId);
        if (!toolEl) return;

        var inputContent = toolEl.querySelector('.tool-input-content');
        if (inputContent) {
            inputContent.textContent += data.delta;
        }
    }

    function handleToolCallCompleted(data) {
        console.log('[TOOL_COMPLETED] toolId=' + data.toolId + ' status=' + data.status + ' toolName=' + data.toolName);
        var toolEl = findToolCallElement(data.toolId);
        if (!toolEl) {
            console.warn('[TOOL_COMPLETED] Element NOT FOUND for toolId=' + data.toolId);
            return;
        }
        console.log('[TOOL_COMPLETED] Element found, updating to ' + data.status);

        // Update status
        toolEl.classList.remove('running');
        if (data.status === 'failed') {
            toolEl.classList.add('failed');
        }

        var statusEl = toolEl.querySelector('.tool-call-status');
        if (statusEl) {
            statusEl.className = 'tool-call-status ' + data.status;
            statusEl.textContent = capitalizeFirst(data.status);
        }

        var iconEl = toolEl.querySelector('.tool-call-icon');
        if (iconEl) {
            iconEl.className = 'tool-call-icon ' + data.status;
            iconEl.innerHTML = getStatusIcon(data.status);
        }

        // Update output
        if (data.output) {
            var body = toolEl.querySelector('.tool-call-body');
            if (body) {
                var existingOutputLabel = body.querySelector('.tool-output-label');
                if (!existingOutputLabel) {
                    var outputLabel = document.createElement('div');
                    outputLabel.className = 'tool-call-section-label tool-output-label';
                    outputLabel.textContent = 'Output';
                    body.appendChild(outputLabel);

                    var outputContent = document.createElement('div');
                    outputContent.className = 'tool-call-section-content';
                    outputContent.textContent = truncateOutput(data.output);
                    body.appendChild(outputContent);
                }
            }
        }

        // Update summary
        if (data.summary) {
            var summaryEl = toolEl.querySelector('.tool-call-summary');
            if (summaryEl) {
                summaryEl.textContent = data.summary;
            }
        }

        scrollToBottom();
    }

    function handleAssistantMessageCompleted(data) {
        // Flush any remaining text
        if (state.streamingTextBuffer && state.currentContentEl) {
            renderMarkdown(state.currentContentEl, state.streamingTextBuffer);
            state.streamingTextBuffer = '';
        }

        // Remove streaming cursor
        if (state.currentContentEl) {
            state.currentContentEl.classList.remove('streaming-cursor');
            applyRtlIfNeeded(state.currentContentEl);
        }

        // Apply RTL + clear streaming-cursor on all content elements in
        // this assistant message. appendToolCallElement creates a fresh
        // contentEl with streaming-cursor *after* every tool call to
        // receive subsequent text; only the LAST one is cleared by the
        // line above. On replay (and on live streaming with multiple
        // tool calls) every intermediate contentEl was left with the
        // blinking-cursor class — visible as red flickering bars between
        // tool calls. Clear them all here.
        if (state.currentAssistantEl) {
            var contentEls = state.currentAssistantEl.querySelectorAll('.message-content');
            for (var i = 0; i < contentEls.length; i++) {
                contentEls[i].classList.remove('streaming-cursor');
                applyRtlIfNeeded(contentEls[i]);
            }

            // Append duration to the bubble's timestamp ("HH:MM:SS · 23s").
            // For live messages, data.finishedAt is the wall-clock at
            // completion. For replayed history, data.finishedAt equals
            // block.timestamp (the JSONL write time = end of turn) so the
            // historical duration is preserved correctly. Duration is the
            // gap from the preceding USER bubble to this assistant's end.
            var timeEl = state.currentAssistantEl.querySelector('.message-time');
            if (timeEl) {
                var finishedAt = (data && data.finishedAt) ? data.finishedAt : Date.now();
                var precedingTs = findPrecedingUserTimestamp(state.currentAssistantEl);
                if (precedingTs > 0 && finishedAt > precedingTs) {
                    var dur = finishedAt - precedingTs;
                    timeEl.textContent = formatBubbleTime(parseInt(timeEl.getAttribute('data-ts'), 10))
                                       + ' · ' + formatDuration(dur);
                }
            }
        }

        removeThinkingIndicator();

        // Create a new content element for text after tool calls, if needed
        state.currentAssistantEl = null;
        state.currentContentEl = null;

        // Only flip the Send/Stop button back to Send if no tool is still
        // running. When the assistant message stops with stop_reason="tool_use",
        // a tool is mid-execution and the model will continue once the tool
        // result comes back — Stop should stay visible the whole time.
        // The result message will eventually arrive and force-finish via
        // handleResultReceived(); this just keeps the button accurate
        // *during* tool execution.
        if (!hasRunningToolCalls()) {
            // Remember the actual final assistant bubble (not a loading /
            // thinking placeholder). handleResultReceived will append the
            // completion footer here.
            state.lastFinalAssistantEl = state.currentAssistantEl;
            setStreamingState(false);
        }
        scrollToBottom();
    }

    /** True if any rendered tool-call widget is still in the 'running' state. */
    function hasRunningToolCalls() {
        return document.querySelectorAll('.tool-call.running').length > 0;
    }

    function handleResultReceived(data) {
        setStreamingState(false);
        removeLoadingIndicator();

        // Mirror V1's syncAllToolCallStatuses: if a tool call is still in
        // "running" state when the turn ends, the CLI's stream got cut off
        // (e.g. AWS Bedrock rate limit or proxy hiccup) before its
        // tool_call_completed event arrived. Mark those tools as aborted
        // so the UI doesn't show a spinning indicator forever.
        var stuckTools = messagesContainer.querySelectorAll('.tool-call.running');
        var abortedIds = [];
        for (var i = 0; i < stuckTools.length; i++) {
            var t = stuckTools[i];
            abortedIds.push(t.getAttribute('data-tool-id') || '<unknown>');
            t.classList.remove('running');
            t.classList.add('failed');
            var status = t.querySelector('.tool-call-status');
            if (status) {
                status.className = 'tool-call-status failed';
                status.textContent = 'Aborted';
            }
            var icon = t.querySelector('.tool-call-icon');
            if (icon) {
                icon.className = 'tool-call-icon failed';
                icon.innerHTML = getStatusIcon('failed');
            }
        }
        // Notify Java so it can log which tools were aborted — makes the
        // workspace .log a complete record of "what happened this turn"
        // without devs having to cross-reference the screenshot.
        if (abortedIds.length > 0) {
            bridge.sendToJava('tools_aborted_on_turn_end', { toolIds: abortedIds });
        }

        if (data.formattedTokens || data.formattedCost) {
            var parts = [];
            if (data.formattedTokens) parts.push(data.formattedTokens);
            if (data.formattedCost) parts.push(data.formattedCost);
            if (data.formattedDuration) parts.push(data.formattedDuration);
            usageInfo.textContent = parts.join(' | ');
            usageInfo.classList.remove('hidden');
        }

        // Update enhanced status bar fields
        if (data.formattedTokens) {
            statusTokens.textContent = data.formattedTokens;
            statusTokens.classList.remove('hidden');
        }
        if (data.formattedCost) {
            statusCost.textContent = data.formattedCost;
            statusCost.classList.remove('hidden');
        }

        // Append a clear "done" footer to the last assistant bubble so the user
        // has an obvious indication that the response is finished — the
        // status-bar update alone was too subtle.
        appendCompletionFooter(data);
    }

    /** Adds a small "✓ tokens · duration · cost" footer below the final
     *  assistant message. Idempotent — replaces any prior footer on that bubble. */
    function appendCompletionFooter(data) {
        var target = state.lastFinalAssistantEl;
        if (!target) return;

        // Remove any earlier footer (defensive, in case result fires twice).
        var prev = target.querySelector(':scope > .message-completion-footer');
        if (prev) prev.parentNode.removeChild(prev);

        var parts = [];
        if (data.formattedTokens)   parts.push(data.formattedTokens);
        if (data.formattedDuration) parts.push(data.formattedDuration);
        if (data.formattedCost)     parts.push(data.formattedCost);

        var footer = document.createElement('div');
        footer.className = 'message-completion-footer';
        footer.innerHTML =
            '<span class="message-completion-check">✓</span>' +
            '<span class="message-completion-text">' +
                (parts.length > 0 ? parts.join(' · ') : 'Done') +
            '</span>';
        target.appendChild(footer);
        state.lastFinalAssistantEl = null; // consume so the next turn's footer is fresh
    }

    function handlePermissionRequested(data) {
        state.pendingPermission = {
            toolUseId: data.toolUseId || null,
            requestId: data.requestId || null
        };

        permissionToolName.textContent = data.toolName || 'Tool Permission';
        permissionDescription.textContent = data.description || 'Claude wants to use a tool.';
        permissionBanner.classList.remove('hidden');
        // Trigger pulse animation to draw attention
        permissionBanner.classList.remove('pulse');
        void permissionBanner.offsetWidth; // force reflow
        permissionBanner.classList.add('pulse');
        scrollToBottom();
    }

    function handleExtendedThinkingStarted() {
        if (state.thinkingEl) return;

        hideWelcome();

        state.thinkingEl = document.createElement('div');
        state.thinkingEl.className = 'message message-assistant';

        var indicator = document.createElement('div');
        indicator.className = 'thinking-indicator';
        indicator.innerHTML =
            '<div class="thinking-dots"><span></span><span></span><span></span></div>' +
            '<span class="thinking-text">Thinking...</span>';

        state.thinkingEl.appendChild(indicator);
        messagesContainer.appendChild(state.thinkingEl);
        scrollToBottom();
    }

    function handleExtendedThinkingEnded() {
        removeThinkingIndicator();
    }

    function handleError(data) {
        removeLoadingIndicator();
        removeThinkingIndicator();
        // Reset to send-button state so the user can try again. Without
        // this the stop button stays visible after an error, leaving the
        // input "locked" until the user opens a new tab.
        setStreamingState(false);
        var el = document.createElement('div');
        el.className = 'message message-error';

        var contentEl = document.createElement('div');
        contentEl.className = 'message-content';
        contentEl.textContent = data.message || 'An error occurred.';
        el.appendChild(contentEl);

        messagesContainer.appendChild(el);
        scrollToBottom();
    }

    function handleConversationCleared() {
        // Remove all messages
        while (messagesContainer.firstChild) {
            messagesContainer.removeChild(messagesContainer.firstChild);
        }

        // Re-add welcome message
        var welcome = createWelcomeElement();
        messagesContainer.appendChild(welcome);

        state.currentAssistantEl = null;
        state.currentContentEl = null;
        state.streamingTextBuffer = '';
        state.hasMessages = false;
        state.thinkingEl = null;
        state.loadingEl = null;
        state.messageIndex = 0;
        clearAttachments();

        usageInfo.classList.add('hidden');
        sessionModel.classList.remove('visible');
        statusSessionId.classList.add('hidden');
        statusModel.classList.add('hidden');
        statusTokens.classList.add('hidden');
        statusCost.classList.add('hidden');
        hidePermissionBanner();
    }

    function handleGenerationStopped() {
        setStreamingState(false);
        removeLoadingIndicator();
        if (state.currentContentEl) {
            state.currentContentEl.classList.remove('streaming-cursor');
        }
        removeThinkingIndicator();
    }

    // ==================== UI Helpers ====================

    var MODE_LABELS = { 'default': 'Ask before edits', 'acceptEdits': 'Edit automatically', 'plan': 'Plan mode' };
    var MODE_ORDER = ['default', 'acceptEdits', 'plan'];

    function setMode(mode) {
        state.currentMode = mode;
        // Update toolbar label
        if (modeLabel) {
            modeLabel.textContent = MODE_LABELS[mode] || mode;
        }
        // Update checkmarks in popup
        if (modePopup) {
            var options = modePopup.querySelectorAll('.mode-option');
            for (var i = 0; i < options.length; i++) {
                var opt = options[i];
                var check = opt.querySelector('.mode-check');
                var isSelected = opt.getAttribute('data-mode') === mode;
                if (check) {
                    check.classList.toggle('hidden', !isSelected);
                }
                opt.classList.toggle('selected', isSelected);
            }
        }
    }

    var EFFORT_LABELS = { '': 'Auto', 'low': 'Low', 'medium': 'Medium', 'high': 'High', 'max': 'Max' };

    function setEffort(effort) {
        // Update dots
        var dots = document.querySelectorAll('#effort-dots .effort-dot');
        for (var i = 0; i < dots.length; i++) {
            dots[i].classList.toggle('active', dots[i].getAttribute('data-effort') === effort);
        }
        // Update label
        var levelText = document.getElementById('effort-level-text');
        if (levelText) {
            levelText.textContent = EFFORT_LABELS[effort] || 'Medium';
        }
    }

    function cycleMode() {
        var idx = MODE_ORDER.indexOf(state.currentMode);
        var nextIdx = (idx + 1) % MODE_ORDER.length;
        var nextMode = MODE_ORDER[nextIdx];
        setMode(nextMode);
        bridge.sendToJava('change_mode', { mode: nextMode });
    }

    function setStreamingState(streaming) {
        state.isStreaming = streaming;
        sendBtn.classList.toggle('hidden', streaming);
        stopBtn.classList.toggle('hidden', !streaming);
        if (headerStopBtn) { headerStopBtn.disabled = !streaming; }
        updateSendButton();
    }

    function hideWelcome() {
        if (!state.hasMessages && welcomeMessage) {
            welcomeMessage.style.display = 'none';
            state.hasMessages = true;
        }
    }

    function hidePermissionBanner() {
        permissionBanner.classList.add('hidden');
        state.pendingPermission = null;
    }

    function removeThinkingIndicator() {
        if (state.thinkingEl && state.thinkingEl.parentNode) {
            state.thinkingEl.parentNode.removeChild(state.thinkingEl);
        }
        state.thinkingEl = null;
    }

    function createWelcomeElement() {
        var el = document.createElement('div');
        el.id = 'welcome-message';
        el.innerHTML =
            '<div class="welcome-icon">' +
            '<svg width="32" height="32" viewBox="0 0 32 32" fill="none">' +
            '<path fill="var(--claude-orange)" d="M16 2L18.8 11.2C19 11.8 19.4 12.3 20 12.5L29 16L20 19.5C19.4 19.7 19 20.2 18.8 20.8L16 30L13.2 20.8C13 20.2 12.6 19.7 12 19.5L3 16L12 12.5C12.6 12.3 13 11.8 13.2 11.2L16 2Z"/>' +
            '</svg></div>' +
            '<h2>Claude Code</h2>' +
            '<p>Ask Claude to help with your code.<br>' +
            'Use <code>@</code> to reference files, <code>/</code> for commands.</p>';
        return el;
    }

    // ==================== Message Element Creation ====================

    function createMessageElement(role, data) {
        var el = document.createElement('div');
        el.className = 'message message-' + role;

        // Per-bubble timestamp — first child so it lives at the top
        // of the bubble. Hidden via CSS when SHOW_MESSAGE_TIMESTAMPS is off.
        if (data && data.timestamp) {
            el.appendChild(createTimeElement(data.timestamp));
        }

        var contentEl = document.createElement('div');
        contentEl.className = 'message-content';

        if (role === 'user') {
            // User messages: render as plain text (no markdown)
            var text = extractTextFromMessage(data);
            contentEl.textContent = text;
        } else if (role === 'assistant') {
            var text = extractTextFromMessage(data);
            if (text) {
                renderMarkdown(contentEl, text);
            }
        }

        // Apply RTL detection to message content
        applyRtlIfNeeded(contentEl);

        el.appendChild(contentEl);

        // Render any inline image segments below the text
        if (data.segments && data.segments.length) {
            for (var i = 0; i < data.segments.length; i++) {
                var seg = data.segments[i];
                if (seg && seg.type === 'image' && seg.bytes) {
                    el.appendChild(createImageElement(seg));
                }
            }
        }
        return el;
    }

    function createImageElement(seg) {
        var wrap = document.createElement('div');
        wrap.className = 'message-image';
        var img = document.createElement('img');
        var media = seg.mediaType || 'image/png';
        img.src = 'data:' + media + ';base64,' + seg.bytes;
        img.alt = seg.name || 'image';
        img.title = seg.name || '';
        // Open the full-size image in a new browser-tab-like view on click.
        img.addEventListener('click', function () {
            try {
                var w = window.open('', '_blank');
                if (w) {
                    w.document.write('<img src="' + img.src + '" style="max-width:100%">');
                }
            } catch (e) {}
        });
        wrap.appendChild(img);
        return wrap;
    }

    function extractTextFromMessage(data) {
        if (!data.segments) return '';
        var text = '';
        for (var i = 0; i < data.segments.length; i++) {
            if (data.segments[i].type === 'text') {
                text += data.segments[i].text || '';
            }
        }
        return text;
    }

    // ==================== Tool Call Rendering ====================

    function appendToolCallElement(parentEl, toolData) {
        // Dedup: when the CLI emits a snapshot assistant_message_started
        // AFTER the streaming tool_call_started, both paths call this
        // function for the same toolId. Without dedup we end up with two
        // DOM boxes — one driven by the live stream (gets updated to
        // Completed), one stuck in Running that gets Aborted by the
        // turn-end cleanup. If a box for this toolId already exists, just
        // upgrade its status fields rather than creating a duplicate.
        if (toolData.toolId) {
            var existing = findToolCallElement(toolData.toolId);
            if (existing) {
                upgradeToolCallElement(existing, toolData);
                return;
            }
        }
        var toolEl = document.createElement('div');
        toolEl.className = 'tool-call ' + (toolData.status || 'running');
        toolEl.setAttribute('data-tool-id', toolData.toolId || '');

        // Header
        var header = document.createElement('div');
        header.className = 'tool-call-header';
        header.addEventListener('click', function () {
            toolEl.classList.toggle('expanded');
        });

        // Chevron
        var chevron = document.createElement('div');
        chevron.className = 'tool-call-chevron';
        chevron.innerHTML = '<svg width="16" height="16" viewBox="0 0 16 16" fill="none">' +
            '<path d="M6 4l4 4-4 4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>';
        header.appendChild(chevron);

        // Status icon
        var iconEl = document.createElement('div');
        iconEl.className = 'tool-call-icon ' + (toolData.status || 'running');
        iconEl.innerHTML = getStatusIcon(toolData.status || 'running');
        header.appendChild(iconEl);

        // Tool name
        var nameEl = document.createElement('span');
        nameEl.className = 'tool-call-name';
        nameEl.textContent = toolData.displayName || toolData.toolName || 'Tool';
        header.appendChild(nameEl);

        // Summary
        var summaryEl = document.createElement('span');
        summaryEl.className = 'tool-call-summary';
        summaryEl.textContent = toolData.summary || '';
        header.appendChild(summaryEl);

        // Status badge
        var statusBadge = document.createElement('span');
        statusBadge.className = 'tool-call-status ' + (toolData.status || 'running');
        statusBadge.textContent = capitalizeFirst(toolData.status || 'running');
        header.appendChild(statusBadge);

        toolEl.appendChild(header);

        // Body (expandable)
        var body = document.createElement('div');
        body.className = 'tool-call-body';

        // Input section
        if (toolData.input) {
            var inputLabel = document.createElement('div');
            inputLabel.className = 'tool-call-section-label';
            inputLabel.textContent = 'Input';
            body.appendChild(inputLabel);

            var inputContent = document.createElement('div');
            inputContent.className = 'tool-call-section-content tool-input-content';
            inputContent.textContent = toolData.input;
            body.appendChild(inputContent);
        } else {
            // Create empty input area for streaming
            var inputLabel = document.createElement('div');
            inputLabel.className = 'tool-call-section-label';
            inputLabel.textContent = 'Input';
            body.appendChild(inputLabel);

            var inputContent = document.createElement('div');
            inputContent.className = 'tool-call-section-content tool-input-content';
            body.appendChild(inputContent);
        }

        // Output section (if already available)
        if (toolData.output) {
            var outputLabel = document.createElement('div');
            outputLabel.className = 'tool-call-section-label tool-output-label';
            outputLabel.textContent = 'Output';
            body.appendChild(outputLabel);

            var outputContent = document.createElement('div');
            outputContent.className = 'tool-call-section-content';
            outputContent.textContent = truncateOutput(toolData.output);
            body.appendChild(outputContent);
        }

        toolEl.appendChild(body);

        // Insert before the current content element if it exists, otherwise at the end
        if (state.currentContentEl && state.currentContentEl.parentNode === parentEl) {
            parentEl.insertBefore(toolEl, state.currentContentEl);
        } else {
            parentEl.appendChild(toolEl);
        }

        // Create a new content element after the tool call for subsequent text
        var newContentEl = document.createElement('div');
        newContentEl.className = 'message-content streaming-cursor';
        parentEl.appendChild(newContentEl);
        state.currentContentEl = newContentEl;
        state.streamingTextBuffer = '';
    }

    function findToolCallElement(toolId) {
        if (!toolId) return null;
        return messagesContainer.querySelector('.tool-call[data-tool-id="' + CSS.escape(toolId) + '"]');
    }

    /**
     * Update an existing tool-call DOM element with newer status data —
     * used when the CLI re-fires events for an already-rendered toolId
     * (e.g. snapshot assistant_message after live tool_call_started). Only
     * touches fields the new payload provides; preserves existing input/
     * output text so we don't wipe what the stream already filled in.
     */
    function upgradeToolCallElement(toolEl, toolData) {
        var status = toolData.status || 'running';
        // Status pill
        var statusEl = toolEl.querySelector('.tool-call-status');
        if (statusEl) {
            statusEl.className = 'tool-call-status ' + status;
            statusEl.textContent = capitalizeFirst(status);
        }
        // Status icon
        var iconEl = toolEl.querySelector('.tool-call-icon');
        if (iconEl) {
            iconEl.className = 'tool-call-icon ' + status;
            iconEl.innerHTML = getStatusIcon(status);
        }
        // Outer classes — drop running, add failed if applicable
        toolEl.classList.remove('running');
        toolEl.classList.remove('failed');
        if (status === 'failed') toolEl.classList.add('failed');
        // Summary upgrade (snapshot has the full command; live started
        // with an empty summary that got filled in by input_delta later).
        if (toolData.summary) {
            var summaryEl = toolEl.querySelector('.tool-call-summary');
            if (summaryEl) summaryEl.textContent = toolData.summary;
        }
        // Input upgrade — only fill if currently empty.
        if (toolData.input) {
            var inputContent = toolEl.querySelector('.tool-input-content');
            if (inputContent && !inputContent.textContent) {
                inputContent.textContent = toolData.input;
            }
        }
        // Output: add if we now have one and the body doesn't.
        if (toolData.output && !toolEl.querySelector('.tool-output-label')) {
            var body = toolEl.querySelector('.tool-call-body');
            if (body) {
                var outputLabel = document.createElement('div');
                outputLabel.className = 'tool-call-section-label tool-output-label';
                outputLabel.textContent = 'Output';
                body.appendChild(outputLabel);
                var outputContent = document.createElement('div');
                outputContent.className = 'tool-call-section-content';
                outputContent.textContent = truncateOutput(toolData.output);
                body.appendChild(outputContent);
            }
        }
    }

    function getStatusIcon(status) {
        switch (status) {
            case 'running':
                return '<svg width="16" height="16" viewBox="0 0 16 16" fill="none">' +
                    '<circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="1.5" stroke-dasharray="6 3"/></svg>';
            case 'completed':
                return '<svg width="16" height="16" viewBox="0 0 16 16" fill="none">' +
                    '<path d="M4 8l3 3 5-6" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>';
            case 'failed':
                return '<svg width="16" height="16" viewBox="0 0 16 16" fill="none">' +
                    '<path d="M5 5l6 6M11 5l-6 6" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>';
            default:
                return '';
        }
    }

    function truncateOutput(output) {
        if (!output) return '';
        var maxLen = 2000;
        if (output.length > maxLen) {
            return output.substring(0, maxLen) + '\n... (truncated)';
        }
        return output;
    }

    // ==================== Markdown Rendering ====================

    /**
     * Renders markdown text into an HTML element.
     * Supports: headers, bold, italic, inline code, code blocks, links, lists, blockquotes, horizontal rules.
     */
    function renderMarkdown(el, text) {
        el.innerHTML = markdownToHtml(text);

        // Add copy buttons to code blocks
        var codeBlocks = el.querySelectorAll('.code-block-wrapper');
        for (var i = 0; i < codeBlocks.length; i++) {
            setupCodeBlockCopy(codeBlocks[i]);
            setupCodeBlockApply(codeBlocks[i]);
            setupCodeBlockInsert(codeBlocks[i]);
        }

        // Apply RTL punctuation fixes
        applyRtlFixes(el);
    }

    function markdownToHtml(text) {
        if (!text) return '';

        // Split into lines for block-level processing
        var lines = text.split('\n');
        var html = '';
        var inCodeBlock = false;
        var codeBlockLang = '';
        var codeBlockContent = '';
        var inList = false;
        var listType = '';
        var inBlockquote = false;
        var blockquoteContent = '';

        for (var i = 0; i < lines.length; i++) {
            var line = lines[i];

            // Code block fence
            if (line.match(/^```/)) {
                if (inCodeBlock) {
                    // End code block
                    html += renderCodeBlock(codeBlockLang, codeBlockContent);
                    inCodeBlock = false;
                    codeBlockLang = '';
                    codeBlockContent = '';
                } else {
                    // Close any open list or blockquote
                    if (inList) {
                        html += '</' + listType + '>';
                        inList = false;
                    }
                    if (inBlockquote) {
                        html += '<blockquote>' + inlineMarkdown(blockquoteContent.trim()) + '</blockquote>';
                        inBlockquote = false;
                        blockquoteContent = '';
                    }
                    // Start code block
                    inCodeBlock = true;
                    codeBlockLang = line.substring(3).trim();
                }
                continue;
            }

            if (inCodeBlock) {
                codeBlockContent += (codeBlockContent ? '\n' : '') + line;
                continue;
            }

            // Blockquote
            if (line.match(/^>\s?/)) {
                if (inList) {
                    html += '</' + listType + '>';
                    inList = false;
                }
                inBlockquote = true;
                blockquoteContent += line.replace(/^>\s?/, '') + '\n';
                continue;
            } else if (inBlockquote) {
                html += '<blockquote>' + inlineMarkdown(blockquoteContent.trim()) + '</blockquote>';
                inBlockquote = false;
                blockquoteContent = '';
            }

            // Horizontal rule
            if (line.match(/^(\*{3,}|-{3,}|_{3,})\s*$/)) {
                if (inList) {
                    html += '</' + listType + '>';
                    inList = false;
                }
                html += '<hr>';
                continue;
            }

            // Headers
            var headerMatch = line.match(/^(#{1,6})\s+(.+)$/);
            if (headerMatch) {
                if (inList) {
                    html += '</' + listType + '>';
                    inList = false;
                }
                var level = headerMatch[1].length;
                html += '<h' + level + '>' + inlineMarkdown(headerMatch[2]) + '</h' + level + '>';
                continue;
            }

            // Unordered list
            var ulMatch = line.match(/^(\s*)[*\-+]\s+(.+)$/);
            if (ulMatch) {
                if (!inList || listType !== 'ul') {
                    if (inList) html += '</' + listType + '>';
                    html += '<ul>';
                    inList = true;
                    listType = 'ul';
                }
                html += '<li>' + inlineMarkdown(ulMatch[2]) + '</li>';
                continue;
            }

            // Ordered list
            var olMatch = line.match(/^(\s*)\d+\.\s+(.+)$/);
            if (olMatch) {
                if (!inList || listType !== 'ol') {
                    if (inList) html += '</' + listType + '>';
                    html += '<ol>';
                    inList = true;
                    listType = 'ol';
                }
                html += '<li>' + inlineMarkdown(olMatch[2]) + '</li>';
                continue;
            }

            // If we were in a list but this line is not a list item
            if (inList && line.trim() === '') {
                html += '</' + listType + '>';
                inList = false;
                continue;
            }

            if (inList && !ulMatch && !olMatch) {
                html += '</' + listType + '>';
                inList = false;
            }

            // Empty line
            if (line.trim() === '') {
                continue;
            }

            // Regular paragraph
            html += '<p>' + inlineMarkdown(line) + '</p>';
        }

        // Close any open blocks
        if (inCodeBlock) {
            html += renderCodeBlock(codeBlockLang, codeBlockContent);
        }
        if (inList) {
            html += '</' + listType + '>';
        }
        if (inBlockquote) {
            html += '<blockquote>' + inlineMarkdown(blockquoteContent.trim()) + '</blockquote>';
        }

        return html;
    }

    /**
     * Process inline markdown: bold, italic, code, links, strikethrough.
     */
    function inlineMarkdown(text) {
        if (!text) return '';

        // Escape HTML first (but preserve our own tags later)
        text = escapeHtml(text);

        // Inline code (must be before bold/italic to avoid conflicts)
        text = text.replace(/`([^`]+)`/g, '<code>$1</code>');

        // Bold + Italic
        text = text.replace(/\*\*\*(.+?)\*\*\*/g, '<strong><em>$1</em></strong>');
        text = text.replace(/___(.+?)___/g, '<strong><em>$1</em></strong>');

        // Bold
        text = text.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
        text = text.replace(/__(.+?)__/g, '<strong>$1</strong>');

        // Italic
        text = text.replace(/\*(.+?)\*/g, '<em>$1</em>');
        text = text.replace(/_(.+?)_/g, '<em>$1</em>');

        // Strikethrough
        text = text.replace(/~~(.+?)~~/g, '<del>$1</del>');

        // Links: [text](url)
        text = text.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');

        // Auto-link URLs
        text = text.replace(/(^|[^"=])(https?:\/\/[^\s<]+)/g, '$1<a href="$2" target="_blank" rel="noopener">$2</a>');

        return text;
    }

    function escapeHtml(text) {
        var div = document.createElement('div');
        div.appendChild(document.createTextNode(text));
        return div.innerHTML;
    }

    function renderCodeBlock(lang, code) {
        var escapedCode = escapeHtml(code);
        var langDisplay = lang || 'text';

        // Apply syntax highlighting if available
        var highlightedCode = escapedCode;
        if (window.syntaxHighlight && lang) {
            try {
                highlightedCode = window.syntaxHighlight.highlight(escapedCode, lang);
            } catch (e) {
                // Fallback to plain escaped code
                highlightedCode = escapedCode;
            }
        }

        return '<div class="code-block-wrapper">' +
            '<div class="code-block-header">' +
            '<span class="code-block-lang">' + escapeHtml(langDisplay) + '</span>' +
            '<div class="code-block-actions">' +
            '<button class="code-block-btn code-block-copy" data-code="' + escapedCode.replace(/"/g, '&quot;') + '">' +
            '<svg width="12" height="12" viewBox="0 0 16 16" fill="none">' +
            '<rect x="5" y="5" width="9" height="9" rx="1" stroke="currentColor" stroke-width="1.5"/>' +
            '<path d="M11 5V3a1 1 0 00-1-1H3a1 1 0 00-1 1v7a1 1 0 001 1h2" stroke="currentColor" stroke-width="1.5"/>' +
            '</svg> Copy</button>' +
            '<button class="code-block-btn code-block-apply" title="Apply to Editor">' +
            '<svg width="12" height="12" viewBox="0 0 16 16" fill="none">' +
            '<path d="M2 3h12M2 7h8M2 11h6" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>' +
            '<path d="M11 10l2 2 3-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>' +
            '</svg> Apply</button>' +
            '<button class="code-block-btn code-block-insert" title="Insert at Cursor">' +
            '<svg width="12" height="12" viewBox="0 0 16 16" fill="none">' +
            '<path d="M8 2v12M4 10l4 4 4-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>' +
            '</svg> Insert</button>' +
            '</div></div>' +
            '<pre><code class="hljs">' + highlightedCode + '</code></pre></div>';
    }

    function setupCodeBlockCopy(wrapper) {
        var copyBtn = wrapper.querySelector('.code-block-copy');
        if (!copyBtn) return;

        copyBtn.addEventListener('click', function (e) {
            e.stopPropagation();
            var codeEl = wrapper.querySelector('pre code');
            var text = codeEl ? codeEl.textContent : '';

            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(text).then(function () {
                    showCopied(copyBtn);
                }).catch(function () {
                    fallbackCopy(text, copyBtn);
                });
            } else {
                fallbackCopy(text, copyBtn);
            }
        });
    }

    function fallbackCopy(text, btn) {
        var textarea = document.createElement('textarea');
        textarea.value = text;
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.select();
        try {
            document.execCommand('copy');
            showCopied(btn);
        } catch (e) {
            console.error('Copy failed:', e);
        }
        document.body.removeChild(textarea);
    }

    function showCopied(btn) {
        btn.classList.add('copied');
        var originalHtml = btn.innerHTML;
        btn.innerHTML = '<svg width="12" height="12" viewBox="0 0 16 16" fill="none">' +
            '<path d="M4 8l3 3 5-6" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>' +
            '</svg> Copied!';
        setTimeout(function () {
            btn.classList.remove('copied');
            btn.innerHTML = originalHtml;
        }, 2000);
    }

    function setupCodeBlockApply(wrapper) {
        var applyBtn = wrapper.querySelector('.code-block-apply');
        if (!applyBtn) return;
        applyBtn.addEventListener('click', function (e) {
            e.stopPropagation();
            var codeEl = wrapper.querySelector('pre code');
            var text = codeEl ? codeEl.textContent : '';
            bridge.sendToJava('apply_to_editor', { code: text });
            showButtonFeedback(applyBtn, 'Applied!');
        });
    }

    function setupCodeBlockInsert(wrapper) {
        var insertBtn = wrapper.querySelector('.code-block-insert');
        if (!insertBtn) return;
        insertBtn.addEventListener('click', function (e) {
            e.stopPropagation();
            var codeEl = wrapper.querySelector('pre code');
            var text = codeEl ? codeEl.textContent : '';
            bridge.sendToJava('insert_at_cursor', { code: text });
            showButtonFeedback(insertBtn, 'Inserted!');
        });
    }

    function showButtonFeedback(btn, msg) {
        var originalHtml = btn.innerHTML;
        btn.innerHTML = '<svg width="12" height="12" viewBox="0 0 16 16" fill="none">' +
            '<path d="M4 8l3 3 5-6" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>' +
            '</svg> ' + msg;
        btn.classList.add('copied');
        setTimeout(function () {
            btn.classList.remove('copied');
            btn.innerHTML = originalHtml;
        }, 2000);
    }

    // ==================== System Messages ====================

    function handleSystemMessage(data) {
        hideWelcome();
        var el = document.createElement('div');
        el.className = 'message message-system';

        var contentEl = document.createElement('div');
        contentEl.className = 'message-content';
        if (data.text) {
            renderMarkdown(contentEl, data.text);
        }
        el.appendChild(contentEl);

        messagesContainer.appendChild(el);
        scrollToBottom();
    }

    // ==================== Slash Command Menu ====================

    function handleSlashInput() {
        var text = messageInput.value;
        // Show slash menu when input starts with / and has no spaces yet (typing a command)
        if (text.startsWith('/') && !text.includes(' ') && text.length <= 20) {
            var prefix = text.toLowerCase();
            var matches = [];
            for (var i = 0; i < SLASH_COMMANDS.length; i++) {
                if (SLASH_COMMANDS[i].name.indexOf(prefix) === 0) {
                    matches.push(SLASH_COMMANDS[i]);
                }
            }
            if (matches.length > 0) {
                showSlashMenu(matches);
            } else {
                hideSlashMenu();
            }
        } else {
            hideSlashMenu();
        }
    }

    function handleSlashSuggestions(data) {
        // Legacy handler for bridge-based suggestions (kept for backward compatibility)
        if (!data.suggestions || data.suggestions.length === 0) {
            hideSlashMenu();
            return;
        }
        showSlashMenu(data.suggestions);
    }

    function showSlashMenu(suggestions) {
        if (!slashMenuEl) {
            slashMenuEl = document.createElement('div');
            slashMenuEl.className = 'slash-menu';
            // Insert before the input area
            var inputArea = document.getElementById('input-area');
            if (inputArea && inputArea.parentNode) {
                inputArea.parentNode.insertBefore(slashMenuEl, inputArea);
            } else {
                document.body.appendChild(slashMenuEl);
            }
        }

        slashMenuItems = suggestions;
        slashMenuSelectedIndex = 0;

        var html = '';
        for (var i = 0; i < suggestions.length; i++) {
            var s = suggestions[i];
            html += '<div class="slash-menu-item' + (i === 0 ? ' selected' : '') + '" data-index="' + i + '">';
            html += '<span class="slash-menu-cmd">' + escapeHtml(s.name) + '</span>';
            html += '<span class="slash-menu-desc">' + escapeHtml(s.description) + '</span>';
            if (s.hasSubOptions) {
                html += '<span class="slash-menu-badge">›</span>';
            } else if (s.local) {
                html += '<span class="slash-menu-badge">plugin</span>';
            }
            html += '</div>';
        }

        slashMenuEl.innerHTML = html;
        slashMenuEl.style.display = 'block';

        // Add click handlers
        var items = slashMenuEl.querySelectorAll('.slash-menu-item');
        for (var j = 0; j < items.length; j++) {
            (function (idx) {
                items[idx].addEventListener('click', function () {
                    selectSlashMenuItem(idx);
                });
                items[idx].addEventListener('mouseenter', function () {
                    slashMenuSelectedIndex = idx;
                    updateSlashMenuSelection();
                });
            })(j);
        }
    }

    function hideSlashMenu() {
        if (slashMenuEl) {
            slashMenuEl.style.display = 'none';
        }
        slashMenuSelectedIndex = -1;
    }

    function updateSlashMenuSelection() {
        if (!slashMenuEl) return;
        var items = slashMenuEl.querySelectorAll('.slash-menu-item');
        for (var i = 0; i < items.length; i++) {
            if (i === slashMenuSelectedIndex) {
                items[i].classList.add('selected');
            } else {
                items[i].classList.remove('selected');
            }
        }
    }

    function selectSlashMenuItem(index) {
        if (index < 0 || index >= slashMenuItems.length) return;
        var cmd = slashMenuItems[index];
        messageInput.value = '';
        messageInput.focus();
        hideSlashMenu();
        autoResizeTextarea();
        updateSendButton();

        // If command has local sub-options, show the picker directly (no bridge round-trip)
        var localSubOptions = COMMAND_SUB_OPTIONS[cmd.name];
        if (localSubOptions && localSubOptions.length > 0) {
            commandPickerCommand = cmd.name;
            showCommandPicker(localSubOptions);
            return;
        }

        // Otherwise send to Java for execution
        bridge.sendToJava('execute_slash_command', { command: cmd.name });
    }

    // ==================== Command Sub-Picker ====================

    var commandPickerEl = null;
    var commandPickerSelectedIndex = -1;
    var commandPickerItems = [];
    var commandPickerCommand = '';

    function handleCommandPickerOptions(data) {
        if (!data.options || data.options.length === 0) return;
        commandPickerCommand = data.command || '';
        showCommandPicker(data.options);
    }

    function showCommandPicker(options) {
        if (!commandPickerEl) {
            commandPickerEl = document.createElement('div');
            commandPickerEl.className = 'slash-menu command-picker';
            var inputArea = document.getElementById('input-area');
            if (inputArea && inputArea.parentNode) {
                inputArea.parentNode.insertBefore(commandPickerEl, inputArea);
            } else {
                document.body.appendChild(commandPickerEl);
            }
        }

        commandPickerItems = options;
        commandPickerSelectedIndex = 0;

        var html = '<div class="command-picker-header">' + escapeHtml(commandPickerCommand) + '</div>';
        for (var i = 0; i < options.length; i++) {
            var opt = options[i];
            html += '<div class="slash-menu-item' + (i === 0 ? ' selected' : '') + '" data-index="' + i + '">';
            html += '<span class="slash-menu-cmd">' + escapeHtml(opt.label) + '</span>';
            html += '<span class="slash-menu-desc">' + escapeHtml(opt.description) + '</span>';
            html += '</div>';
        }

        commandPickerEl.innerHTML = html;
        commandPickerEl.style.display = 'block';

        // Add click/hover handlers
        var items = commandPickerEl.querySelectorAll('.slash-menu-item');
        for (var j = 0; j < items.length; j++) {
            (function (idx) {
                items[idx].addEventListener('click', function () {
                    selectCommandPickerItem(idx);
                });
                items[idx].addEventListener('mouseenter', function () {
                    commandPickerSelectedIndex = idx;
                    updateCommandPickerSelection();
                });
            })(j);
        }

        messageInput.focus();
    }

    function hideCommandPicker() {
        if (commandPickerEl) {
            commandPickerEl.style.display = 'none';
        }
        commandPickerSelectedIndex = -1;
        commandPickerItems = [];
        commandPickerCommand = '';
    }

    function updateCommandPickerSelection() {
        if (!commandPickerEl) return;
        var items = commandPickerEl.querySelectorAll('.slash-menu-item');
        for (var i = 0; i < items.length; i++) {
            items[i].classList.toggle('selected', i === commandPickerSelectedIndex);
        }
    }

    function selectCommandPickerItem(index) {
        if (index < 0 || index >= commandPickerItems.length) return;
        var opt = commandPickerItems[index];
        var cmd = commandPickerCommand; // save before hideCommandPicker resets it
        hideCommandPicker();
        // Send as full slash command via send_message (e.g. "/model sonnet")
        // This ensures it goes through the robust handleSendMessage -> handleLocalSlashCommand path
        var fullCommand = cmd + ' ' + opt.value;
        bridge.sendToJava('send_message', { message: fullCommand });
    }

    // ==================== @File Mention Menu ====================

    function handleAtMentionInput() {
        var text = messageInput.value;
        var cursorPos = messageInput.selectionStart;

        // Find the @ that precedes the cursor
        var atIdx = -1;
        for (var i = cursorPos - 1; i >= 0; i--) {
            var ch = text.charAt(i);
            if (ch === '@') {
                atIdx = i;
                break;
            }
            if (ch === ' ' || ch === '\n') break;
        }

        if (atIdx >= 0) {
            var query = text.substring(atIdx + 1, cursorPos);
            // Only trigger if query is reasonable (1-60 chars, no spaces at start)
            if (query.length >= 0 && query.length <= 60 && !/^\s/.test(query)) {
                fileMenuAtPos = atIdx;
                bridge.sendToJava('file_search', { query: query });
                return;
            }
        }
        hideFileMenu();
    }

    function handleFileSuggestions(data) {
        if (!data.files || data.files.length === 0) {
            hideFileMenu();
            return;
        }
        showFileMenu(data.files);
    }

    function showFileMenu(files) {
        if (!fileMenuEl) {
            fileMenuEl = document.createElement('div');
            fileMenuEl.className = 'slash-menu file-menu';
            var inputArea = document.getElementById('input-area');
            if (inputArea && inputArea.parentNode) {
                inputArea.parentNode.insertBefore(fileMenuEl, inputArea);
            } else {
                document.body.appendChild(fileMenuEl);
            }
        }

        fileMenuItems = files;
        fileMenuSelectedIndex = 0;

        var html = '';
        for (var i = 0; i < files.length; i++) {
            var f = files[i];
            html += '<div class="slash-menu-item' + (i === 0 ? ' selected' : '') + '" data-index="' + i + '">';
            html += '<span class="file-menu-icon">📄</span>';
            html += '<span class="file-menu-name">' + escapeHtml(f.name) + '</span>';
            html += '<span class="file-menu-path">' + escapeHtml(f.relativePath || '') + '</span>';
            html += '</div>';
        }

        fileMenuEl.innerHTML = html;
        fileMenuEl.style.display = 'block';

        var items = fileMenuEl.querySelectorAll('.slash-menu-item');
        for (var j = 0; j < items.length; j++) {
            (function (idx) {
                items[idx].addEventListener('click', function () {
                    selectFileMenuItem(idx);
                });
                items[idx].addEventListener('mouseenter', function () {
                    fileMenuSelectedIndex = idx;
                    updateFileMenuSelection();
                });
            })(j);
        }
    }

    function hideFileMenu() {
        if (fileMenuEl) {
            fileMenuEl.style.display = 'none';
        }
        fileMenuSelectedIndex = -1;
        fileMenuAtPos = -1;
    }

    function updateFileMenuSelection() {
        if (!fileMenuEl) return;
        var items = fileMenuEl.querySelectorAll('.slash-menu-item');
        for (var i = 0; i < items.length; i++) {
            items[i].classList.toggle('selected', i === fileMenuSelectedIndex);
        }
    }

    function selectFileMenuItem(index) {
        if (index < 0 || index >= fileMenuItems.length) return;
        var file = fileMenuItems[index];
        var text = messageInput.value;
        var cursorPos = messageInput.selectionStart;

        // Replace from @ position to cursor with @filepath
        var before = text.substring(0, fileMenuAtPos);
        var after = text.substring(cursorPos);
        var mention = '@' + file.relativePath + ' ';
        messageInput.value = before + mention + after;
        messageInput.selectionStart = messageInput.selectionEnd = before.length + mention.length;
        messageInput.focus();
        hideFileMenu();
        autoResizeTextarea();
        updateSendButton();
    }

    // ==================== Popup Menu Navigation Abstraction ====================

    function getActivePopupMenu() {
        if (commandPickerEl && commandPickerEl.style.display !== 'none') {
            return {
                selectNext: function () {
                    commandPickerSelectedIndex = Math.min(commandPickerSelectedIndex + 1, commandPickerItems.length - 1);
                    updateCommandPickerSelection();
                },
                selectPrev: function () {
                    commandPickerSelectedIndex = Math.max(commandPickerSelectedIndex - 1, 0);
                    updateCommandPickerSelection();
                },
                getSelectedIndex: function () { return commandPickerSelectedIndex; },
                selectCurrent: function () { selectCommandPickerItem(commandPickerSelectedIndex); },
                hide: function () { hideCommandPicker(); }
            };
        }
        if (fileMenuEl && fileMenuEl.style.display !== 'none') {
            return {
                selectNext: function () {
                    fileMenuSelectedIndex = Math.min(fileMenuSelectedIndex + 1, fileMenuItems.length - 1);
                    updateFileMenuSelection();
                },
                selectPrev: function () {
                    fileMenuSelectedIndex = Math.max(fileMenuSelectedIndex - 1, 0);
                    updateFileMenuSelection();
                },
                getSelectedIndex: function () { return fileMenuSelectedIndex; },
                selectCurrent: function () { selectFileMenuItem(fileMenuSelectedIndex); },
                hide: function () { hideFileMenu(); }
            };
        }
        if (slashMenuEl && slashMenuEl.style.display !== 'none') {
            return {
                selectNext: function () {
                    slashMenuSelectedIndex = Math.min(slashMenuSelectedIndex + 1, slashMenuItems.length - 1);
                    updateSlashMenuSelection();
                },
                selectPrev: function () {
                    slashMenuSelectedIndex = Math.max(slashMenuSelectedIndex - 1, 0);
                    updateSlashMenuSelection();
                },
                getSelectedIndex: function () { return slashMenuSelectedIndex; },
                selectCurrent: function () { selectSlashMenuItem(slashMenuSelectedIndex); },
                hide: function () { hideSlashMenu(); }
            };
        }
        return null;
    }

    // ==================== Loading Indicator ====================

    function showLoadingIndicator() {
        if (state.loadingEl) return;

        state.loadingEl = document.createElement('div');
        state.loadingEl.className = 'message message-assistant';

        // Reuse the extended-thinking dots animation so the "while we wait
        // for the first chunk" period has the same visual language as the
        // explicit thinking phase. More noticeable than the small spinner
        // and the wording matches what users expect from chat AI UIs.
        var indicator = document.createElement('div');
        indicator.className = 'thinking-indicator';
        indicator.innerHTML =
            '<div class="thinking-dots"><span></span><span></span><span></span></div>' +
            '<span class="thinking-text">Thinking...</span>';

        state.loadingEl.appendChild(indicator);
        messagesContainer.appendChild(state.loadingEl);
        scrollToBottom();
    }

    function removeLoadingIndicator() {
        if (state.loadingEl && state.loadingEl.parentNode) {
            state.loadingEl.parentNode.removeChild(state.loadingEl);
        }
        state.loadingEl = null;
    }

    // ==================== Drag & Drop / Image Attachments ====================

    // ==================== Status Bar Toast ====================

    function showToast(message, durationMs) {
        durationMs = durationMs || 3000;
        var existing = document.querySelector('.status-toast');
        if (existing) existing.remove();

        var toast = document.createElement('div');
        toast.className = 'status-toast';
        toast.textContent = message;
        toast.style.animationDuration = '200ms, 200ms';
        toast.style.animationDelay = '0ms, ' + (durationMs - 200) + 'ms';
        document.getElementById('root').appendChild(toast);

        setTimeout(function () {
            if (toast.parentNode) toast.parentNode.removeChild(toast);
        }, durationMs);
    }

    // Expose for Java bridge
    window.__showToast = showToast;

    // ==================== RTL Punctuation Correction ====================

    var RTL_REGEX = /[\u0590-\u05FF\u0600-\u06FF\u0700-\u074F]/;
    var LEADING_PUNCT_REGEX = /^([.,:;!?\-–—'"»«\)\]\}>]+)(.+)/;

    /**
     * Detects if text starts with RTL characters.
     */
    function isRtlText(text) {
        if (!text) return false;
        // Find first letter character
        for (var i = 0; i < text.length && i < 50; i++) {
            if (RTL_REGEX.test(text[i])) return true;
            if (/[a-zA-Z]/.test(text[i])) return false;
        }
        return false;
    }

    /**
     * Moves leading punctuation to the end for RTL text display.
     * E.g., ".שלום" → "שלום."
     */
    function fixRtlPunctuation(text) {
        if (!text || !isRtlText(text)) return text;
        var match = text.match(LEADING_PUNCT_REGEX);
        if (match) {
            return match[2] + match[1];
        }
        return text;
    }

    /**
     * Applies RTL fix to all message content elements.
     */
    function applyRtlFixes(el) {
        if (!el) return;
        var paragraphs = el.querySelectorAll('p, li, span, div');
        for (var i = 0; i < paragraphs.length; i++) {
            var p = paragraphs[i];
            if (p.children.length === 0 && p.textContent) {
                var fixed = fixRtlPunctuation(p.textContent);
                if (fixed !== p.textContent) {
                    p.textContent = fixed;
                }
                // Set dir=auto for proper RTL alignment
                if (isRtlText(p.textContent)) {
                    p.setAttribute('dir', 'auto');
                }
            }
        }
    }

    // ==================== File Attachment Chip Management ====================

    function handleAttachmentsUpdated(data) {
        lastAttachments = (data && data.attachments) ? data.attachments : [];
        renderAllChips();
    }

    function handleAttachmentsCleared() {
        lastAttachments = [];
        renderAllChips();
    }

    /**
     * Re-renders both the auto-pinned active-file chip and any user-attached
     * @-mention chips into #file-chips. Active file chip is always first and
     * styled with a small "current file" affordance (Amazon Q parity).
     */
    function renderAllChips() {
        var container = document.getElementById('file-chips');
        if (!container) return;
        container.innerHTML = '';

        var hasActive = activeFile.name && !activeFile.dismissed;
        var hasAttachments = lastAttachments && lastAttachments.length > 0;

        if (!hasActive && !hasAttachments) {
            container.classList.add('hidden');
            return;
        }
        container.classList.remove('hidden');

        // Auto-pinned active file chip — first, with a distinguishing class.
        if (hasActive) {
            var chip = document.createElement('span');
            chip.className = 'file-chip file-chip-active';
            chip.title = activeFile.path || activeFile.name;

            var icon = document.createElement('span');
            icon.className = 'file-chip-icon';
            icon.textContent = '📄';
            chip.appendChild(icon);

            var nameSpan = document.createElement('span');
            nameSpan.className = 'file-chip-name';
            nameSpan.textContent = activeFile.name;
            chip.appendChild(nameSpan);

            var removeBtn = document.createElement('button');
            removeBtn.className = 'file-chip-remove';
            removeBtn.textContent = '×';
            removeBtn.title = 'Remove for this message';
            removeBtn.addEventListener('click', function () {
                activeFile.dismissed = true;
                renderAllChips();
            });
            chip.appendChild(removeBtn);
            container.appendChild(chip);
        }

        // User-attached files via @-mention or attach button — same as before.
        for (var i = 0; i < lastAttachments.length; i++) {
            var c = document.createElement('span');
            c.className = 'file-chip';
            var ns = document.createElement('span');
            ns.className = 'file-chip-name';
            ns.textContent = lastAttachments[i].label;
            ns.title = lastAttachments[i].path;
            c.appendChild(ns);
            var rb = document.createElement('button');
            rb.className = 'file-chip-remove';
            rb.textContent = '×';
            rb.setAttribute('data-index', i);
            rb.addEventListener('click', function () {
                var idx = parseInt(this.getAttribute('data-index'), 10);
                bridge.sendToJava('remove_attachment', { index: idx });
            });
            c.appendChild(rb);
            container.appendChild(c);
        }
    }

    // Wire attach button
    var attachBtn = document.getElementById('attach-btn');
    if (attachBtn) {
        attachBtn.addEventListener('click', function () {
            bridge.sendToJava('attach_file_dialog', {});
        });
    }

    function setupDragAndDrop() {
        var root = document.getElementById('root');
        var dragCounter = 0;

        root.addEventListener('dragenter', function (e) {
            e.preventDefault();
            e.stopPropagation();
            dragCounter++;
            if (hasImageFiles(e)) {
                dropOverlay.classList.remove('hidden');
            }
        });

        root.addEventListener('dragleave', function (e) {
            e.preventDefault();
            e.stopPropagation();
            dragCounter--;
            if (dragCounter <= 0) {
                dragCounter = 0;
                dropOverlay.classList.add('hidden');
            }
        });

        root.addEventListener('dragover', function (e) {
            e.preventDefault();
            e.stopPropagation();
        });

        root.addEventListener('drop', function (e) {
            e.preventDefault();
            e.stopPropagation();
            dragCounter = 0;
            dropOverlay.classList.add('hidden');

            var files = e.dataTransfer.files;
            for (var i = 0; i < files.length; i++) {
                if (files[i].type.startsWith('image/')) {
                    addImageAttachment(files[i]);
                }
            }
        });

        // Also handle paste (Ctrl+V image paste)
        messageInput.addEventListener('paste', function (e) {
            var items = (e.clipboardData || e.originalEvent.clipboardData).items;
            for (var i = 0; i < items.length; i++) {
                if (items[i].type.startsWith('image/')) {
                    var file = items[i].getAsFile();
                    if (file) {
                        addImageAttachment(file);
                        e.preventDefault();
                    }
                }
            }
        });
    }

    function hasImageFiles(e) {
        if (e.dataTransfer && e.dataTransfer.types) {
            for (var i = 0; i < e.dataTransfer.types.length; i++) {
                if (e.dataTransfer.types[i] === 'Files') return true;
            }
        }
        return false;
    }

    /**
     * Handles a Ctrl/Cmd+V from Java (the IDE intercepts the shortcut before
     * it reaches the webview). Java forwards either an image (base64 PNG) or
     * plain text; we route into the same place the in-webview paste handler
     * would have, so behavior is identical to a webview-native paste.
     */
    function handlePasteFromClipboard(data) {
        if (!data) return;
        if (data.kind === 'image' && data.bytes) {
            // Convert base64 to a Blob -> File so we reuse addImageAttachment().
            try {
                var binary = atob(data.bytes);
                var len = binary.length;
                var bytes = new Uint8Array(len);
                for (var i = 0; i < len; i++) bytes[i] = binary.charCodeAt(i);
                var blob = new Blob([bytes], { type: data.mediaType || 'image/png' });
                var file = new File([blob], 'clipboard.png', { type: data.mediaType || 'image/png' });
                addImageAttachment(file);
                if (messageInput) messageInput.focus();
            } catch (e) {
                console.warn('paste image decode failed', e);
            }
            return;
        }
        if (data.kind === 'text' && typeof data.text === 'string') {
            // Insert at the current caret position in the textarea.
            if (!messageInput) return;
            messageInput.focus();
            var start = messageInput.selectionStart || 0;
            var end = messageInput.selectionEnd || 0;
            var v = messageInput.value;
            messageInput.value = v.slice(0, start) + data.text + v.slice(end);
            var caret = start + data.text.length;
            messageInput.selectionStart = messageInput.selectionEnd = caret;
            // Re-run the size/state hooks the native paste event would trigger.
            autoResizeTextarea();
            updateSendButton();
        }
    }

    function addImageAttachment(file) {
        var reader = new FileReader();
        reader.onload = function (e) {
            var dataUrl = e.target.result;
            // Extract base64 data (remove data:image/xxx;base64, prefix)
            var base64 = dataUrl.split(',')[1];
            state.attachedImages.push({ dataUrl: dataUrl, bytes: base64 });
            updateAttachmentPreview();
            updateSendButton();
        };
        reader.readAsDataURL(file);
    }

    function removeAttachment(index) {
        state.attachedImages.splice(index, 1);
        updateAttachmentPreview();
        updateSendButton();
    }

    function clearAttachments() {
        state.attachedImages = [];
        updateAttachmentPreview();
    }

    function updateAttachmentPreview() {
        var existing = document.querySelector('.attachment-preview-bar');
        if (existing) existing.remove();

        if (state.attachedImages.length === 0) return;

        var bar = document.createElement('div');
        bar.className = 'attachment-preview-bar';

        for (var i = 0; i < state.attachedImages.length; i++) {
            (function (idx) {
                var thumb = document.createElement('div');
                thumb.className = 'attachment-thumb';
                var img = document.createElement('img');
                img.src = state.attachedImages[idx].dataUrl;
                thumb.appendChild(img);

                var removeBtn = document.createElement('button');
                removeBtn.className = 'remove-attachment';
                removeBtn.textContent = '×';
                removeBtn.addEventListener('click', function () {
                    removeAttachment(idx);
                });
                thumb.appendChild(removeBtn);

                bar.appendChild(thumb);
            })(i);
        }

        var inputArea = document.getElementById('input-area');
        inputArea.insertBefore(bar, inputArea.firstChild);
    }

    // ==================== Utility ====================

    function capitalizeFirst(str) {
        if (!str) return '';
        return str.charAt(0).toUpperCase() + str.slice(1);
    }

    // ==================== Start ====================

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

})();
