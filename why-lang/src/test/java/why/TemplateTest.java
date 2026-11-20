package why;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import why.lang.ExecutionBudget;
import why.lang.Interpreter;
import why.lang.Scope;
import why.tpl.Template;
import why.tpl.TemplateParser;

class TemplateTest {

    private static String render(String source, Map<String, Object> vars, Map<String, Template> partials) {
        Scope scope = new Scope(null);
        vars.forEach(scope::define);
        Interpreter interpreter = new Interpreter(Map.of(), scope);
        Template template = TemplateParser.parse(source, "test.why", 1);
        template.validatePartials(partials);
        return template.render(interpreter, scope, partials);
    }

    @Test
    void echoesAreEscapedByDefault() {
        String out = render("<h1>{{title}}</h1>{{{title}}}{{& title}}", Map.of("title", "<b>why & co</b>"), Map.of());
        assertEquals("<h1>&lt;b&gt;why &amp; co&lt;/b&gt;</h1><b>why & co</b><b>why & co</b>", out);
    }

    @Test
    void eachExposesItAndIndex() {
        assertEquals("[0:a][1:b]", render("{{#each items}}[{{@index}}:{{it}}]{{/each}}",
                Map.of("items", List.of("a", "b")), Map.of()));
        assertEquals("first", render("{{#each items}}{{#if @first}}first{{/if}}{{/each}}",
                Map.of("items", List.of("a", "b")), Map.of()));
    }

    @Test
    void eachRendersElseWhenEmpty() {
        assertEquals("none", render("{{#each items}}x{{else}}none{{/each}}", Map.of("items", List.of()), Map.of()));
        assertEquals("none", render("{{#each items}}x{{else}}none{{/each}}", Map.of(), Map.of()));
        assertEquals("none", render("{{#each items}}x{{else}}none{{/each}}", Map.of("items", false), Map.of()));
    }

    @Test
    void ifUnlessAndElse() {
        assertEquals("yes", render("{{#if ok}}yes{{else}}no{{/if}}", Map.of("ok", true), Map.of()));
        assertEquals("no", render("{{#if ok}}yes{{else}}no{{/if}}", Map.of("ok", false), Map.of()));
        assertEquals("no", render("{{#unless ok}}yes{{else}}no{{/unless}}", Map.of("ok", true), Map.of()));
        assertEquals("yes", render("{{#unless ok}}yes{{else}}no{{/unless}}", Map.of("ok", false), Map.of()));
    }

    @Test
    void emptyListLengthIsTruthyButNeedsAnExplicitComparison() {
        assertEquals("yes", render("{{#if posts.length}}yes{{else}}no{{/if}}",
                Map.of("posts", List.of()), Map.of()));
        assertEquals("no", render("{{#if posts.length > 0}}yes{{else}}no{{/if}}",
                Map.of("posts", List.of()), Map.of()));
        assertEquals("yes", render("{{#if posts.length > 0}}yes{{else}}no{{/if}}",
                Map.of("posts", List.of("one")), Map.of()));
    }

    @Test
    void partialsSeeTheCurrentScope() {
        Map<String, Template> partials = Map.of(
                "header", TemplateParser.parse("<header>{{site}}</header>", "header.why", 2));
        String out = render("{{> header}}<main>{{title}}</main>", Map.of("site", "Why", "title", "Home"), partials);
        assertEquals("<header>Why</header><main>Home</main>", out);
    }

    @Test
    void hashIterationExposesKey() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", "Why");
        row.put("views", 3L);
        assertEquals("title=Why;views=3;",
                render("{{#each row}}{{@key}}={{it}};{{/each}}", Map.of("row", row), Map.of()));
    }

    @Test
    void commentsAreDropped() {
        assertEquals("ab", render("a{{! not rendered }}b", Map.of(), Map.of()));
    }

    @Test
    void unclosedBlockIsACompileError() {
        WhyError error = assertThrows(WhyError.class, () -> render("{{#each items}}x", Map.of(), Map.of()));
        assertEquals(1, error.line());
        assertTrue(error.getMessage().contains("unclosed"));
    }

    @Test
    void unknownPartialsAreCompileErrors() {
        WhyError error = assertThrows(WhyError.class, () -> render("{{> nope}}", Map.of(), Map.of()));
        assertTrue(error.getMessage().contains("unknown partial"));
    }

    @Test
    void outputBudgetStopsOversizedViews() {
        Interpreter interpreter = new Interpreter(Map.of(), new Scope(null),
                new ExecutionBudget(100, 4, Long.MAX_VALUE));
        Template template = TemplateParser.parse("abcdef", "test.why", 1);

        WhyError error = assertThrows(WhyError.class,
                () -> template.render(interpreter, new Scope(null), Map.of()));
        assertTrue(error.getMessage().contains("output budget"));
    }

    @Test
    void recursivePartialsStopAtTheDepthBudget() {
        Template recursive = TemplateParser.parse("{{> self}}", "loop.why", 1);
        Map<String, Template> partials = Map.of("self", recursive);

        WhyError error = assertThrows(WhyError.class, () -> render("{{> self}}", Map.of(), partials));
        assertTrue(error.getMessage().contains("partial depth budget"));
    }
}
