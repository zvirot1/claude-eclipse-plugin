package com.anthropic.eclipse.claude.api;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jface.preference.IPreferenceStore;
import com.anthropic.eclipse.claude.Activator;
import com.anthropic.eclipse.claude.preferences.PreferenceConstants;
import com.anthropic.eclipse.claude.preferences.SecureApiKeyStore;
import com.anthropic.eclipse.claude.util.JsonParser;

/**
 * Client for communicating with the Anthropic Claude API.
 */
public class ClaudeApiClient {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    // Conversation history for multi-turn chat
    private List<Message> conversationHistory = new ArrayList<>();

    public static class Message {
        public final String role;
        public final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    /**
     * Send a message and get a response. Maintains conversation history.
     */
    public String chat(String userMessage) throws Exception {
        conversationHistory.add(new Message("user", userMessage));
        String response = callApi(conversationHistory);
        conversationHistory.add(new Message("assistant", response));
        return response;
    }

    /**
     * Send a one-shot message without maintaining history.
     */
    public String sendOneShot(String systemPrompt, String userMessage) throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("user", userMessage));
        return callApi(systemPrompt, messages);
    }

    /**
     * Clear conversation history.
     */
    public void clearHistory() {
        conversationHistory.clear();
    }

    public List<Message> getHistory() {
        return new ArrayList<>(conversationHistory);
    }

    private String callApi(List<Message> messages) throws Exception {
        return callApi(null, messages);
    }

    private String callApi(String systemPrompt, List<Message> messages) throws Exception {
        IPreferenceStore prefs = Activator.getDefault().getPreferenceStore();
        String apiKey = SecureApiKeyStore.getApiKey();
        String model = prefs.getString(PreferenceConstants.MODEL);
        int maxTokens = prefs.getInt(PreferenceConstants.MAX_TOKENS);

        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new Exception("API Key not configured. Please set your Anthropic API key in Window > Preferences > Claude AI.");
        }

        // Build JSON payload
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"model\":\"").append(JsonParser.escapeJsonString(model)).append("\",");
        json.append("\"max_tokens\":").append(maxTokens).append(",");

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            json.append("\"system\":\"").append(JsonParser.escapeJsonString(systemPrompt)).append("\",");
        }

        json.append("\"messages\":[");
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            json.append("{\"role\":\"").append(msg.role).append("\",");
            json.append("\"content\":\"").append(JsonParser.escapeJsonString(msg.content)).append("\"}");
            if (i < messages.size() - 1) json.append(",");
        }
        json.append("]}");

        // Make HTTP request
        HttpURLConnection conn = (HttpURLConnection) URI.create(API_URL).toURL().openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", ANTHROPIC_VERSION);
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = json.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            InputStream is = responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream();

            StringBuilder response = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
            }

            if (responseCode >= 400) {
                throw new Exception("API Error (" + responseCode + "): " + response.toString());
            }

            return extractTextFromResponse(response.toString());
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Simple JSON text extraction - parses the "text" field from Claude's response.
     */
    private String extractTextFromResponse(String jsonResponse) {
        // Look for "text":"..." in the content array
        String marker = "\"text\":\"";
        int start = jsonResponse.indexOf(marker);
        if (start == -1) {
            // Try to extract error message
            marker = "\"message\":\"";
            start = jsonResponse.indexOf(marker);
            if (start == -1) return "Could not parse response: " + jsonResponse;
        }

        start += marker.length();
        StringBuilder result = new StringBuilder();
        boolean escape = false;

        for (int i = start; i < jsonResponse.length(); i++) {
            char c = jsonResponse.charAt(i);
            if (escape) {
                switch (c) {
                    case '"': result.append('"'); break;
                    case '\\': result.append('\\'); break;
                    case '/': result.append('/'); break;
                    case 'n': result.append('\n'); break;
                    case 'r': result.append('\r'); break;
                    case 't': result.append('\t'); break;
                    default: result.append('\\').append(c);
                }
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                break;
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

}
