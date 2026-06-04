package com.anthropic.eclipse.claude.views.webview;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.widgets.Display;

import com.anthropic.eclipse.claude.util.JsonParser;

/**
 * Java <-> JavaScript bridge for the embedded SWT Browser hosting the
 * chat webview. SWT equivalent of the IntelliJ plugin's
 * {@code WebviewBridge.java} which uses JBCefJSQuery.
 *
 * <p>JS -> Java: a {@link BrowserFunction} named {@code __sendToJava} is
 * exposed on the page's {@code window}. The webview's {@code bridge.js}
 * calls it with a JSON string {@code {"type":"...","data":{...}}}, this
 * class parses it and dispatches to the registered {@link #setMessageHandler
 * message handler}.
 *
 * <p>Java -> JS: {@link #sendToWebview(String, String)} composes a script
 * snippet that calls {@code window.receiveFromJava(type, data)} and
 * executes it via {@link Browser#execute(String)}. The webview's
 * {@code bridge.js} then dispatches the event to its registered listeners.
 */
public class WebviewBridge {

    private final Browser browser;
    private BrowserFunction sendToJavaFn;
    private BiConsumer<String, String> messageHandler;
    private volatile boolean disposed;

    /**
     * Wires up the bridge against the given Browser. The Browser must
     * already exist; this class registers a {@link BrowserFunction} on it.
     * The BrowserFunction is registered BEFORE the page loads so it's
     * available immediately when bridge.js runs. On SWT.EDGE specifically,
     * navigating to a new URL can drop pre-registered BrowserFunctions —
     * call {@link #rebindBrowserFunction()} from the Browser's
     * ProgressListener.completed callback to re-register after page load.
     */
    public WebviewBridge(Browser browser) {
        this.browser = browser;
        registerBrowserFunction();
    }

    private void registerBrowserFunction() {
        // Dispose stale BrowserFunction first (defensive — SWT may already
        // have cleared it during a page navigation).
        if (sendToJavaFn != null) {
            try {
                if (!sendToJavaFn.isDisposed()) sendToJavaFn.dispose();
            } catch (Exception ignored) {}
        }
        this.sendToJavaFn = new BrowserFunction(browser, "__sendToJava") {
            @Override
            public Object function(Object[] args) {
                if (disposed) return null;
                try {
                    if (args == null || args.length == 0) return null;
                    Object first = args[0];
                    String request = (first instanceof String) ? (String) first : String.valueOf(first);
                    handleIncomingMessage(request);
                } catch (Exception e) {
                    System.err.println("[WebviewBridge] Error handling JS message: " + e.getMessage());
                    e.printStackTrace();
                }
                return null;
            }
        };
    }

    /**
     * Re-register the {@code __sendToJava} BrowserFunction. SWT.EDGE
     * (WebView2 on Windows) occasionally drops BrowserFunctions that were
     * registered before {@link Browser#setUrl(String)} loaded the page —
     * the function exists on the Java side but {@code window.__sendToJava}
     * is undefined in JS, so {@code bridge.sendToJava(...)} silently
     * queues into {@code pendingMessages} and nothing reaches Java. Calling
     * this from {@code ProgressListener.completed} fixes it.
     */
    public void rebindBrowserFunction() {
        if (disposed) return;
        if (browser == null || browser.isDisposed()) return;
        registerBrowserFunction();
    }

    /**
     * Set the consumer that handles incoming JS->Java messages.
     * Called with {@code (type, dataJson)} where {@code dataJson} is the
     * raw JSON string of the {@code data} field.
     */
    public void setMessageHandler(BiConsumer<String, String> messageHandler) {
        this.messageHandler = messageHandler;
    }

    /**
     * Push a message from Java to JS. Composes:
     * {@code if (window.receiveFromJava) window.receiveFromJava('<type>', <data-json>);}
     * and executes it on the browser. {@code jsonData} is embedded as-is so
     * it must be a syntactically valid JS literal — typically a JSON object
     * produced by {@link JsonBuilder}.
     *
     * <p>Safe to call from any thread; it marshals onto the UI thread.
     * No-ops if the browser is disposed.
     */
    public void sendToWebview(String type, String jsonData) {
        if (disposed) return;
        if (browser == null || browser.isDisposed()) return;
        Display display = browser.getDisplay();
        if (display == null || display.isDisposed()) return;

        String escapedType = JsonBuilder.jsonString(type);
        // jsonData is assumed to be a valid JSON literal (object).
        final String script =
            "if (window.receiveFromJava) { window.receiveFromJava(" + escapedType + ", " + jsonData + "); }";

        if (display.getThread() == Thread.currentThread()) {
            executeSafely(script);
        } else {
            display.asyncExec(() -> executeSafely(script));
        }
    }

    /**
     * Notify the JS bridge that the Java side is ready (flushes any queued
     * pending messages on the JS side). Call this once, after the page has
     * finished loading.
     */
    public void notifyBridgeReady() {
        if (disposed) return;
        Display display = browser.getDisplay();
        if (display == null) return;
        Runnable r = () -> executeSafely("if (window.__onBridgeReady) { window.__onBridgeReady(); }");
        if (display.getThread() == Thread.currentThread()) r.run();
        else display.asyncExec(r);
    }

    private void executeSafely(String script) {
        if (browser == null || browser.isDisposed()) return;
        try {
            browser.execute(script);
        } catch (Exception e) {
            System.err.println("[WebviewBridge] browser.execute failed: " + e.getMessage());
        }
    }

    private void handleIncomingMessage(String request) {
        if (request == null || request.isEmpty()) return;
        try {
            Map<String, Object> message = JsonParser.parseObject(request);
            if (message == null) return;
            Object typeObj = message.get("type");
            if (!(typeObj instanceof String)) {
                System.err.println("[WebviewBridge] missing/invalid type in: " + truncate(request));
                return;
            }
            String type = (String) typeObj;
            Object dataObj = message.get("data");
            String dataJson;
            if (dataObj == null) {
                dataJson = "{}";
            } else {
                // Re-serialize the data portion. JsonParser already gave us
                // a Map/List/scalar; serialize back to a JSON string.
                dataJson = toJson(dataObj);
            }
            if (messageHandler != null) {
                messageHandler.accept(type, dataJson);
            }
        } catch (Exception e) {
            System.err.println("[WebviewBridge] parse error: " + e.getMessage() + " in: " + truncate(request));
        }
    }

    /**
     * Minimal Object -> JSON serializer for Map / List / String / Number / Boolean / null.
     * Avoids pulling in a JSON library; used only on the JS->Java path where
     * messages are small.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String) return JsonBuilder.jsonString((String) obj);
        if (obj instanceof Boolean || obj instanceof Number) return obj.toString();
        if (obj instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) obj).entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(JsonBuilder.jsonString(String.valueOf(e.getKey()))).append(":").append(toJson(e.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        if (obj instanceof Iterable) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object o : (Iterable<?>) obj) {
                if (!first) sb.append(",");
                first = false;
                sb.append(toJson(o));
            }
            sb.append("]");
            return sb.toString();
        }
        return JsonBuilder.jsonString(obj.toString());
    }

    public void dispose() {
        disposed = true;
        try {
            if (sendToJavaFn != null && !sendToJavaFn.isDisposed()) {
                sendToJavaFn.dispose();
            }
        } catch (Exception ignored) {}
    }

    private static String truncate(String s) {
        if (s == null) return "null";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> emptyMap() {
        return new HashMap<>();
    }
}
