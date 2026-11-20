package why;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import why.lang.Ast;
import why.lang.Interpreter;
import why.tpl.Template;
import why.util.Suggest;

/** Compiles every page without serving it, reports problems, then prints the page map. */
public final class Checker {
    private static final List<String> KINDS =
            List.of("imports", "partials", "defs", "functions", "urls", "other");

    private final Site site;
    private final PageCompiler compiler;
    private final Map<String, List<String>> problems = new LinkedHashMap<>();

    public Checker(Site site) {
        this.site = site;
        this.compiler = new PageCompiler(site);
        for (String kind : KINDS) problems.put(kind, new ArrayList<>());
    }

    /** Returns 0 when nothing is wrong, 1 otherwise. */
    public int check(PrintStream out) {
        List<PageFile> pages = discover();

        Map<String, List<PageFile>> byUrl = new LinkedHashMap<>();
        for (PageFile page : pages) byUrl.computeIfAbsent(page.url(), url -> new ArrayList<>()).add(page);
        for (Map.Entry<String, List<PageFile>> entry : byUrl.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            List<String> files = entry.getValue().stream().map(PageFile::display).toList();
            add("urls", entry.getKey(), String.join(" and ", files));
        }

        List<PageInfo> infos = new ArrayList<>();
        for (PageFile page : pages) {
            Program program = null;
            try {
                program = compiler.compile(page.file());
            } catch (WhyError e) {
                add(kindOf(e), e.location(), e.getMessage());
            }
            if (program != null) {
                duplicateDefs(page, program);
                undefinedCalls(page, program);
            }
            infos.add(new PageInfo(page, program));
        }

        print(out, pages.size(), infos);
        return total() == 0 ? 0 : 1;
    }

    // ---- collecting ----------------------------------------------------

    private List<PageFile> discover() {
        try (Stream<Path> walk = Files.walk(site.root())) {
            return walk.filter(Files::isRegularFile)
                    .filter(site::isPage)
                    .filter(file -> !Site.isImportOnly(file)) // import-only files have no URL
                    .map(file -> new PageFile(file, site.relative(file), urlOf(file)))
                    .sorted(Comparator.comparing(PageFile::url).thenComparing(PageFile::display))
                    .toList();
        } catch (IOException e) {
            add("other", site.root().toString(), "cannot read the site folder: " + e.getMessage());
            return List.of();
        }
    }

    /** {@code a.why} and {@code a/index.why} both answer /a; that is the collision we look for. */
    private String urlOf(Path file) {
        String display = site.relative(file);
        String stem = display.substring(0, display.length() - Site.PAGE_EXTENSION.length());
        if (stem.equals("index")) return "/";
        if (stem.endsWith("/index")) return "/" + stem.substring(0, stem.length() - "/index".length());
        return "/" + stem;
    }

    private void duplicateDefs(PageFile page, Program program) {
        Map<String, List<Program.DefOrigin>> byName = new LinkedHashMap<>();
        for (Program.DefOrigin origin : program.defOrigins()) {
            byName.computeIfAbsent(origin.name(), name -> new ArrayList<>()).add(origin);
        }
        for (Map.Entry<String, List<Program.DefOrigin>> entry : byName.entrySet()) {
            List<Program.DefOrigin> origins = dedupe(entry.getValue());
            if (origins.size() < 2) continue;
            long local = origins.stream().filter(origin -> origin.file().equals(page.display())).count();
            long imported = origins.size() - local;
            // One local def overriding one import is documented behaviour, not a duplicate.
            if (local < 2 && imported < 2) continue;
            List<String> where = origins.stream().map(origin -> origin.file() + ":" + origin.line()).toList();
            Program.DefOrigin first = origins.get(0);
            add("defs", first.file() + ":" + first.line(),
                    "def '" + entry.getKey() + "' is defined " + origins.size() + " times: "
                            + String.join(", ", where));
        }
    }

    private static List<Program.DefOrigin> dedupe(List<Program.DefOrigin> origins) {
        Set<String> seen = new HashSet<>();
        List<Program.DefOrigin> out = new ArrayList<>();
        for (Program.DefOrigin origin : origins) {
            if (seen.add(origin.file() + ":" + origin.line() + ":" + origin.name())) out.add(origin);
        }
        return out;
    }

    private void undefinedCalls(PageFile page, Program program) {
        Set<String> known = new HashSet<>(program.defs().keySet());
        known.addAll(Interpreter.BUILTINS);
        collectDefs(program.body(), known);

        List<CallSite> undefined = new ArrayList<>();
        findCalls(program.body(), known, undefined);
        findTemplateCalls(program.view().nodes(), known, undefined);

        Set<String> seen = new HashSet<>();
        for (CallSite call : undefined) {
            if (!seen.add(call.line() + ":" + call.name())) continue;
            add("functions", page.display() + ":" + call.line(),
                    "undefined function '" + call.name() + "'" + Suggest.didYouMean(call.name(), known));
        }
    }

    /** Defs can be declared inside functions; count them all so a call does not look undefined. */
    private static void collectDefs(List<Ast.Stmt> body, Set<String> known) {
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.Def def) {
                known.add(def.name());
                collectDefs(def.body(), known);
            } else if (stmt instanceof Ast.If branch) {
                for (Ast.Branch candidate : branch.branches()) {
                    collectExpr(candidate.cond(), known);
                    collectDefs(candidate.body(), known);
                }
                collectDefs(branch.otherwise(), known);
            } else if (stmt instanceof Ast.While loop) {
                collectExpr(loop.cond(), known);
                collectDefs(loop.body(), known);
            } else if (stmt instanceof Ast.Assign assign) {
                collectExpr(assign.value(), known);
            } else if (stmt instanceof Ast.ExprStmt e) {
                collectExpr(e.expr(), known);
            } else if (stmt instanceof Ast.Return ret && ret.value() != null) {
                collectExpr(ret.value(), known);
            }
        }
    }

    private static void collectExpr(Ast.Expr expr, Set<String> known) {
        if (expr instanceof Ast.Call call) {
            if (call.target() != null) collectExpr(call.target(), known);
            for (Ast.Expr arg : call.args()) collectExpr(arg, known);
            if (call.block() != null) collectDefs(call.block().body(), known);
        } else if (expr instanceof Ast.Binary binary) {
            collectExpr(binary.left(), known);
            collectExpr(binary.right(), known);
        } else if (expr instanceof Ast.Unary unary) {
            collectExpr(unary.operand(), known);
        } else if (expr instanceof Ast.Interp interp) {
            for (Ast.Expr part : interp.parts()) collectExpr(part, known);
        } else if (expr instanceof Ast.ArrayLit array) {
            for (Ast.Expr item : array.items()) collectExpr(item, known);
        } else if (expr instanceof Ast.HashLit hash) {
            for (Ast.Pair pair : hash.pairs()) {
                collectExpr(pair.key(), known);
                collectExpr(pair.value(), known);
            }
        } else if (expr instanceof Ast.Get get) {
            collectExpr(get.target(), known);
        } else if (expr instanceof Ast.Index index) {
            collectExpr(index.target(), known);
            collectExpr(index.index(), known);
        }
    }

    private static void findCalls(List<Ast.Stmt> body, Set<String> known, List<CallSite> undefined) {
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.Def def) {
                findCalls(def.body(), known, undefined);
            } else if (stmt instanceof Ast.If branch) {
                for (Ast.Branch candidate : branch.branches()) {
                    findExpr(candidate.cond(), known, undefined);
                    findCalls(candidate.body(), known, undefined);
                }
                findCalls(branch.otherwise(), known, undefined);
            } else if (stmt instanceof Ast.While loop) {
                findExpr(loop.cond(), known, undefined);
                findCalls(loop.body(), known, undefined);
            } else if (stmt instanceof Ast.Assign assign) {
                findExpr(assign.value(), known, undefined);
            } else if (stmt instanceof Ast.ExprStmt e) {
                findExpr(e.expr(), known, undefined);
            } else if (stmt instanceof Ast.Return ret && ret.value() != null) {
                findExpr(ret.value(), known, undefined);
            }
        }
    }

    private static void findExpr(Ast.Expr expr, Set<String> known, List<CallSite> undefined) {
        if (expr instanceof Ast.Call call) {
            if (call.target() == null && !known.contains(call.name())) {
                undefined.add(new CallSite(call.name(), call.line()));
            }
            if (call.target() != null) findExpr(call.target(), known, undefined);
            for (Ast.Expr arg : call.args()) findExpr(arg, known, undefined);
            if (call.block() != null) findCalls(call.block().body(), known, undefined);
        } else if (expr instanceof Ast.Binary binary) {
            findExpr(binary.left(), known, undefined);
            findExpr(binary.right(), known, undefined);
        } else if (expr instanceof Ast.Unary unary) {
            findExpr(unary.operand(), known, undefined);
        } else if (expr instanceof Ast.Interp interp) {
            for (Ast.Expr part : interp.parts()) findExpr(part, known, undefined);
        } else if (expr instanceof Ast.ArrayLit array) {
            for (Ast.Expr item : array.items()) findExpr(item, known, undefined);
        } else if (expr instanceof Ast.HashLit hash) {
            for (Ast.Pair pair : hash.pairs()) {
                findExpr(pair.key(), known, undefined);
                findExpr(pair.value(), known, undefined);
            }
        } else if (expr instanceof Ast.Get get) {
            findExpr(get.target(), known, undefined);
        } else if (expr instanceof Ast.Index index) {
            findExpr(index.target(), known, undefined);
            findExpr(index.index(), known, undefined);
        }
    }

    private static void findTemplateCalls(List<Template.Node> nodes, Set<String> known,
                                          List<CallSite> undefined) {
        for (Template.Node node : nodes) {
            if (node instanceof Template.Echo echo) {
                findExpr(echo.expr(), known, undefined);
            } else if (node instanceof Template.Block block) {
                findExpr(block.cond(), known, undefined);
                findTemplateCalls(block.body(), known, undefined);
                findTemplateCalls(block.otherwise(), known, undefined);
            } else if (node instanceof Template.Each each) {
                findExpr(each.expr(), known, undefined);
                findTemplateCalls(each.body(), known, undefined);
                findTemplateCalls(each.otherwise(), known, undefined);
            }
        }
    }

    // ---- output --------------------------------------------------------

    private void print(PrintStream out, int count, List<PageInfo> infos) {
        out.println("checking " + count + (count == 1 ? " page" : " pages") + " in " + site.root());
        out.println();
        out.println("problems");
        if (total() == 0) {
            out.println("  none");
        } else {
            for (Map.Entry<String, List<String>> entry : problems.entrySet()) {
                if (entry.getValue().isEmpty()) continue;
                out.println("  " + entry.getKey());
                for (String problem : entry.getValue()) out.println("    " + problem);
            }
        }

        out.println();
        out.println("page map");
        if (infos.isEmpty()) {
            out.println("  (no pages)");
        } else {
            int urlWidth = infos.stream().mapToInt(info -> info.page().url().length()).max().orElse(0);
            int fileWidth = infos.stream().mapToInt(info -> info.page().display().length()).max().orElse(0);
            for (PageInfo info : infos) {
                StringBuilder line = new StringBuilder("  ")
                        .append(pad(info.page().url(), urlWidth)).append("  ")
                        .append(pad(info.page().display(), fileWidth));
                if (info.program() == null) {
                    line.append("  (compile error)");
                } else {
                    if (!info.program().importNames().isEmpty()) {
                        line.append("  imports: ").append(String.join(", ", info.program().importNames()));
                    }
                    Set<String> partials = partialNames(info.program().view().nodes());
                    if (!partials.isEmpty()) line.append("  partials: ").append(String.join(", ", partials));
                    List<String> defs = localDefs(info);
                    if (!defs.isEmpty()) line.append("  defs: ").append(String.join(", ", defs));
                }
                out.println(line.toString().stripTrailing());
            }
        }

        out.println();
        int found = total();
        out.println("why check: "
                + (found == 0 ? "no problems" : found + (found == 1 ? " problem" : " problems"))
                + " in " + count + (count == 1 ? " page" : " pages"));
    }

    private static Set<String> partialNames(List<Template.Node> nodes) {
        Set<String> names = new TreeSet<>();
        collectPartialNames(nodes, names);
        return names;
    }

    private static void collectPartialNames(List<Template.Node> nodes, Set<String> names) {
        for (Template.Node node : nodes) {
            if (node instanceof Template.Partial partial) {
                names.add(partial.name());
            } else if (node instanceof Template.Block block) {
                collectPartialNames(block.body(), names);
                collectPartialNames(block.otherwise(), names);
            } else if (node instanceof Template.Each each) {
                collectPartialNames(each.body(), names);
                collectPartialNames(each.otherwise(), names);
            }
        }
    }

    private static List<String> localDefs(PageInfo info) {
        Set<String> names = new TreeSet<>();
        for (Program.DefOrigin origin : info.program().defOrigins()) {
            if (origin.file().equals(info.page().display())) names.add(origin.name());
        }
        return new ArrayList<>(names);
    }

    private void add(String kind, String location, String message) {
        problems.get(kind).add((location.isEmpty() ? "" : location + ": ") + message);
    }

    private int total() {
        return problems.values().stream().mapToInt(List::size).sum();
    }

    private static String kindOf(WhyError error) {
        String message = error.getMessage();
        if (message.startsWith("cannot find import") || message.startsWith("circular import")) return "imports";
        if (message.startsWith("unknown partial")) return "partials";
        return "other";
    }

    private static String pad(String text, int width) {
        return text + " ".repeat(Math.max(0, width - text.length()));
    }

    private record PageFile(Path file, String display, String url) {}

    private record PageInfo(PageFile page, Program program) {}

    private record CallSite(String name, int line) {}
}
