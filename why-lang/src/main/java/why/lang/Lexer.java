package why.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import why.WhyError;

/** Turns page source into tokens. Newlines are kept as tokens: they end statements. */
public final class Lexer {

    private static final Set<String> KEYWORDS = Set.of(
            "def", "end", "if", "elsif", "else", "unless", "while", "return",
            "break", "next", "true", "false", "nil", "and", "or", "not", "do", "then");

    private static final Set<String> TWO_CHAR_OPERATORS = Set.of(
            "==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "=>");

    private static final String ONE_CHAR_OPERATORS = "=+-*/%<>!.,()[]{}|:;";

    private final String src;
    private final String file;
    private int pos = 0;
    private int line;

    public Lexer(String src, String file, int firstLine) {
        this.src = src;
        this.file = file;
        this.line = firstLine <= 0 ? 1 : firstLine;
    }

    public List<Token> tokenize() {
        List<Token> out = new ArrayList<>();
        while (true) {
            skipSpaces();
            if (pos >= src.length()) {
                out.add(new Token(Token.Type.EOF, "", line, (char) 0));
                return out;
            }
            char c = src.charAt(pos);
            if (c == '\n') {
                out.add(new Token(Token.Type.NEWLINE, "\n", line, (char) 0));
                pos++;
                line++;
            } else if (c == '#') {
                while (pos < src.length() && src.charAt(pos) != '\n') pos++;
            } else if (Character.isDigit(c)) {
                out.add(number());
            } else if (Character.isLetter(c) || c == '_' || c == '@') {
                out.add(word());
            } else if (c == '"' || c == '\'') {
                out.add(string(c));
            } else {
                out.add(operator());
            }
        }
    }

    private void skipSpaces() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r') pos++;
            else return;
        }
    }

    private Token number() {
        int start = pos;
        int startLine = line;
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        boolean floating = false;
        if (pos + 1 < src.length() && src.charAt(pos) == '.' && Character.isDigit(src.charAt(pos + 1))) {
            floating = true;
            pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        }
        return new Token(floating ? Token.Type.FLOAT : Token.Type.INT, src.substring(start, pos), startLine, (char) 0);
    }

    private Token word() {
        int start = pos;
        int startLine = line;
        if (src.charAt(pos) == '@') pos++;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isLetterOrDigit(c) || c == '_') pos++;
            else break;
        }
        if (pos < src.length() && (src.charAt(pos) == '?' || src.charAt(pos) == '!')) pos++;
        String text = src.substring(start, pos);
        if (!text.startsWith("@") && KEYWORDS.contains(text)) {
            return new Token(Token.Type.KEYWORD, text, startLine, (char) 0);
        }
        return new Token(Token.Type.IDENT, text, startLine, (char) 0);
    }

    private Token string(char quote) {
        int startLine = line;
        pos++;
        StringBuilder raw = new StringBuilder();
        while (true) {
            if (pos >= src.length()) throw new WhyError("unterminated string", file, startLine);
            char c = src.charAt(pos);
            if (c == '\\') {
                if (pos + 1 >= src.length()) throw new WhyError("unterminated string", file, startLine);
                char escaped = src.charAt(pos + 1);
                raw.append(c).append(escaped);
                if (escaped == '\n') line++;
                pos += 2;
                continue;
            }
            if (c == quote) {
                pos++;
                break;
            }
            if (c == '\n') line++;
            raw.append(c);
            pos++;
        }
        return new Token(Token.Type.STRING, raw.toString(), startLine, quote);
    }

    private Token operator() {
        int startLine = line;
        if (pos + 1 < src.length()) {
            String two = src.substring(pos, pos + 2);
            if (TWO_CHAR_OPERATORS.contains(two)) {
                pos += 2;
                return new Token(Token.Type.OP, two, startLine, (char) 0);
            }
        }
        char c = src.charAt(pos);
        if (ONE_CHAR_OPERATORS.indexOf(c) >= 0) {
            pos++;
            return new Token(Token.Type.OP, String.valueOf(c), startLine, (char) 0);
        }
        throw new WhyError("unexpected character '" + c + "'", file, startLine);
    }
}
