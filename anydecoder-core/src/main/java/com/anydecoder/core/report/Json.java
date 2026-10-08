package com.anydecoder.core.report;

import java.util.List;
import java.util.Map;

/**
 * A minimal JSON writer.
 *
 * <p>The report is the interface between the command line and whatever consumes it -- a person,
 * a CI job, or an agent driving repairs -- so it needs a format that is unambiguous to parse.
 * A hand-written writer is enough for that and keeps the dependency list short, which for a tool
 * that asks users to run it against their own bytecode is worth more than the convenience of a
 * library.
 */
public final class Json {

    private Json() {
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void append(StringBuilder sb, Object value, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            appendString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b);
        } else if (value instanceof Number n) {
            sb.append(number(n));
        } else if (value instanceof Map<?, ?> map) {
            appendMap(sb, (Map<String, Object>) map, indent);
        } else if (value instanceof List<?> list) {
            appendList(sb, list, indent);
        } else {
            appendString(sb, String.valueOf(value));
        }
    }

    private static String number(Number n) {
        if (n instanceof Double d && (d.isNaN() || d.isInfinite())) {
            return "null";
        }
        return n.toString();
    }

    private static void appendMap(StringBuilder sb, Map<String, Object> map, int indent) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            pad(sb, indent + 1);
            appendString(sb, entry.getKey());
            sb.append(": ");
            append(sb, entry.getValue(), indent + 1);
            if (++i < map.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        pad(sb, indent);
        sb.append('}');
    }

    private static void appendList(StringBuilder sb, List<?> list, int indent) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        // Lists of scalars read better on one line, and the report is meant to be read.
        boolean scalars = list.stream().noneMatch(v -> v instanceof Map || v instanceof List);
        if (scalars) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                append(sb, list.get(i), indent);
            }
            sb.append(']');
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            pad(sb, indent + 1);
            append(sb, list.get(i), indent + 1);
            if (i < list.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        pad(sb, indent);
        sb.append(']');
    }

    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static void pad(StringBuilder sb, int indent) {
        sb.append("  ".repeat(indent));
    }
}
