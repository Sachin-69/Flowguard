package com.flowguard;

import java.util.*;

/** Minimal JSON serializer + flat-object parser (no external deps). */
public final class Json {
    private Json() {}

    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    public static String stringify(Object o) {
        if (o == null) return "null";
        if (o instanceof String) return "\"" + escape((String) o) + "\"";
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        if (o instanceof Map) {
            StringBuilder b = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?,?> e : ((Map<?,?>) o).entrySet()) {
                if (!first) b.append(",");
                first = false;
                b.append("\"").append(escape(String.valueOf(e.getKey()))).append("\":");
                b.append(stringify(e.getValue()));
            }
            return b.append("}").toString();
        }
        if (o instanceof Collection) {
            StringBuilder b = new StringBuilder("[");
            boolean first = true;
            for (Object e : (Collection<?>) o) {
                if (!first) b.append(",");
                first = false;
                b.append(stringify(e));
            }
            return b.append("]").toString();
        }
        return "\"" + escape(o.toString()) + "\"";
    }

    /** Parse a flat JSON object into Map<String,String>. Handles strings, numbers, booleans, null. */
    public static Map<String,String> parseFlat(String json) {
        Map<String,String> m = new LinkedHashMap<>();
        if (json == null) return m;
        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}")) json = json.substring(0, json.length()-1);
        List<String> parts = splitTopLevel(json);
        for (String p : parts) {
            int colon = indexOfColon(p);
            if (colon < 0) continue;
            String k = p.substring(0, colon).trim();
            String v = p.substring(colon+1).trim();
            k = unquote(k);
            v = unquote(v);
            m.put(k, v);
        }
        return m;
    }

    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inStr = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i-1) != '\\')) inStr = !inStr;
            if (c == ',' && !inStr) { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static int indexOfColon(String s) {
        boolean inStr = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i-1) != '\\')) inStr = !inStr;
            if (c == ':' && !inStr) return i;
        }
        return -1;
    }

    private static String unquote(String s) {
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length()-1);
            s = s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n");
        }
        return s;
    }

    public static String get(Map<String,String> m, String key, String def) {
        String v = m.get(key);
        return (v == null || v.isEmpty() || v.equals("null")) ? def : v;
    }

    public static int getInt(Map<String,String> m, String key, int def) {
        try { return Integer.parseInt(m.getOrDefault(key, String.valueOf(def)).trim()); }
        catch (Exception e) { return def; }
    }
}
