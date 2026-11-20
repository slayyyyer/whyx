package why.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import why.WhyError;

/**
 * Recursive-descent parser for the Why language. Newlines end statements, so the
 * precedence chain skips them only after operators, commas and opening brackets.
 */
public final class Parser {

    private static final Set<String> ASSIGN_OPS = Set.of("=", "+=", "-=", "*=", "/=");
    private static final Set<String> BODY_ENDS = Set.of("end");
    private static final Set<String> IF_ENDS = Set.of("elsif", "else", "end");
    private static final Set<String> STATEMENT_END_KEYWORDS = Set.of("end", "else", "elsif");

    /** Binary operators, loosest binding first. */
    private static final List<Set<String>> PRECEDENCE = List.of(
            Set.of("||", "or"),
            Set.of("&&", "and"),
            Set.of("==", "!="),
            Set.of("<", "<=", ">", ">="),
            Set.of("+", "-"),
            Set.of("*", "/", "%"));

    private final List<Token> tokens;
    private final String file;
    private int pos = 0;

    public Parser(String source, String file, int firstLine) {
        this.file = file;
        this.tokens = new Lexer(source, file, firstLine).tokenize();
    }

    /** A whole controller (or an empty source). */
    public List<Ast.Stmt> parseProgram() {
        List<Ast.Stmt> body = statements(Set.of());
        skipSeparators();
        Token t = peek();
        if (t.type() != Token.Type.EOF) throw error(t, "unexpected '" + t.text() + "'");
        return body;
    }

    /** One expression, as found inside a {@code {{...}}} tag. */
    public Ast.Expr parseExpressionOnly() {
        skipSeparators();
        Ast.Expr expr = expression();
        skipSeparators();
        Token t = peek();
        if (t.type() != Token.Type.EOF) throw error(t, "unexpected '" + t.text() + "'");
        return expr;
    }

    // ---- statements ---------------------------------------------------

    private List<Ast.Stmt> statements(Set<String> stops) {
        List<Ast.Stmt> out = new ArrayList<>();
        while (true) {
            skipSeparators();
            Token t = peek();
            if (t.type() == Token.Type.EOF) return out;
            if (t.type() == Token.Type.KEYWORD && stops.contains(t.text())) return out;
            out.add(statement());
        }
    }

    private Ast.Stmt statement() {
        Token t = peek();
        if (t.type() == Token.Type.KEYWORD) {
            return switch (t.text()) {
                case "if" -> {
                    next();
                    yield conditional(false, t.line());
                }
                case "unless" -> {
                    next();
                    yield conditional(true, t.line());
                }
                case "while" -> {
                    next();
                    yield whileLoop(t.line());
                }
                case "def" -> {
                    next();
                    yield definition(t.line());
                }
                case "return" -> {
                    next();
                    yield new Ast.Return(statementEnds() ? null : expression(), t.line());
                }
                case "break" -> {
                    next();
                    yield new Ast.Break(t.line());
                }
                case "next" -> {
                    next();
                    yield new Ast.Next(t.line());
                }
                default -> throw error(t, "unexpected keyword '" + t.text() + "'");
            };
        }
        if (t.type() == Token.Type.IDENT && isAssign(peek(1))) {
            next();
            String op = next().text();
            return new Ast.Assign(t.text(), op, expression(), t.line());
        }
        return new Ast.ExprStmt(expression(), t.line());
    }

    private boolean statementEnds() {
        Token t = peek();
        return t.type() == Token.Type.EOF
                || t.type() == Token.Type.NEWLINE
                || (t.type() == Token.Type.OP && t.text().equals(";"))
                || (t.type() == Token.Type.KEYWORD && STATEMENT_END_KEYWORDS.contains(t.text()));
    }

    private boolean isAssign(Token t) {
        return t.type() == Token.Type.OP && ASSIGN_OPS.contains(t.text());
    }

    private Ast.Stmt conditional(boolean inverted, int line) {
        Ast.Expr cond = expression();
        if (inverted) cond = new Ast.Unary("not", cond, line);
        consumeThen();

        List<Ast.Branch> branches = new ArrayList<>();
        branches.add(new Ast.Branch(cond, statements(IF_ENDS)));
        List<Ast.Stmt> otherwise = List.of();

        while (true) {
            skipSeparators();
            Token t = peek();
            if (t.type() == Token.Type.KEYWORD && t.text().equals("elsif")) {
                next();
                Ast.Expr other = expression();
                consumeThen();
                branches.add(new Ast.Branch(other, statements(IF_ENDS)));
            } else if (t.type() == Token.Type.KEYWORD && t.text().equals("else")) {
                next();
                consumeThen();
                otherwise = statements(BODY_ENDS);
                skipSeparators();
                expectKeyword("end");
                break;
            } else if (t.type() == Token.Type.KEYWORD && t.text().equals("end")) {
                next();
                break;
            } else {
                throw error(t, "expected 'elsif', 'else' or 'end'");
            }
        }
        return new Ast.If(branches, otherwise, line);
    }

    private Ast.Stmt whileLoop(int line) {
        Ast.Expr cond = expression();
        if (checkKeyword("do")) next();
        List<Ast.Stmt> body = statements(BODY_ENDS);
        skipSeparators();
        expectKeyword("end");
        return new Ast.While(cond, body, line);
    }

    private Ast.Stmt definition(int line) {
        Token name = expectIdent("a function name");
        List<String> params = new ArrayList<>();
        if (checkOp("(")) {
            next();
            skipNewlines();
            if (!checkOp(")")) {
                do {
                    params.add(expectIdent("a parameter name").text());
                    skipNewlines();
                } while (matchOp(","));
            }
            expectOp(")");
        }
        List<Ast.Stmt> body = statements(BODY_ENDS);
        skipSeparators();
        expectKeyword("end");
        return new Ast.Def(name.text(), params, body, line, file);
    }

    private void consumeThen() {
        if (checkKeyword("then")) next();
    }

    // ---- expressions --------------------------------------------------

    private Ast.Expr expression() {
        Token t = peek();
        if (t.type() == Token.Type.KEYWORD && t.text().equals("not")) {
            next();
            return new Ast.Unary("not", expression(), t.line());
        }
        if (t.type() == Token.Type.OP && t.text().equals("!")) {
            next();
            return new Ast.Unary("not", expression(), t.line());
        }
        return binary(0);
    }

    /** Precedence climbing: one loop serves every binary operator level. */
    private Ast.Expr binary(int level) {
        if (level == PRECEDENCE.size()) return unary();
        Ast.Expr left = binary(level + 1);
        while (true) {
            Token t = peek();
            boolean isOperator = t.type() == Token.Type.OP || t.type() == Token.Type.KEYWORD;
            if (!isOperator || !PRECEDENCE.get(level).contains(t.text())) return left;
            next();
            skipNewlines();
            left = new Ast.Binary(normalize(t.text()), left, binary(level + 1), t.line());
        }
    }

    private static String normalize(String op) {
        return switch (op) {
            case "or" -> "||";
            case "and" -> "&&";
            default -> op;
        };
    }

    private Ast.Expr unary() {
        Token t = peek();
        if (t.type() == Token.Type.OP && (t.text().equals("-") || t.text().equals("+"))) {
            next();
            Ast.Expr operand = unary();
            return t.text().equals("-") ? new Ast.Unary("-", operand, t.line()) : operand;
        }
        return postfix();
    }

    private Ast.Expr postfix() {
        Ast.Expr expr = primary();
        while (true) {
            if (checkOp(".")) {
                Token dot = next();
                Token name = expectIdent("a method or property name");
                List<Ast.Expr> args = List.of();
                Ast.Block block = null;
                if (checkOp("(")) args = arguments();
                if (checkKeyword("do")) block = block();
                expr = args.isEmpty() && block == null
                        ? new Ast.Get(expr, name.text(), dot.line())
                        : new Ast.Call(expr, name.text(), args, block, dot.line());
            } else if (checkOp("[")) {
                next();
                skipNewlines();
                Ast.Expr index = expression();
                skipNewlines();
                expectOp("]");
                int line = expr.line();
                expr = new Ast.Index(expr, index, line);
            } else if (checkKeyword("do") && expr instanceof Ast.Call call && call.block() == null) {
                expr = new Ast.Call(call.target(), call.name(), call.args(), block(), call.line());
            } else {
                return expr;
            }
        }
    }

    private Ast.Block block() {
        expectKeyword("do");
        List<String> params = new ArrayList<>();
        if (checkOp("|")) {
            next();
            if (!checkOp("|")) {
                do {
                    params.add(expectIdent("a block parameter").text());
                } while (matchOp(","));
            }
            expectOp("|");
        }
        List<Ast.Stmt> body = statements(BODY_ENDS);
        skipSeparators();
        expectKeyword("end");
        return new Ast.Block(params, body);
    }

    private Ast.Expr primary() {
        Token t = next();
        switch (t.type()) {
            case INT:
                return new Ast.IntLit(Long.parseLong(t.text()), t.line());
            case FLOAT:
                return new Ast.FloatLit(Double.parseDouble(t.text()), t.line());
            case STRING:
                return string(t);
            case IDENT:
                if (checkOp("(")) return new Ast.Call(null, t.text(), arguments(), null, t.line());
                return new Ast.Var(t.text(), t.line());
            case KEYWORD:
                return switch (t.text()) {
                    case "true" -> new Ast.BoolLit(true, t.line());
                    case "false" -> new Ast.BoolLit(false, t.line());
                    case "nil" -> new Ast.NilLit(t.line());
                    default -> throw error(t, "unexpected keyword '" + t.text() + "'");
                };
            case OP:
                return switch (t.text()) {
                    case "(" -> {
                        skipNewlines();
                        Ast.Expr inner = expression();
                        skipNewlines();
                        expectOp(")");
                        yield inner;
                    }
                    case "[" -> array(t.line());
                    case "{" -> hash(t.line());
                    default -> throw error(t, "unexpected '" + t.text() + "'");
                };
            default:
                throw error(t, "unexpected end of input");
        }
    }

    private List<Ast.Expr> arguments() {
        expectOp("(");
        List<Ast.Expr> args = new ArrayList<>();
        List<Ast.Pair> named = new ArrayList<>();
        boolean sawNamed = false;
        skipNewlines();
        if (!checkOp(")")) {
            do {
                skipNewlines();
                if (peek().type() == Token.Type.IDENT && peek(1).type() == Token.Type.OP
                        && peek(1).text().equals(":")) {
                    Token key = next();
                    next();
                    named.add(new Ast.Pair(new Ast.StrLit(key.text(), key.line()), expression()));
                    sawNamed = true;
                } else {
                    if (sawNamed) throw error(peek(), "positional arguments cannot follow named arguments");
                    args.add(expression());
                }
                skipNewlines();
            } while (matchOp(","));
        }
        expectOp(")");
        // Named arguments are a trailing hash, which is how db.query(sql, who: uid) gets its map.
        if (!named.isEmpty()) args.add(new Ast.HashLit(named, named.get(0).key().line()));
        return args;
    }

    private Ast.Expr array(int line) {
        List<Ast.Expr> items = new ArrayList<>();
        skipNewlines();
        if (!checkOp("]")) {
            do {
                skipNewlines();
                items.add(expression());
                skipNewlines();
            } while (matchOp(","));
        }
        expectOp("]");
        return new Ast.ArrayLit(items, line);
    }

    private Ast.Expr hash(int line) {
        List<Ast.Pair> pairs = new ArrayList<>();
        skipNewlines();
        if (!checkOp("}")) {
            do {
                skipNewlines();
                if (peek().type() == Token.Type.IDENT && peek(1).type() == Token.Type.OP
                        && peek(1).text().equals(":")) {
                    Token key = next();
                    next();
                    pairs.add(new Ast.Pair(new Ast.StrLit(key.text(), key.line()), expression()));
                } else {
                    Ast.Expr key = expression();
                    expectOp("=>");
                    pairs.add(new Ast.Pair(key, expression()));
                }
                skipNewlines();
            } while (matchOp(","));
        }
        expectOp("}");
        return new Ast.HashLit(pairs, line);
    }

    // ---- strings ------------------------------------------------------

    private Ast.Expr string(Token t) {
        String raw = t.text();
        if (t.quote() == '\'') return new Ast.StrLit(singleQuoted(raw), t.line());
        return interpolated(raw, t.line());
    }

    private static String singleQuoted(String raw) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char escaped = raw.charAt(i + 1);
                if (escaped == '\'' || escaped == '\\') {
                    out.append(escaped);
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private Ast.Expr interpolated(String raw, int line) {
        List<Ast.Expr> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char escaped = raw.charAt(i + 1);
                switch (escaped) {
                    case 'n' -> literal.append('\n');
                    case 't' -> literal.append('\t');
                    case 'r' -> literal.append('\r');
                    case '0' -> literal.append('\0');
                    default -> literal.append(escaped);
                }
                i += 2;
                continue;
            }
            if (c == '#' && i + 1 < raw.length() && raw.charAt(i + 1) == '{') {
                int start = i + 2;
                int at = start;
                int depth = 1;
                while (at < raw.length() && depth > 0) {
                    char d = raw.charAt(at);
                    if (d == '"' || d == '\'') {
                        at = skipString(raw, at);
                        continue;
                    }
                    if (d == '{') {
                        depth++;
                    } else if (d == '}') {
                        depth--;
                        if (depth == 0) break;
                    }
                    at++;
                }
                if (depth != 0) throw new WhyError("unterminated '#{' in string", file, line);
                if (literal.length() > 0) {
                    parts.add(new Ast.StrLit(literal.toString(), line));
                    literal.setLength(0);
                }
                parts.add(new Parser(raw.substring(start, at), file, line).parseExpressionOnly());
                i = at + 1;
                continue;
            }
            literal.append(c);
            i++;
        }
        if (literal.length() > 0) parts.add(new Ast.StrLit(literal.toString(), line));
        if (parts.isEmpty()) return new Ast.StrLit("", line);
        if (parts.size() == 1 && parts.get(0) instanceof Ast.StrLit only) return only;
        return new Ast.Interp(parts, line);
    }

    private static int skipString(String raw, int quotePos) {
        char quote = raw.charAt(quotePos);
        int at = quotePos + 1;
        while (at < raw.length()) {
            char c = raw.charAt(at);
            if (c == '\\') {
                at += 2;
                continue;
            }
            if (c == quote) return at + 1;
            at++;
        }
        return at;
    }

    // ---- token helpers -------------------------------------------------

    private Token peek() {
        return tokens.get(Math.min(pos, tokens.size() - 1));
    }

    private Token peek(int ahead) {
        return tokens.get(Math.min(pos + ahead, tokens.size() - 1));
    }

    private Token next() {
        Token t = peek();
        if (pos < tokens.size() - 1) pos++;
        return t;
    }

    private boolean checkOp(String op) {
        Token t = peek();
        return t.type() == Token.Type.OP && t.text().equals(op);
    }

    private boolean matchOp(String op) {
        if (!checkOp(op)) return false;
        next();
        return true;
    }

    private void expectOp(String op) {
        if (!matchOp(op)) throw error(peek(), "expected '" + op + "'");
    }

    private boolean checkKeyword(String keyword) {
        Token t = peek();
        return t.type() == Token.Type.KEYWORD && t.text().equals(keyword);
    }

    private void expectKeyword(String keyword) {
        if (!checkKeyword(keyword)) throw error(peek(), "expected '" + keyword + "'");
        next();
    }

    private Token expectIdent(String what) {
        Token t = peek();
        if (t.type() != Token.Type.IDENT) throw error(t, "expected " + what);
        return next();
    }

    private void skipNewlines() {
        while (peek().type() == Token.Type.NEWLINE) next();
    }

    private void skipSeparators() {
        while (true) {
            Token t = peek();
            if (t.type() == Token.Type.NEWLINE || (t.type() == Token.Type.OP && t.text().equals(";"))) next();
            else return;
        }
    }

    private WhyError error(Token t, String message) {
        return new WhyError(message, file, t.line());
    }
}
