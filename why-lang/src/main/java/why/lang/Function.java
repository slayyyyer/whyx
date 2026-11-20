package why.lang;

import java.util.List;

/**
 * A user-defined function or a block: parameters, body, the scope it closes over, and the file
 * it was written in (null for blocks).
 */
public record Function(List<String> params, List<Ast.Stmt> body, Scope closure, String file) {}
