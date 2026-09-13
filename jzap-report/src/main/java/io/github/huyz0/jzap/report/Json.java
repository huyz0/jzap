package io.github.huyz0.jzap.report;

import java.util.Locale;

/** Minimal JSON writing, so the report module stays free of a serialisation dependency. */
final class Json {

    private final StringBuilder sb = new StringBuilder();
    private int depth;
    private boolean needsComma;

    String finish() {
        return sb.toString();
    }

    Json startObject() {
        comma();
        sb.append("{\n");
        depth++;
        needsComma = false;
        return this;
    }

    Json endObject() {
        depth--;
        sb.append('\n').append(indent()).append('}');
        needsComma = true;
        return this;
    }

    Json startArray() {
        comma();
        sb.append("[\n");
        depth++;
        needsComma = false;
        return this;
    }

    Json endArray() {
        depth--;
        sb.append('\n').append(indent()).append(']');
        needsComma = true;
        return this;
    }

    Json key(String name) {
        comma();
        sb.append(indent()).append(quote(name)).append(": ");
        needsComma = false;
        return this;
    }

    Json value(String v) {
        sb.append(v == null ? "null" : quote(v));
        needsComma = true;
        return this;
    }

    Json value(long v) {
        sb.append(v);
        needsComma = true;
        return this;
    }

    Json value(double v) {
        sb.append(String.format(Locale.ROOT, "%.4f", v));
        needsComma = true;
        return this;
    }

    Json rawValue(String v) {
        sb.append(v);
        needsComma = true;
        return this;
    }

    Json element() {
        comma();
        sb.append(indent());
        needsComma = false;
        return this;
    }

    private void comma() {
        if (needsComma) {
            sb.append(",\n");
        }
    }

    private String indent() {
        return "  ".repeat(depth);
    }

    static String quote(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
