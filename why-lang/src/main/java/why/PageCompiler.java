package why;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import why.lang.Ast;
import why.lang.Parser;
import why.tpl.Template;
import why.tpl.TemplateParser;

/** Loads a page, resolves its imports and compiles everything into a {@link Program}. */
public final class PageCompiler {
    private final Site site;
    private final PageLoader loader;

    public PageCompiler(Site site) {
        this.site = site;
        this.loader = new PageLoader(site);
    }

    public Program compile(Path file) {
        Page page = loader.load(file);
        String display = site.relative(file);

        List<Ast.Stmt> body = new Parser(page.controllerSource(), display, page.controllerLine()).parseProgram();

        Map<String, Ast.Def> defs = new LinkedHashMap<>();
        List<Program.DefOrigin> defOrigins = new ArrayList<>();
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.Def def) {
                defs.put(def.name(), def);
                defOrigins.add(new Program.DefOrigin(def.name(), def.file(), def.line()));
            }
        }

        Map<String, Template> partials = new LinkedHashMap<>();
        Map<Template, List<WhyError.Frame>> origins = new LinkedHashMap<>();
        List<Path> stack = new ArrayList<>();
        stack.add(canonical(file));
        collect(page, display, defs, partials, stack, List.of(), origins, defOrigins);

        Template view = TemplateParser.parse(page.viewSource(), display, page.viewLine());
        view.validatePartials(partials);
        for (Map.Entry<Template, List<WhyError.Frame>> entry : origins.entrySet()) {
            try {
                entry.getKey().validatePartials(partials);
            } catch (WhyError e) {
                List<WhyError.Frame> via = entry.getValue();
                for (int i = via.size() - 1; i >= 0; i--) e.at(via.get(i).file(), via.get(i).line());
                throw e;
            }
        }

        return new Program(display, page.imports().stream().map(Page.Import::name).toList(), body, defs,
                partials, view, defOrigins);
    }

    /**
     * Adds the imports' views as partials and their top-level {@code def}s as functions.
     * Imports are transitive; the importing page's own defs win over imported ones.
     * Top-level controller statements in an imported page are not executed.
     */
    private void collect(Page page, String display, Map<String, Ast.Def> defs,
                         Map<String, Template> partials, List<Path> stack,
                         List<WhyError.Frame> via, Map<Template, List<WhyError.Frame>> origins,
                         List<Program.DefOrigin> defOrigins) {
        for (Page.Import imp : page.imports()) {
            List<WhyError.Frame> next = new ArrayList<>(via);
            next.add(new WhyError.Frame(display, imp.line()));
            try {
                Path imported = resolve(page.file(), imp.name());
                if (imported == null) {
                    throw new WhyError("cannot find import '" + imp.name() + "'", display, imp.line());
                }
                Path normalized = canonical(imported);
                if (stack.contains(normalized)) {
                    throw new WhyError("circular import '" + imp.name() + "'", display, imp.line());
                }

                Page importedPage = loader.load(imported);
                String importedDisplay = site.relative(imported);
                Template importedView = TemplateParser.parse(
                        importedPage.viewSource(), importedDisplay, importedPage.viewLine());
                partials.putIfAbsent(imp.name(), importedView);
                origins.putIfAbsent(importedView, next);

                List<Ast.Stmt> importedBody = new Parser(
                        importedPage.controllerSource(), importedDisplay, importedPage.controllerLine()).parseProgram();
                for (Ast.Stmt stmt : importedBody) {
                    if (stmt instanceof Ast.Def def) {
                        defs.putIfAbsent(def.name(), def);
                        defOrigins.add(new Program.DefOrigin(def.name(), def.file(), def.line()));
                    }
                }

                stack.add(normalized);
                collect(importedPage, importedDisplay, defs, partials, stack, next, origins, defOrigins);
                stack.remove(stack.size() - 1);
            } catch (WhyError e) {
                throw e.at(display, imp.line());
            }
        }
    }

    /** Resolves symlink aliases so a symlinked import cannot evade cycle detection. */
    private static Path canonical(Path file) {
        try {
            return file.toRealPath();
        } catch (IOException e) {
            return file.toAbsolutePath().normalize();
        }
    }

    private Path resolve(Path pageFile, String name) {
        Path directory = pageFile.toAbsolutePath().getParent();
        Path local = directory == null ? null : directory.resolve(name + Site.PAGE_EXTENSION);
        if (local != null && Files.isRegularFile(local)) {
            return site.isInsideSite(local) ? local.normalize() : null;
        }
        Path inRoot = site.root().resolve(name + Site.PAGE_EXTENSION).normalize();
        return Files.isRegularFile(inRoot) && site.isInsideSite(inRoot) ? inRoot : null;
    }
}
