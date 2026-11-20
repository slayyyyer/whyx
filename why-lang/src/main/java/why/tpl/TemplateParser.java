package why.tpl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import why.WhyError;
import why.lang.Ast;
import why.lang.Parser;

/** Builds a {@link Template} from view text. */
public final class TemplateParser {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Set<String> BLOCKS = Set.of("if", "unless", "each");

    private final String src;
    private final String file;
    private final int firstLine;
    private int pos = 0;

    private TemplateParser(String src, String file, int firstLine) {
        this.src = src;
        this.file = file;
        this.firstLine = firstLine <= 0 ? 1 : firstLine;
    }

    public static Template parse(String source, String file, int firstLine) {
        return new TemplateParser(source, file, firstLine).build();
    }

    private static final class Frame {
        final String kind;
        final Ast.Expr expr;
        final int line;
        final List<Template.Node> body = new ArrayList<>();
        final List<Template.Node> otherwise = new ArrayList<>();
        boolean inElse;

        Frame(String kind, Ast.Expr expr, int line) {
            this.kind = kind;
            this.expr = expr;
            this.line = line;
        }
    }

    private Template build() {
        List<Template.Node> root = new ArrayList<>();
        Deque<Frame> stack = new ArrayDeque<>();

        while (pos < src.length()) {
            int open = src.indexOf("{{", pos);
            if (open < 0) {
                addText(stack, root, src.substring(pos));
                break;
            }
            if (open > pos) addText(stack, root, src.substring(pos, open));

            boolean raw = src.startsWith("{{{", open);
            int contentStart = open + (raw ? 3 : 2);
            String closer = raw ? "}}}" : "}}";
            int close = src.indexOf(closer, contentStart);
            int tagLine = lineAt(open);
            if (close < 0) throw new WhyError("unterminated '" + (raw ? "{{{" : "{{") + "'", file, tagLine);

            String tag = src.substring(contentStart, close).trim();
            pos = close + closer.length();

            if (raw) {
                echo(stack, root, tag, false, tagLine);
                continue;
            }
            if (tag.isEmpty()) throw new WhyError("empty '{{}}'", file, tagLine);

            char lead = tag.charAt(0);
            if (lead == '!') {
                continue;
            }
            if (lead == '>') {
                String name = tag.substring(1).trim();
                if (!NAME.matcher(name).matches()) {
                    throw new WhyError("partial name '" + name + "' is not a bare name", file, tagLine);
                }
                add(stack, root, new Template.Partial(name, tagLine));
                continue;
            }
            if (lead == '#') {
                String rest = tag.substring(1).trim();
                int space = indexOfWhitespace(rest);
                String kind = space < 0 ? rest : rest.substring(0, space);
                String expression = space < 0 ? "" : rest.substring(space + 1).trim();
                if (!BLOCKS.contains(kind)) throw new WhyError("unknown block '{{#" + kind + "}}'", file, tagLine);
                if (expression.isEmpty()) throw new WhyError("'{{#" + kind + "}}' needs an expression", file, tagLine);
                Ast.Expr expr = new Parser(expression, file, tagLine).parseExpressionOnly();
                if (kind.equals("unless")) expr = new Ast.Unary("not", expr, tagLine);
                stack.push(new Frame(kind, expr, tagLine));
                continue;
            }
            if (lead == '/') {
                String name = tag.substring(1).trim();
                if (stack.isEmpty()) throw new WhyError("unexpected '{{/" + name + "}}'", file, tagLine);
                Frame frame = stack.pop();
                if (!name.equals(frame.kind)) {
                    throw new WhyError("'{{/" + name + "}}' does not close '{{#" + frame.kind + "}}'", file, tagLine);
                }
                List<Template.Node> body = List.copyOf(frame.body);
                List<Template.Node> otherwise = List.copyOf(frame.otherwise);
                add(stack, root, frame.kind.equals("each")
                        ? new Template.Each(frame.expr, body, otherwise, frame.line)
                        : new Template.Block(frame.expr, body, otherwise, frame.line));
                continue;
            }
            if (tag.equals("else")) {
                if (stack.isEmpty()) throw new WhyError("'{{else}}' outside a block", file, tagLine);
                Frame frame = stack.peek();
                if (frame.inElse) throw new WhyError("duplicate '{{else}}'", file, tagLine);
                frame.inElse = true;
                continue;
            }
            if (lead == '&') {
                echo(stack, root, tag.substring(1).trim(), false, tagLine);
                continue;
            }
            echo(stack, root, tag, true, tagLine);
        }

        if (!stack.isEmpty()) {
            Frame frame = stack.peek();
            throw new WhyError("unclosed '{{#" + frame.kind + "}}'", file, frame.line);
        }
        return new Template(file, root);
    }

    private void echo(Deque<Frame> stack, List<Template.Node> root, String expression, boolean escape, int line) {
        if (expression.isEmpty()) throw new WhyError("empty mustache", file, line);
        Ast.Expr expr = new Parser(expression, file, line).parseExpressionOnly();
        add(stack, root, new Template.Echo(expr, escape, line));
    }

    private static void add(Deque<Frame> stack, List<Template.Node> root, Template.Node node) {
        if (stack.isEmpty()) {
            root.add(node);
        } else {
            Frame frame = stack.peek();
            (frame.inElse ? frame.otherwise : frame.body).add(node);
        }
    }

    private static void addText(Deque<Frame> stack, List<Template.Node> root, String text) {
        if (!text.isEmpty()) add(stack, root, new Template.Text(text));
    }

    private static int indexOfWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) return i;
        }
        return -1;
    }

    private int lineAt(int offset) {
        int line = firstLine;
        for (int i = 0; i < offset && i < src.length(); i++) {
            if (src.charAt(i) == '\n') line++;
        }
        return line;
    }
}
