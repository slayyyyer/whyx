package why.util;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import why.WhyError;

/** Compact JSON for the values a page can hold: nil, bool, numbers, strings, lists, hashes. */
public final class Json {
    private static final int MAX_DEPTH = 64;

    private Json() {}

    public static String write(Object value, int line) {
        StringBuilder out = new StringBuilder();
        writeValue(value, out, 0, line);
        return out.toString();
    }

    private static void writeValue(Object value, StringBuilder out, int depth, int line) {
        if (depth > MAX_DEPTH) throw new WhyError("response.json nests more than " + MAX_DEPTH + " deep", null, line);
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean bool) {
            out.append(bool);
        } else if (value instanceof Number number) {
            writeNumber(number, out);
        } else if (value instanceof String text) {
            writeString(text, out);
        } else if (value instanceof byte[] bytes) {
            writeString(Base64.getEncoder().encodeToString(bytes), out);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                writeString(String.valueOf(entry.getKey()), out);
                out.append(':');
                writeValue(entry.getValue(), out, depth + 1, line);
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) out.append(',');
                first = false;
                writeValue(item, out, depth + 1, line);
            }
            out.append(']');
        } else {
            writeString(String.valueOf(value), out);
        }
    }

    private static void writeNumber(Number number, StringBuilder out) {
        if (number instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                out.append("null");
                return;
            }
            out.append(d.doubleValue());
        } else if (number instanceof Float f) {
            if (f.isNaN() || f.isInfinite()) {
                out.append("null");
                return;
            }
            out.append(f.doubleValue());
        } else {
            out.append(number.longValue());
        }
    }

    private static void writeString(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
