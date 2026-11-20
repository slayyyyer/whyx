package why.lang;

import java.util.List;

/** The syntax tree: statements, expressions and the small records they are built from. */
public final class Ast {
    private Ast() {}

    public interface Node {
        int line();
    }

    public interface Stmt extends Node {}

    public interface Expr extends Node {}

    // ---- statements ---------------------------------------------------

    /** {@code name = value}, and the compound forms {@code += -= *= /=}. */
    public record Assign(String name, String op, Expr value, int line) implements Stmt {}

    public record ExprStmt(Expr expr, int line) implements Stmt {}

    public record Branch(Expr cond, List<Stmt> body) {}

    public record If(List<Branch> branches, List<Stmt> otherwise, int line) implements Stmt {}

    public record While(Expr cond, List<Stmt> body, int line) implements Stmt {}

    /** {@code file} is the page the def was written in, for runtime error locations. */
    public record Def(String name, List<String> params, List<Stmt> body, int line, String file) implements Stmt {}

    /** {@code return} with no value has a null value. */
    public record Return(Expr value, int line) implements Stmt {}

    public record Break(int line) implements Stmt {}

    public record Next(int line) implements Stmt {}

    // ---- expressions --------------------------------------------------

    public record IntLit(long value, int line) implements Expr {}

    public record FloatLit(double value, int line) implements Expr {}

    public record StrLit(String value, int line) implements Expr {}

    /** A double-quoted string with {@code #{...}} parts. */
    public record Interp(List<Expr> parts, int line) implements Expr {}

    public record BoolLit(boolean value, int line) implements Expr {}

    public record NilLit(int line) implements Expr {}

    public record ArrayLit(List<Expr> items, int line) implements Expr {}

    public record Pair(Expr key, Expr value) {}

    public record HashLit(List<Pair> pairs, int line) implements Expr {}

    public record Var(String name, int line) implements Expr {}

    /** {@code a.b}: a hash key, or a method/property with no parentheses. */
    public record Get(Expr target, String name, int line) implements Expr {}

    public record Index(Expr target, Expr index, int line) implements Expr {}

    /** {@code f(...)} when target is null, otherwise {@code recv.name(...)}; block is optional. */
    public record Call(Expr target, String name, List<Expr> args, Block block, int line) implements Expr {}

    public record Block(List<String> params, List<Stmt> body) {}

    public record Binary(String op, Expr left, Expr right, int line) implements Expr {}

    public record Unary(String op, Expr operand, int line) implements Expr {}
}
