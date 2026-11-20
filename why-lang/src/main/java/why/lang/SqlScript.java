package why.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits a SQL script into the statements SQLite should execute. A semicolon ends a statement only
 * when it is outside a string, a quoted identifier, a comment, or a {@code CREATE TRIGGER} body.
 *
 * <p>Trigger bodies need their inner semicolons kept: {@code CREATE TRIGGER ... BEGIN ... END;}
 * is one statement even though it contains many. A {@code CASE ... END} inside the body is counted
 * so the trigger's own {@code END} is the one that closes it.
 */
public final class SqlScript {
    private SqlScript() {}

    /** Top-level statements, without their trailing semicolons and without empty fragments. */
    public static List<String> statements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean code = false;
        int prefix = 0; // 0 at start, 1 after CREATE, 2 after CREATE TEMP, 3 anything else
        boolean trigger = false;
        boolean body = false;
        int cases = 0;
        int i = 0;
        int n = script.length();
        while (i < n) {
            char c = script.charAt(i);
            if (c == '-' && i + 1 < n && script.charAt(i + 1) == '-') {
                int end = script.indexOf('\n', i + 2);
                end = end < 0 ? n : end + 1;
                current.append(' ');
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < n && script.charAt(i + 1) == '*') {
                int end = script.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                current.append(' ');
                i = end;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                int end = quoted(script, i, c);
                current.append(script, i, end);
                code = true;
                i = end;
                continue;
            }
            if (c == '[') {
                int end = script.indexOf(']', i + 1);
                end = end < 0 ? n : end + 1;
                current.append(script, i, end);
                code = true;
                i = end;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int end = i + 1;
                while (end < n && (Character.isLetterOrDigit(script.charAt(end))
                        || script.charAt(end) == '_')) {
                    end++;
                }
                String word = script.substring(i, end);
                current.append(word);
                code = true;
                String lower = word.toLowerCase(Locale.ROOT);
                if (body) {
                    if (lower.equals("case")) {
                        cases++;
                    } else if (lower.equals("end")) {
                        if (cases > 0) cases--;
                        else body = false;
                    }
                } else if (trigger) {
                    if (lower.equals("begin")) {
                        trigger = false;
                        body = true;
                    }
                } else if (prefix == 0) {
                    prefix = lower.equals("create") ? 1 : 3;
                } else if (prefix == 1) {
                    if (lower.equals("temp") || lower.equals("temporary")) {
                        prefix = 2;
                    } else {
                        if (lower.equals("trigger")) trigger = true;
                        prefix = 3;
                    }
                } else if (prefix == 2) {
                    if (lower.equals("trigger")) trigger = true;
                    prefix = 3;
                }
                i = end;
                continue;
            }
            if (c == ';') {
                if (body) {
                    current.append(c);
                } else {
                    if (code) statements.add(current.toString().strip());
                    current.setLength(0);
                    code = false;
                    prefix = 0;
                    trigger = false;
                    cases = 0;
                }
                i++;
                continue;
            }
            current.append(c);
            if (!Character.isWhitespace(c)) {
                code = true;
                if (prefix == 0) prefix = 3;
            }
            i++;
        }
        if (code) statements.add(current.toString().strip());
        return statements;
    }

    /** The end of a quoted string or identifier, where a doubled quote stays inside it. */
    private static int quoted(String sql, int start, char quote) {
        int i = start + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return sql.length();
    }
}
