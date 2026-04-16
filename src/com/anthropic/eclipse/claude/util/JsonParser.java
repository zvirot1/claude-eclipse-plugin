package com.anthropic.eclipse.claude.util;

import java.util.*;

/**
 * Minimal recursive-descent JSON parser.
 * No external dependencies - parses JSON strings into Maps, Lists, Strings, Numbers, and Booleans.
 */
public class JsonParser {

    private final String input;
    private int pos;

    public JsonParser(String input) {
        this.input = input;
        this.pos = 0;
    }

    /**
     * Parse a JSON string into a Java object.
     * @return Map for objects, List for arrays, String, Number, Boolean, or null
     */
    public static Object parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        JsonParser parser = new JsonParser(json.trim());
        return parser.parseValue();
    }

    /**
     * Parse a JSON string into a Map.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object result = parse(json);
        if (result instanceof Map) {
            return (Map<String, Object>) result;
        }
        throw new JsonParseException("Expected JSON object, got: " + (result == null ? "null" : result.getClass().getSimpleName()));
    }

    /**
     * Safely get a string value from a map.
     */
    public static String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    /**
     * Safely get a string value with default.
     */
    public static String getString(Map<String, Object> map, String key, String defaultValue) {
        Object val = map.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    /**
     * Safely get an int value from a map.
     */
    public static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        return defaultValue;
    }

    /**
     * Safely get a double value from a map.
     */
    public static double getDouble(Map<String, Object> map, String key, double defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        return defaultValue;
    }

    /**
     * Safely get a long value from a map.
     */
    public static long getLong(Map<String, Object> map, String key, long defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number) val).longValue();
        }
        return defaultValue;
    }

    /**
     * Safely get a boolean value from a map.
     */
    public static boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object val = map.get(key);
        if (val instanceof Boolean) {
            return (Boolean) val;
        }
        return defaultValue;
    }

    /**
     * Safely get a nested map from a map.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> getMap(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Map) {
            return (Map<String, Object>) val;
        }
        return null;
    }

    /**
     * Safely get a list from a map.
     */
    @SuppressWarnings("unchecked")
    public static List<Object> getList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof List) {
            return (List<Object>) val;
        }
        return null;
    }

    /**
     * Convert an object back to a JSON string.
     */
    @SuppressWarnings("unchecked")
    public static String toJson(Object obj) {
        if (obj == null) {
            return "null";
        }
        if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(escapeJsonString(entry.getKey())).append("\":");
                sb.append(toJson(entry.getValue()));
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }
        if (obj instanceof List) {
            List<Object> list = (List<Object>) obj;
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(",");
                sb.append(toJson(item));
                first = false;
            }
            sb.append("]");
            return sb.toString();
        }
        if (obj instanceof String) {
            return "\"" + escapeJsonString((String) obj) + "\"";
        }
        if (obj instanceof Number || obj instanceof Boolean) {
            return obj.toString();
        }
        return "\"" + escapeJsonString(obj.toString()) + "\"";
    }

    // --- Internal parsing methods ---

    private Object parseValue() {
        skipWhitespace();
        if (pos >= input.length()) {
            throw new JsonParseException("Unexpected end of input");
        }
        char c = input.charAt(pos);
        switch (c) {
            case '{': return parseObjectInternal();
            case '[': return parseArrayInternal();
            case '"': return parseString();
            case 't': case 'f': return parseBoolean();
            case 'n': return parseNull();
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return parseNumber();
                }
                throw new JsonParseException("Unexpected character '" + c + "' at position " + pos);
        }
    }

    private Map<String, Object> parseObjectInternal() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // skip '{'
        skipWhitespace();
        if (pos < input.length() && input.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (pos < input.length()) {
            skipWhitespace();
            if (input.charAt(pos) != '"') {
                throw new JsonParseException("Expected '\"' for object key at position " + pos);
            }
            String key = parseString();
            skipWhitespace();
            if (pos >= input.length() || input.charAt(pos) != ':') {
                throw new JsonParseException("Expected ':' after key at position " + pos);
            }
            pos++; // skip ':'
            Object value = parseValue();
            map.put(key, value);
            skipWhitespace();
            if (pos >= input.length()) break;
            char c = input.charAt(pos);
            if (c == '}') {
                pos++;
                return map;
            }
            if (c == ',') {
                pos++;
            }
        }
        return map;
    }

    private List<Object> parseArrayInternal() {
        List<Object> list = new ArrayList<>();
        pos++; // skip '['
        skipWhitespace();
        if (pos < input.length() && input.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (pos < input.length()) {
            list.add(parseValue());
            skipWhitespace();
            if (pos >= input.length()) break;
            char c = input.charAt(pos);
            if (c == ']') {
                pos++;
                return list;
            }
            if (c == ',') {
                pos++;
            }
        }
        return list;
    }

    private String parseString() {
        pos++; // skip opening '"'
        StringBuilder sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '\\') {
                pos++;
                if (pos >= input.length()) break;
                char escaped = input.charAt(pos);
                switch (escaped) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 < input.length()) {
                            String hex = input.substring(pos + 1, pos + 5);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                        }
                        break;
                    default: sb.append(escaped);
                }
            } else if (c == '"') {
                pos++; // skip closing '"'
                return sb.toString();
            } else {
                sb.append(c);
            }
            pos++;
        }
        return sb.toString();
    }

    private Number parseNumber() {
        int start = pos;
        if (input.charAt(pos) == '-') pos++;
        while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') pos++;
        boolean isFloat = false;
        if (pos < input.length() && input.charAt(pos) == '.') {
            isFloat = true;
            pos++;
            while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') pos++;
        }
        if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
            isFloat = true;
            pos++;
            if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++;
            while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') pos++;
        }
        String numStr = input.substring(start, pos);
        if (isFloat) {
            return Double.parseDouble(numStr);
        }
        long val = Long.parseLong(numStr);
        if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
            return (int) val;
        }
        return val;
    }

    private Boolean parseBoolean() {
        if (input.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (input.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw new JsonParseException("Expected 'true' or 'false' at position " + pos);
    }

    private Object parseNull() {
        if (input.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw new JsonParseException("Expected 'null' at position " + pos);
    }

    private void skipWhitespace() {
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break;
            pos++;
        }
    }

    public static String escapeJsonString(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else if (c > 0x7F) {
                        // Escape non-ASCII characters (Hebrew, Arabic, CJK, emoji, etc.)
                        // as JSON unicode escapes for maximum compatibility across
                        // system encodings and CLI versions. Handles surrogate pairs
                        // for characters outside the Basic Multilingual Plane.
                        if (Character.isHighSurrogate(c) && i + 1 < s.length()) {
                            char low = s.charAt(i + 1);
                            if (Character.isLowSurrogate(low)) {
                                sb.append(String.format("\\u%04x\\u%04x", (int) c, (int) low));
                                i++; // skip the low surrogate
                            } else {
                                sb.append(String.format("\\u%04x", (int) c));
                            }
                        } else {
                            sb.append(String.format("\\u%04x", (int) c));
                        }
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * Pretty-print a compact JSON string with indentation.
     */
    public static String prettyPrint(String json) {
        StringBuilder sb = new StringBuilder();
        int indent = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (inString) {
                sb.append(c);
                continue;
            }
            switch (c) {
                case '{': case '[':
                    sb.append(c).append('\n');
                    indent += 2;
                    sb.append(" ".repeat(indent));
                    break;
                case '}': case ']':
                    sb.append('\n');
                    indent -= 2;
                    sb.append(" ".repeat(Math.max(0, indent)));
                    sb.append(c);
                    break;
                case ',':
                    sb.append(c).append('\n');
                    sb.append(" ".repeat(indent));
                    break;
                case ':':
                    sb.append(": ");
                    break;
                default:
                    sb.append(c);
            }
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * Exception for JSON parse errors.
     */
    public static class JsonParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public JsonParseException(String message) {
            super(message);
        }
    }
}
