package why;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import why.lang.Db;

class ServerTest {

    @TempDir
    Path dir;

    private WhyServer server;
    private HttpClient client;

    @BeforeEach
    void start() throws IOException {
        write("header.why", "view:\n<header>site</header>\n");
        write("index.why", """
                imports:
                header

                controller:
                posts = db.query("select title from posts order by id")
                count = posts.length

                view:
                {{> header}}
                {{#each posts}}<h2>{{it.title}}</h2>{{/each}}
                <p>count={{count}}</p>
                """);
        write("plain.why", "<h1>plain</h1>\n");
        write("broken.why", "controller:\nif\nend\n\nview:\nnever\n");
        write("style.css", "body{color:red}\n");
        write("token.why", "view:\n{{csrf.token}}\n");

        try (Db db = new Db(dir.resolve("data.db"))) {
            db.exec("create table posts (id integer primary key, title text)", List.of());
            db.exec("insert into posts (id, title) values (1, 'first'), (2, 'second')", List.of());
        }

        server = new WhyServer(new Site(dir), "127.0.0.1", 0, true, false);
        server.start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String session) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Cookie", "why_session=" + session)
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Posts the way a browser would: fetch a token from a page, then submit it with the form. */
    private HttpResponse<String> postWithToken(String path, String body) throws Exception {
        HttpResponse<String> tokenPage = get("/token");
        String setCookie = tokenPage.headers().firstValue("set-cookie").orElse("");
        assertTrue(setCookie.startsWith("why_csrf="), setCookie);
        String token = setCookie.substring("why_csrf=".length(), setCookie.indexOf(';'));
        String payload = body.isEmpty() ? "_token=" + token : body + "&_token=" + token;

        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", "why_csrf=" + token)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theRequestCarriesTheClientAddress() throws Exception {
        write("remote.why", "view:\n{{request.remote}}\n");
        assertEquals("127.0.0.1", get("/remote").body().strip());
    }

    @Test
    void formPostsWithoutATokenAreRejected() throws Exception {
        write("sign.why", "controller:\nname = form.name\n\nview:\n{{name}}\n");
        assertEquals(403, post("/sign", "name=why").statusCode());
    }

    @Test
    void validateReportsMissingFields() throws Exception {
        write("join.why", """
                controller:
                errors = validate(form, {name: "required", note: "min:3"})

                view:
                {{#if errors.length > 0}}bad {{errors.name}}{{else}}ok{{/if}}
                """);

        assertTrue(postWithToken("/join", "").body().contains("bad is required"),
                postWithToken("/join", "").body());
        assertEquals("ok\n", postWithToken("/join", "name=why&note=long").body());
    }

    @Test
    void bindsToLoopbackByDefault() throws Exception {
        server.stop();
        server = new WhyServer(new Site(dir), 0);
        server.start();

        assertTrue(server.address().getAddress().isLoopbackAddress());
        assertEquals(200, get("/").statusCode());
    }

    @Test
    void explicitHostCanExposeTheServer() throws Exception {
        server.stop();
        server = new WhyServer(new Site(dir), "0.0.0.0", 0, false, false);
        server.start();

        assertTrue(server.address().getAddress().isAnyLocalAddress());
    }

    @Test
    void developmentDiagnosticsAreOptIn() throws Exception {
        server.stop();
        server = new WhyServer(new Site(dir), "127.0.0.1", 0, true, false);
        server.start();

        HttpResponse<String> response = get("/broken");
        assertEquals(500, response.statusCode());
        assertTrue(response.body().contains("broken.why:2"));
        assertTrue(response.body().contains("unexpected end of input"));
    }

    @Test
    void rendersAPageWithImportsAndDbRows() throws Exception {
        HttpResponse<String> response = get("/");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("text/html"));
        assertTrue(response.body().contains("<header>site</header>"));
        assertTrue(response.body().contains("<h2>first</h2><h2>second</h2>"));
        assertTrue(response.body().contains("<p>count=2</p>"));
    }

    @Test
    void servesPlainPagesAndExtensionFreeUrls() throws Exception {
        assertEquals("<h1>plain</h1>\n", get("/plain.why").body());
        assertEquals("<h1>plain</h1>\n", get("/plain").body());
    }

    @Test
    void sendsNosniffByDefault() throws Exception {
        assertEquals("nosniff", header(get("/"), "x-content-type-options"));
    }

    @Test
    void sendsReferrerPolicyByDefault() throws Exception {
        assertEquals("no-referrer", header(get("/"), "referrer-policy"));
    }

    @Test
    void sendsFrameAncestorsByDefault() throws Exception {
        assertEquals("frame-ancestors 'none'", header(get("/"), "content-security-policy"));
    }

    @Test
    void sendsXFrameOptionsByDefault() throws Exception {
        assertEquals("DENY", header(get("/"), "x-frame-options"));
    }

    @Test
    void pagesCanOptIntoAContentSecurityPolicy() throws Exception {
        write("csp.why", """
                controller:
                response.csp("default-src 'self'; img-src 'self' data:")

                view:
                <h1>ok</h1>
                """);
        write("framed.why", """
                controller:
                response.csp("frame-ancestors 'self'")

                view:
                <h1>framed</h1>
                """);

        assertEquals("default-src 'self'; img-src 'self' data:; frame-ancestors 'none'",
                header(get("/csp"), "content-security-policy"));
        assertEquals("frame-ancestors 'self'", header(get("/framed"), "content-security-policy"));
    }

    @Test
    void servesStaticFilesAndReportsMisses() throws Exception {
        HttpResponse<String> css = get("/style.css");
        assertEquals(200, css.statusCode());
        assertEquals("text/css; charset=utf-8", css.headers().firstValue("content-type").orElse(""));
        assertEquals("body{color:red}\n", css.body());
        assertEquals(404, get("/nope").statusCode());
    }

    @Test
    void pagesCanSetTheStatusAndHeaders() throws Exception {
        write("created.why", """
                controller:
                response.status(201)
                response.header("X-Why", "created")

                view:
                <p>made</p>
                """);

        HttpResponse<String> response = get("/created");
        assertEquals(201, response.statusCode());
        assertEquals("created", header(response, "x-why"));
        assertEquals("<p>made</p>\n", response.body());
    }

    @Test
    void pagesCanAnswerJson() throws Exception {
        write("data.why", """
                controller:
                data = {ok: true, count: 2, tags: ["a", "b"]}
                response.json(data)

                view:
                ignored
                """);

        HttpResponse<String> response = get("/data");
        assertEquals(200, response.statusCode());
        assertEquals("{\"ok\":true,\"count\":2,\"tags\":[\"a\",\"b\"]}", response.body());
        assertTrue(header(response, "content-type").startsWith("application/json"),
                header(response, "content-type"));
    }

    @Test
    void pagesCanServeCustomNotFoundResponses() throws Exception {
        write("missing.why", """
                controller:
                response.status(404)

                view:
                <h1>gone</h1>
                """);

        assertEquals(404, get("/missing").statusCode());
    }

    @Test
    void repeatedAssetRequestsAnswerNotModified() throws Exception {
        HttpResponse<String> first = get("/style.css");
        String etag = header(first, "etag");
        assertFalse(etag.isEmpty(), "assets should carry an ETag");

        HttpResponse<String> second = client.send(
                HttpRequest.newBuilder(uri("/style.css")).header("If-None-Match", etag).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(304, second.statusCode());
    }

    @Test
    void largeAssetsAreCompressedWhenTheClientAsks() throws Exception {
        write("big.css", "body{color:red}\n".repeat(200));
        HttpResponse<byte[]> response = client.send(
                HttpRequest.newBuilder(uri("/big.css")).header("Accept-Encoding", "gzip").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals("gzip", header(response, "content-encoding"));
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("body{color:red}"));
        }
    }

    @Test
    void healthAnswersWithoutTouchingAPage() throws Exception {
        HttpResponse<String> response = get("/health");
        assertEquals(200, response.statusCode());
        assertEquals("ok", response.body());
        assertFalse(header(response, "x-request-id").isEmpty(), "responses should carry a request id");
    }

    @Test
    void oversizedStaticAssetsAreRejected() throws Exception {
        Files.write(dir.resolve("big.css"), new byte[(int) AllocationLimits.STATIC_ASSET_BYTES + 1]);

        HttpResponse<String> response = get("/big.css");
        assertEquals(500, response.statusCode());
        assertTrue(response.body().contains("static asset"), response.body());
        assertTrue(response.body().contains("limit"), response.body());
    }

    @Test
    void oversizedFlashValuesAreRejected() throws Exception {
        write("big-flash.why", "controller:\nflash.set(\"notice\", \""
                + "x".repeat(AllocationLimits.FLASH_VALUE_BYTES + 1)
                + "\")\n\nview:\nok\n");

        HttpResponse<String> response = get("/big-flash");
        assertEquals(500, response.statusCode());
        assertTrue(response.body().contains("flash value"), response.body());
        assertTrue(response.body().contains("" + AllocationLimits.FLASH_VALUE_BYTES), response.body());
    }

    @Test
    void oversizedBodiesAreRejectedBeforePageWork() throws Exception {
        write("accept.why", "view:\nok\n");
        String body = "name=" + "x".repeat(65 * 1024);

        HttpResponse<String> response = post("/accept", body);
        assertEquals(413, response.statusCode());
    }

    @Test
    void oversizedHeadersAreRejected() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/plain"))
                .header("X-Big", "x".repeat(17 * 1024))
                .GET()
                .build();

        assertEquals(431, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void slowQueriesStopAtTheRequestTimeoutAndReleaseTheirSlot() throws Exception {
        write("slow-query.why", """
                controller:
                db.query("with recursive cnt(x) as (select 1 union all select x + 1 from cnt limit 1000000000) select sum(x) from cnt")

                view:
                never
                """);

        server.stop();
        server = new WhyServer(new Site(dir), "127.0.0.1", 0, true, false, 500);
        server.start();

        long started = System.nanoTime();
        try {
            assertEquals(504, get("/slow-query").statusCode());
        } catch (IOException e) {
            // The watchdog may close the exchange before the 504 is sent.
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertTrue(elapsedMillis >= 400, "request did not wait for the timeout: " + elapsedMillis + "ms");
        assertTrue(elapsedMillis < 5_000, "request took " + elapsedMillis + "ms");

        long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (server.freeRequestSlots() < 32 && System.nanoTime() < waitUntil) Thread.sleep(20);
        assertEquals(32, server.freeRequestSlots(), "timed-out request still holds its slot");
    }

    @Test
    void runawayPagesStopAtTheInterpreterStepBudget() throws Exception {
        write("runaway.why", "controller:\nwhile true\nend\n\nview:\nnever\n");

        HttpResponse<String> response = get("/runaway");
        assertEquals(500, response.statusCode());
        assertTrue(response.body().contains("step budget"));
    }

    @Test
    void recursivePagesStopAtTheCallDepthBudget() throws Exception {
        write("recursive.why", """
                controller:
                def recurse
                  recurse()
                end
                recurse()

                view:
                never
                """);

        HttpResponse<String> response = get("/recursive");
        assertEquals(500, response.statusCode());
        assertTrue(response.body().contains("call depth budget"));
    }

    @Test
    void databaseAndSchemaAreNotServed() throws Exception {
        write("schema.sql", "create table notes (id integer);\n");

        assertEquals(404, get("/data.db").statusCode());
        assertEquals(404, get("/schema.sql").statusCode());
    }

    @Test
    void symlinksPointingOutsideTheSiteAreNotServed() throws Exception {
        Path outside = Files.createTempDirectory("why-outside-");
        Path secret = outside.resolve("secret.css");
        Path link = dir.resolve("linked");
        try {
            Files.writeString(secret, "secret", StandardCharsets.UTF_8);
            Assumptions.assumeTrue(createDirectoryLink(link, outside), "symbolic links or junctions are unavailable");

            assertEquals(404, get("/linked/secret.css").statusCode());
        } finally {
            deleteLink(link);
            Files.deleteIfExists(secret);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void diagnosticSourceLinesDoNotFollowLinksOutsideTheSite() throws Exception {
        Path outside = Files.createTempDirectory("why-outside-");
        Path secret = outside.resolve("secret.why");
        Path link = dir.resolve("diagnostics");
        try {
            Files.writeString(secret, "outside secret", StandardCharsets.UTF_8);
            Assumptions.assumeTrue(createDirectoryLink(link, outside), "symbolic links or junctions are unavailable");

            Site site = new Site(dir);
            assertEquals("if", site.sourceLine("broken.why", 2));
            assertNull(site.sourceLine("diagnostics/secret.why", 1));
        } finally {
            deleteLink(link);
            Files.deleteIfExists(secret);
            Files.deleteIfExists(outside);
        }
    }

    /** A directory symlink, or a Windows junction; false when neither is available. */
    private static boolean createDirectoryLink(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | IOException e) {
            if (!isWindows()) return false;
            Process process = new ProcessBuilder(
                    "cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static void deleteLink(Path link) throws Exception {
        try {
            Files.deleteIfExists(link);
        } catch (IOException e) {
            if (!isWindows()) throw e;
            Process process = new ProcessBuilder("cmd", "/c", "rmdir", link.toString())
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            process.waitFor();
        }
    }

    @Test
    void errorsBecomeA500PageWithALocation() throws Exception {
        HttpResponse<String> response = get("/broken");
        assertEquals(500, response.statusCode());
        assertTrue(response.body().contains("broken.why:2"));
    }

    @Test
    void errorsAreGenericAndDoNotLeakSourceOrSqlByDefault() throws Exception {
        write("db-error.why", """
                controller:
                db.query("select * from missing")

                view:
                never
                """);

        server.stop();
        server = new WhyServer(new Site(dir), 0);
        server.start();

        HttpResponse<String> syntaxError = get("/broken");
        assertEquals(500, syntaxError.statusCode());
        String syntaxBody = syntaxError.body();
        assertTrue(syntaxBody.contains("Internal server error"));
        assertFalse(syntaxBody.contains("broken.why"));
        assertFalse(syntaxBody.contains("unexpected end of input"));
        assertFalse(syntaxBody.contains("controller:"));
        assertFalse(syntaxBody.contains(dir.toString()));

        HttpResponse<String> sqlError = get("/db-error");
        assertEquals(500, sqlError.statusCode());
        String sqlBody = sqlError.body();
        assertTrue(sqlBody.contains("Internal server error"));
        assertFalse(sqlBody.contains("db-error.why"));
        assertFalse(sqlBody.contains("db.query failed"));
        assertFalse(sqlBody.contains("no such table"));
        assertFalse(sqlBody.contains("select * from missing"));
        assertFalse(sqlBody.contains("at why."));
        assertFalse(sqlBody.contains(dir.toString()));
    }

    @Test
    void errorPageShowsTheSourceChainAndASuggestion() throws Exception {
        write("header.why", "imports:\nnav\n\nview:\n<header>\n{{> nav}}\n</header>\n");
        write("nav.why", "view:\n<nav>{{str2(\"hi\")}}</nav>\n");
        write("chain.why", """
                imports:
                header

                controller:
                title = "x"

                view:
                {{> header}}
                """);

        HttpResponse<String> response = get("/chain");
        assertEquals(500, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("<p class=\"loc\">nav.why:2</p>"));
        assertTrue(body.contains("chain.why:8"));
        assertTrue(body.contains("header.why:6"));
        assertTrue(body.contains("str2"));
        assertTrue(body.contains("^"));
        assertTrue(body.contains("did you mean"));
    }

    @Test
    void errorPageSuggestsAMisspelledPartial() throws Exception {
        write("page.why", "imports:\nheader\n\nview:\n{{> heder}}\n");

        HttpResponse<String> response = get("/page");
        assertEquals(500, response.statusCode());
        assertTrue(response.body().contains("page.why:5"));
        assertTrue(response.body().contains("heder"));
        assertTrue(response.body().contains("did you mean"));
    }

    @Test
    void compileErrorsInAnImportShowTheImportChain() throws Exception {
        write("leaf.why", "view:\n{{> nope}}\n");
        write("mid.why", "imports:\nleaf\n\nview:\n{{> leaf}}\n");
        write("top.why", "imports:\nmid\n\nview:\n{{> mid}}\n");

        HttpResponse<String> response = get("/top");
        assertEquals(500, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("leaf.why:2"));
        assertTrue(body.contains("top.why:2"));
        assertTrue(body.contains("mid.why:2"));
    }

    @Test
    void syntaxErrorsShowTheOffendingLine() throws Exception {
        write("syntax.why", "controller:\nx = )\n\nview:\nhi\n");

        HttpResponse<String> response = get("/syntax");
        assertEquals(500, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("syntax.why:2"));
        assertTrue(body.contains("x = )"));
        assertTrue(body.contains("^"));
    }

    @Test
    void underscoreFilesAreImportOnly() throws Exception {
        write("_snippet.why", "view:\n<span>snippet</span>\n");
        write("_secret.css", "body{color:black}\n");
        write("uses.why", "imports:\n_snippet\n\nview:\n{{> _snippet}}\n");

        assertEquals(404, get("/_snippet").statusCode());
        assertEquals(404, get("/_snippet.why").statusCode());
        assertEquals(404, get("/_secret.css").statusCode());
        assertEquals("<span>snippet</span>\n\n", get("/uses").body());
    }

    @Test
    void externalRedirectsAreRejected() throws Exception {
        write("external-redirect.why", """
                controller:
                redirect("https://example.com/")

                view:
                never
                """);

        HttpResponse<String> response = get("/external-redirect");
        assertEquals(500, response.statusCode());
        assertTrue(response.body().contains("same-origin"));
    }

    @Test
    void flashIsCarriedAcrossOneRedirect() throws Exception {
        write("notice.why", """
                controller:
                if request.method == "POST"
                  flash.set("notice", "saved")
                  redirect("/notice")
                end

                view:
                {{#if flash.notice}}<p>{{flash.notice}}</p>{{/if}}seen
                """);

        HttpResponse<String> posted = postWithToken("/notice", "");
        assertEquals(303, posted.statusCode());
        assertEquals("/notice", posted.headers().firstValue("location").orElse(""));

        String setCookie = posted.headers().firstValue("set-cookie").orElse("");
        assertTrue(setCookie.startsWith("why_session="));
        int semicolon = setCookie.indexOf(';');
        assertTrue(semicolon > "why_session=".length());
        String session = setCookie.substring("why_session=".length(), semicolon);

        HttpResponse<String> first = get("/notice", session);
        assertEquals(200, first.statusCode());
        assertTrue(first.body().contains("<p>saved</p>"));

        HttpResponse<String> second = get("/notice", session);
        assertEquals(200, second.statusCode());
        assertTrue(second.body().contains("seen"));
        assertFalse(second.body().contains("<p>saved</p>"));
    }

    @Test
    void clientSuppliedSessionIdsAreIgnored() throws Exception {
        write("set-flash.why", "controller:\nflash.set(\"notice\", \"saved\")\n\nview:\nok\n");

        HttpResponse<String> response = get("/set-flash", "attacker-chosen");
        assertEquals(200, response.statusCode());
        String setCookie = response.headers().firstValue("set-cookie").orElse("");
        assertTrue(setCookie.startsWith("why_session="));
        assertFalse(setCookie.startsWith("why_session=attacker-chosen"));
    }

    @Test
    void secureCookiesAreMarkedWhenTlsIsConfigured() throws Exception {
        write("notice.why", """
                controller:
                if request.method == "POST"
                  flash.set("notice", "saved")
                  redirect("/notice")
                end

                view:
                seen
                """);

        server.stop();
        server = new WhyServer(new Site(dir), "127.0.0.1", 0, true, true);
        server.start();

        HttpResponse<String> posted = postWithToken("/notice", "");
        assertEquals(303, posted.statusCode());
        String setCookie = posted.headers().firstValue("set-cookie").orElse("");
        assertTrue(setCookie.startsWith("why_session="));
        assertTrue(setCookie.contains("; Secure"));
    }

    @Test
    void queryParametersReachThePage() throws Exception {
        write("echo.why", "controller:\nname = params.name\n\nview:\nhello {{name}}\n");
        assertEquals("hello why\n", get("/echo?name=why").body());
    }

    @Test
    void postRedirectsAndFormFieldsReachThePage() throws Exception {
        write("sign.why", """
                controller:
                if request.method == "POST"
                  redirect("/signed?name=" + form.name)
                end

                view:
                <form method="post" action="/sign"></form>
                """);
        write("signed.why", "controller:\nname = params.name\n\nview:\nsigned {{name}}\n");

        HttpResponse<String> posted = postWithToken("/sign", "name=why");
        assertEquals(303, posted.statusCode());
        assertEquals("/signed?name=why", posted.headers().firstValue("location").orElse(""));
        assertEquals("signed why\n", get("/signed?name=why").body());
    }
}
