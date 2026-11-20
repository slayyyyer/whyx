package whyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import why.Migrations;
import why.Seeds;
import why.Site;
import why.lang.Db;
import why.lang.Passwords;
import why.testing.TestServer;

/**
 * The whyx site driven through its real pages: {@code app/} is copied to a temporary folder, its
 * migrations build the demo database, and the {@link TestServer} harness posts forms with a CSRF
 * token. Cookies are handled here rather than by the harness, because signing in has to carry a
 * session from one request to the next.
 */
class WhyxAppTest {
    private static final String PASSWORD = "whylang";

    @TempDir
    Path dir;

    private TestServer server;
    private String token;

    @BeforeEach
    void start() throws Exception {
        Path app = Path.of("app").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(app), "whyx pages not found at " + app);
        copySite(app);
        Migrations.apply(dir, dir.resolve("data.db"));
        Seeds.apply(dir, dir.resolve("data.db"), true, true);
        server = TestServer.start(dir);
        // A page fetched without a CSRF cookie issues one. Its value is both the cookie and the
        // form token, so every later request can send it back.
        token = cookie(server.send("GET", "/login", null, null, Map.of("Cookie", "")), "why_csrf");
        assertFalse(token.isEmpty(), "the login page should issue a CSRF token");
    }

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    @Test
    void signingInRotatesTheSessionIdAndRetiresTheOldCookie() throws Exception {
        String planted = plantSession();

        TestServer.Response signedIn = post("/login", planted,
                Map.of("handle", "alice", "password", PASSWORD));
        assertEquals(303, signedIn.status(), signedIn.body());
        assertEquals("/", signedIn.header("location"));

        String rotated = cookie(signedIn, "why_sid");
        assertNotEquals(planted, rotated, "signing in must mint a new session id");
        assertEquals(0, count("select count(*) as n from why_sessions where id = ?", List.of(planted)),
                "the id the browser arrived with should be retired");

        assertFalse(get("/", planted).body().contains("What is happening?!"),
                "the pre-login cookie still reached the signed-in home");
        String home = get("/", rotated).body();
        assertTrue(home.contains("What is happening?!"), "the new cookie did not reach the home page");
        assertTrue(home.contains("@alice"), "the new session should belong to alice");
    }

    @Test
    void aPostWithoutSigningInIsRejected() throws Exception {
        long before = count("select count(*) as n from posts", List.of());

        TestServer.Response response = post("/act", null,
                Map.of("action", "post", "body", "anonymous post", "back", "/"));

        assertEquals(303, response.status(), response.body());
        assertEquals("/login", response.header("location"),
                "an anonymous post should be sent to sign in");
        assertEquals(before, count("select count(*) as n from posts", List.of()),
                "the rejected post was stored anyway");
        assertNull(row("select id from posts where body = ?", List.of("anonymous post")));
    }

    @Test
    void aUserCannotDeleteSomeoneElsesPost() throws Exception {
        String bob = signIn("bob");

        TestServer.Response attempt = post("/act", bob,
                Map.of("action", "delete", "post_id", "1", "back", "/post?id=1"));
        assertEquals(303, attempt.status(), attempt.body());
        assertNotNull(row("select id from posts where id = 1", List.of()),
                "bob deleted alice's post");
        assertTrue(get("/post?id=1", bob).body().contains("shipped a page language"),
                "alice's thread disappeared after bob's delete");

        // The same form still deletes the viewer's own post, so the guard is not a dead end.
        TestServer.Response own = post("/act", bob,
                Map.of("action", "delete", "post_id", "2", "back", "/"));
        assertEquals(303, own.status(), own.body());
        assertNull(row("select id from posts where id = 2", List.of()),
                "bob could not delete his own post");
    }

    @Test
    void deletingAThreadRemovesItsNestedReplies() throws Exception {
        String alice = signIn("alice");

        TestServer.Response deleted = post("/act", alice,
                Map.of("action", "delete", "post_id", "1", "back", "/"));

        assertEquals(303, deleted.status(), deleted.body());
        assertNull(row("select id from posts where id in (1, 13, 14, 15)", List.of()),
                "a nested reply survived the deleted thread");
        assertNotNull(row("select id from posts where id = 16", List.of()),
                "a reply to another thread was deleted");
    }

    @Test
    void deletingAPostFromItsThreadRedirectsHome() throws Exception {
        String alice = signIn("alice");

        TestServer.Response deleted = post("/act", alice,
                Map.of("action", "delete", "post_id", "1", "back", "/post?id=1"));

        assertEquals(303, deleted.status(), deleted.body());
        assertEquals("/", deleted.header("location"),
                "the deleted thread would 404, so the redirect must go home");
        assertNull(row("select id from posts where id = 1", List.of()),
                "alice's post should be gone");
    }

    @Test
    void theHomeFeedLeavesOutPeopleTheViewerDoesNotFollow() throws Exception {
        String alice = signIn("alice");
        String home = get("/", alice).body();

        assertTrue(home.contains("hot take: the best state management"),
                "bob is followed, so his post belongs on alice's home");
        assertTrue(home.contains("backups you have never restored"),
                "carol is followed, so her post belongs on alice's home");
        assertFalse(home.contains("testing the waters"),
                "frank is not followed, so his post does not belong on alice's home");
        assertFalse(home.contains("papers are just blog posts with better typography"),
                "dave is not followed, so his post does not belong on alice's home");
    }

    @Test
    void aSuccessfulSignInUpgradesAnOldPasswordHash() throws Exception {
        String old = "pbkdf2-sha256$10000$testingsalt$"
                + Passwords.pbkdf2(PASSWORD, "testingsalt", 10000);
        try (Db db = new Db(dir.resolve("data.db"))) {
            db.exec("update users set password_hash = ? where handle = 'alice'", List.of(old));
        }

        TestServer.Response wrong = post("/login", null,
                Map.of("handle", "alice", "password", "not-it"));
        assertEquals(200, wrong.status(), wrong.body());
        assertEquals(old, storedPassword("alice"), "a failed sign-in must not rewrite the row");

        TestServer.Response right = post("/login", null,
                Map.of("handle", "alice", "password", PASSWORD));
        assertEquals(303, right.status(), right.body());
        String upgraded = storedPassword("alice");
        assertNotEquals(old, upgraded);
        assertTrue(upgraded.startsWith("pbkdf2-sha256$600000$"), upgraded);
        assertTrue(Passwords.verify(PASSWORD, upgraded), upgraded);
    }

    private String storedPassword(String handle) {
        return (String) ((Map<?, ?>) row("select password_hash from users where handle = ?",
                List.of(handle))).get("password_hash");
    }

    @Test
    void aHandleThatCleansToNothingIsRejectedBeforeItIsStored() throws Exception {
        long before = count("select count(*) as n from users", List.of());

        TestServer.Response blank = post("/signup", null,
                Map.of("name", "Ann", "handle", "@@@", "password", "secret1"));
        assertEquals(200, blank.status(), blank.body());
        assertTrue(blank.body().contains("must be at least 3"), blank.body());

        TestServer.Response shortHandle = post("/signup", null,
                Map.of("name", "Ann", "handle", "@a", "password", "secret1"));
        assertEquals(200, shortHandle.status(), shortHandle.body());
        assertTrue(shortHandle.body().contains("at least 3"), shortHandle.body());

        assertEquals(before, count("select count(*) as n from users", List.of()),
                "a rejected sign-up created a user");
        assertNull(row("select id from users where handle = '' or handle = 'a'", List.of()));
    }

    @Test
    void failedSignInsAreRecordedAndTheAddressIsLimited() throws Exception {
        try (Db db = new Db(dir.resolve("data.db"))) {
            for (int i = 0; i < 19; i++) {
                db.exec("insert into login_attempts (kind, subject, attempted_at)"
                        + " values (?, ?, strftime('%s','now'))", List.of("ip", "127.0.0.1"));
            }
        }

        TestServer.Response failed = post("/login", null,
                Map.of("handle", "alice", "password", "not-it"));
        assertEquals(200, failed.status(), failed.body());
        assertTrue(failed.body().contains("do not match"), failed.body());
        assertEquals(20L, count("select count(*) as n from login_attempts"
                + " where kind = 'ip' and subject = ?", List.of("127.0.0.1")));
        assertEquals(1L, count("select count(*) as n from login_attempts"
                + " where kind = 'handle' and subject = ?", List.of("alice")));

        TestServer.Response blocked = post("/login", null,
                Map.of("handle", "alice", "password", PASSWORD));
        assertEquals(200, blocked.status(), blocked.body());
        assertTrue(blocked.body().contains("Too many sign-in attempts"), blocked.body());
    }

    @Test
    void signupsFromOneAddressAreLimited() throws Exception {
        for (int i = 1; i <= 5; i++) {
            TestServer.Response created = post("/signup", null,
                    Map.of("name", "New " + i, "handle", "newbie" + i, "password", "secret1"));
            assertEquals(303, created.status(), created.body());
        }
        long before = count("select count(*) as n from users", List.of());

        TestServer.Response blocked = post("/signup", null,
                Map.of("name", "New 6", "handle", "newbie6", "password", "secret1"));
        assertEquals(200, blocked.status(), blocked.body());
        assertTrue(blocked.body().contains("Too many accounts"), blocked.body());
        assertEquals(before, count("select count(*) as n from users", List.of()),
                "a rate-limited sign-up created a user");
        assertNull(row("select id from users where handle = 'newbie6'", List.of()));
    }

    /** Copies the pages, stylesheet, migrations and dev seed, leaving the demo database behind. */
    private void copySite(Path app) throws IOException {
        try (Stream<Path> pages = Files.list(app)) {
            for (Path page : pages.toList()) {
                String name = page.getFileName().toString();
                if (name.endsWith(Site.PAGE_EXTENSION) || name.equals("style.css")) {
                    Files.copy(page, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        if (Files.isRegularFile(app.resolve(Seeds.FILE))) {
            Files.copy(app.resolve(Seeds.FILE), dir.resolve(Seeds.FILE),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        Path migrations = Files.createDirectories(dir.resolve(Migrations.DIRECTORY));
        try (Stream<Path> files = Files.list(app.resolve(Migrations.DIRECTORY))) {
            for (Path file : files.toList()) {
                Files.copy(file, migrations.resolve(file.getFileName()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** A live session id the browser already holds, as if an earlier page had stored something. */
    private String plantSession() {
        String id = "planted-session";
        try (Db db = new Db(dir.resolve("data.db"))) {
            // The store creates this table on its first save; a planted session comes before that.
            db.exec("create table if not exists why_sessions (id text not null, name text not null,"
                    + " value text not null, updated_at integer not null, primary key (id, name))",
                    List.of());
            db.exec("insert into why_sessions (id, name, value, updated_at)"
                    + " values (?, ?, ?, strftime('%s','now'))",
                    List.of(id, "visited", "1"));
        }
        return id;
    }

    private String signIn(String handle) throws Exception {
        TestServer.Response response =
                post("/login", null, Map.of("handle", handle, "password", PASSWORD));
        assertEquals(303, response.status(), response.body());
        assertEquals("/", response.header("location"));
        String sid = cookie(response, "why_sid");
        assertFalse(sid.isEmpty(), "signing in should issue a why_sid cookie: " + response.headers());
        return sid;
    }

    private String cookie(String sid) {
        String value = "why_csrf=" + token;
        return sid == null ? value : value + "; why_sid=" + sid;
    }

    private TestServer.Response get(String path, String sid) throws Exception {
        return server.send("GET", path, null, null, Map.of("Cookie", cookie(sid)));
    }

    private TestServer.Response post(String path, String sid, Map<String, String> fields)
            throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(encode(field.getKey())).append('=').append(encode(field.getValue()));
        }
        if (body.length() > 0) body.append('&');
        body.append("_token=").append(token);
        return server.send("POST", path, body.toString(),
                "application/x-www-form-urlencoded", Map.of("Cookie", cookie(sid)));
    }

    private static String encode(String text) {
        return URLEncoder.encode(text == null ? "" : text, StandardCharsets.UTF_8);
    }

    /** One cookie from a response's Set-Cookie headers, or "" when the response never set it. */
    private static String cookie(TestServer.Response response, String name) {
        for (String header : response.headers().getOrDefault("set-cookie", List.of())) {
            if (!header.startsWith(name + "=")) continue;
            int end = header.indexOf(';');
            return end < 0 ? header.substring(name.length() + 1)
                    : header.substring(name.length() + 1, end);
        }
        return "";
    }

    private Object row(String sql, List<Object> args) {
        try (Db db = new Db(dir.resolve("data.db"))) {
            return db.get(sql, args);
        }
    }

    private long count(String sql, List<Object> args) {
        return ((Number) ((Map<?, ?>) row(sql, args)).get("n")).longValue();
    }
}
