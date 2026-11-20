package why;

import java.util.List;
import java.util.Map;

import why.lang.Ast;
import why.lang.ExecutionBudget;
import why.lang.Halt;
import why.lang.Interpreter;
import why.lang.Sql;
import why.lang.Scope;
import why.tpl.Template;

/** A compiled page: controller statements, functions, partials and the view. */
public final class Program {

    /** A top-level def and where it was written. */
    public record DefOrigin(String name, String file, int line) {}

    private final String file;
    private final List<String> importNames;
    private final List<Ast.Stmt> body;
    private final Map<String, Ast.Def> defs;
    private final Map<String, Template> partials;
    private final Template view;
    private final List<DefOrigin> defOrigins;

    Program(String file, List<String> importNames, List<Ast.Stmt> body, Map<String, Ast.Def> defs,
            Map<String, Template> partials, Template view, List<DefOrigin> defOrigins) {
        this.file = file;
        this.importNames = importNames;
        this.body = body;
        this.defs = defs;
        this.partials = partials;
        this.view = view;
        this.defOrigins = defOrigins;
    }

    String file() {
        return file;
    }

    List<String> importNames() {
        return importNames;
    }

    List<Ast.Stmt> body() {
        return body;
    }

    Map<String, Ast.Def> defs() {
        return defs;
    }

    Template view() {
        return view;
    }

    /** Every top-level def seen while compiling, local and imported, in collection order. */
    List<DefOrigin> defOrigins() {
        return defOrigins;
    }

    /** Runs the controller against the request globals, then renders the view. */
    public String render(Map<String, Object> globals) {
        return render(globals, ExecutionBudget.unlimited());
    }

    /** Runs the controller and view under explicit step/output/deadline limits. */
    public String render(Map<String, Object> globals, ExecutionBudget budget) {
        Scope scope = new Scope(null);
        globals.forEach(scope::define);
        if (scope.get("sql") == null) scope.define("sql", new Sql());
        Interpreter interpreter = new Interpreter(defs, scope, budget);
        try {
            interpreter.exec(body, scope);
        } catch (Halt stop) {
            return "";
        } catch (WhyError e) {
            throw e.locate(file, e.line());
        }
        try {
            return view.render(interpreter, scope, partials);
        } catch (Halt stop) {
            return "";
        }
    }
}
