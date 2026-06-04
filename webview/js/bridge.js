/**
 * Bridge module for Java <-> JavaScript communication in the Claude Eclipse webview.
 *
 * JS -> Java: sendToJava(type, data) serializes a JSON message and calls
 *             window.__sendToJava() which is registered by WebviewBridge.java
 *             as an SWT BrowserFunction (or by JBCefJSQuery in the IntelliJ variant).
 *
 * Java -> JS: Java calls window.receiveFromJava(type, data) via browser.execute().
 *             The bridge dispatches this to registered handlers via the event system.
 */
(function () {
    'use strict';

    var listeners = {};
    var pendingMessages = [];
    var bridgeReady = false;

    /**
     * Register an event listener for a specific message type.
     * @param {string} type - The message type to listen for
     * @param {function} callback - Handler function receiving (data)
     */
    function on(type, callback) {
        if (!listeners[type]) {
            listeners[type] = [];
        }
        listeners[type].push(callback);
    }

    /**
     * Remove an event listener.
     * @param {string} type - The message type
     * @param {function} callback - The handler to remove
     */
    function off(type, callback) {
        if (!listeners[type]) return;
        listeners[type] = listeners[type].filter(function (cb) {
            return cb !== callback;
        });
    }

    /**
     * Emit an event to all registered listeners for the given type.
     * @param {string} type - The message type
     * @param {*} data - The event data
     */
    function emit(type, data) {
        var handlers = listeners[type];
        if (!handlers) return;
        for (var i = 0; i < handlers.length; i++) {
            try {
                handlers[i](data);
            } catch (e) {
                console.error('[Bridge] Error in handler for "' + type + '":', e);
            }
        }
    }

    /**
     * Send a message from JavaScript to Java.
     * @param {string} type - The message type
     * @param {object} [data] - Optional data payload
     */
    function sendToJava(type, data) {
        var message = JSON.stringify({
            type: type,
            data: data || {}
        });

        if (window.__sendToJava) {
            try {
                window.__sendToJava(message);
            } catch (e) {
                console.error('[Bridge] Error sending to Java:', e);
            }
        } else {
            // Queue messages until the bridge is injected
            pendingMessages.push(message);
        }
    }

    /**
     * Called by Java (via executeJavaScript) to send messages into the webview.
     * @param {string} type - The message type
     * @param {*} data - The data payload (already parsed from JSON by the JS engine)
     */
    function receiveFromJava(type, data) {
        emit(type, data);
    }

    /**
     * Called when the Java bridge function is injected.
     * Flushes any pending messages that were queued before the bridge was ready.
     */
    function onBridgeReady() {
        bridgeReady = true;
        if (pendingMessages.length > 0 && window.__sendToJava) {
            for (var i = 0; i < pendingMessages.length; i++) {
                try {
                    window.__sendToJava(pendingMessages[i]);
                } catch (e) {
                    console.error('[Bridge] Error flushing pending message:', e);
                }
            }
            pendingMessages = [];
        }
    }

    // Expose the public API
    window.bridge = {
        on: on,
        off: off,
        emit: emit,
        sendToJava: sendToJava
    };

    // Expose the Java->JS entry point and bridge ready callback
    window.receiveFromJava = receiveFromJava;
    window.__onBridgeReady = onBridgeReady;

})();
