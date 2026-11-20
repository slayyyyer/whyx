package why.lang;

/** One lexed token. {@code quote} is the string delimiter for STRING tokens, otherwise 0. */
public record Token(Type type, String text, int line, char quote) {

    public enum Type { INT, FLOAT, STRING, IDENT, KEYWORD, OP, NEWLINE, EOF }
}
