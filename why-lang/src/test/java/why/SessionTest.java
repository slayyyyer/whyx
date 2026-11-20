package why;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import why.lang.Db;
import why.lang.Session;
import why.testing.TestServer;

/** The {@code session} global: stored values, its cookie, rotation on sign-in and expiry. */
class SessionTest {
    private static final String SET_COOKIE = "set-cookie";

    @TempDir
    Path dir;

    private TestServer server;

    @BeforeEach
    void start() throws IOException {
        write("remember.why", """
                controller:
                session.set("user_id", params.id)
                session.set("name", "alice")

                view:
                stored={{session.get("user_id")}}
                """);
        write("who.why", """
                view:
                user={{session.get("user_id")}} name={{session.get("name")}}
                """);
        write("signout.why", """
                controller:
                session.clear()

                view:
                bye
                """);
        write("rotate.why", """
                controller:
                session.set("user_id", "7")
                session.rotate()

                view:
                stored={{session.get("user_id")}}
                """);
        server = TestServer.start(dir);
    }

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    /** The session ID from a response that issued one. */
    private static String sessionId(TestServer.Response response) {
        String header = response.header(SET_COOKIE);
        assertTrue(header.startsWith("why_sid="), header);
        return header.substring("why_sid=".length(), header.indexOf(';'));
    }

    private TestServer.Response get(String path, String sessionId) throws Exception {
        return server.send("GET", path, null, null, Map.of("Cookie", "why_sid=" + sessionId));
    }

    @Test
    void valuesSurviveAcrossRequests() throws Exception {
        TestServer.Response remembered = server.get("/remember?id=7");
        assertEquals(200, remembered.status());
        assertTrue(remembered.body().contains("stored=7"), remembered.body());

        TestServer.Response who = get("/who", sessionId(remembered));
        assertTrue(who.body().contains("user=7"), who.body());
        assertTrue(who.body().contains("name=alice"), who.body());
    }

    @Test
    void theCookieIsHttpOnlyAndSameSite() throws Exception {
        String header = server.get("/remember?id=7").header(SET_COOKIE);
        assertTrue(header.contains("HttpOnly"), header);
        assertTrue(header.contains("SameSite=Lax"), header);
        assertTrue(header.contains("Path=/"), header);
        assertTrue(header.contains("Max-Age=" + SessionStore.TTL_SECONDS),
                "a remembered session must outlive the browser being closed: " + header);
    }

    @Test
    void everyPageReissuesTheCookieSoItsLifetimeSlides() throws Exception {
        String id = sessionId(server.get("/remember?id=7"));

        TestServer.Response who = get("/who", id);
        assertEquals(id, sessionId(who), "browsing should keep the same session");
        assertTrue(who.header(SET_COOKIE).contains("Max-Age=" + SessionStore.TTL_SECONDS),
                "the cookie's lifetime should slide with the session: " + who.header(SET_COOKIE));
    }

    @Test
    void anUnknownCookieReadsAsEmpty() throws Exception {
        TestServer.Response who = get("/who", "not-a-session-id");
        assertEquals(200, who.status());
        assertTrue(who.body().contains("user= name="), who.body());
        assertFalse(who.header(SET_COOKIE).contains("Max-Age=0"),
                "an untouched unknown cookie should not be expired");
    }

    @Test
    void signingOutExpiresTheCookieAndForgetsTheValues() throws Exception {
        String id = sessionId(server.get("/remember?id=7"));

        TestServer.Response out = get("/signout", id);
        String header = out.header(SET_COOKIE);
        assertTrue(header.startsWith("why_sid=;"), header);
        assertTrue(header.contains("Max-Age=0"), header);

        assertTrue(get("/who", id).body().contains("user= name="), "the old ID still loaded values");
    }

    @Test
    void rotatingIssuesANewIdAndRetiresTheOldOne() throws Exception {
        String oldId = sessionId(server.get("/remember?id=1"));

        TestServer.Response rotated = get("/rotate", oldId);
        assertEquals(200, rotated.status());
        String newId = sessionId(rotated);

        assertNotEquals(oldId, newId, "rotation did not change the session ID");
        assertTrue(get("/who", newId).body().contains("user=7"), "the new ID did not carry the value");
        assertTrue(get("/who", oldId).body().contains("user= name="),
                "a pre-rotation cookie could still read the signed-in session");
    }

    @Test
    void rotatingWithoutAPriorCookieStillStoresValues() throws Exception {
        TestServer.Response rotated = server.get("/rotate");
        assertTrue(get("/who", sessionId(rotated)).body().contains("user=7"));
    }

    @Test
    void sessionStoresStringsAndForgetsRemovedNames() {
        Session session = new Session();
        session.set("user_id", 7L);
        assertEquals("7", session.get("user_id"));
        assertTrue(session.changed());

        session.set("user_id", null);
        assertNull(session.get("user_id"));
        assertTrue(session.isEmpty());

        session.set("note", "hi");
        session.set("note", null);
        assertTrue(session.isEmpty(), "setting nil should remove the name");

        session.set("note", "hi");
        session.clear();
        assertTrue(session.isEmpty());
    }

    @Test
    void rotateMarksTheSessionForANewId() {
        Session session = new Session();
        assertFalse(session.rotated());
        session.rotate();
        assertTrue(session.rotated());
        assertTrue(session.changed(), "rotation alone must still persist");
    }

    @Test
    void sessionBoundsNamesValuesAndCount() {
        Session session = new Session();
        assertThrows(WhyError.class, () -> session.set("", "x"));
        assertThrows(WhyError.class,
                () -> session.set("big", "x".repeat(Session.MAX_VALUE + 1)));
        for (int i = 0; i < Session.MAX_KEYS; i++) session.set("k" + i, i);
        assertThrows(WhyError.class, () -> session.set("overflow", "x"));
    }

    @Test
    void theStoreRoundTripsAndRotatesIds() throws Exception {
        try (Db db = new Db(dir.resolve("store.db"))) {
            SessionStore store = new SessionStore();
            Session session = new Session();
            session.set("user_id", "9");

            String first = store.save(db, null, session);
            assertEquals("9", store.load(db, first).get("user_id"));

            session.rotate();
            String second = store.save(db, first, session);
            assertNotEquals(first, second);
            assertTrue(store.load(db, first).isEmpty(), "the retired ID still has rows");
            assertEquals("9", store.load(db, second).get("user_id"));
        }
    }

    @Test
    void expiredRowsAreNotLoaded() throws Exception {
        try (Db db = new Db(dir.resolve("expiry.db"))) {
            SessionStore store = new SessionStore();
            Session session = new Session();
            session.set("user_id", "9");
            String id = store.save(db, null, session);

            db.exec("update why_sessions set updated_at = 0 where id = ?", List.of(id));
            assertTrue(store.load(db, id).isEmpty());
        }
    }

    @Test
    void readingALiveSessionResetsItsIdleClock() throws Exception {
        try (Db db = new Db(dir.resolve("sliding.db"))) {
            SessionStore store = new SessionStore();
            Session session = new Session();
            session.set("user_id", "9");
            String id = store.save(db, null, session);

            long nearlyExpired = System.currentTimeMillis() / 1000 - SessionStore.TTL_SECONDS + 60;
            db.exec("update why_sessions set updated_at = ? where id = ?", List.of(nearlyExpired, id));

            assertEquals("9", store.load(db, id).get("user_id"));
            Object age = ((Map<?, ?>) db.get("select updated_at from why_sessions where id = ?",
                    List.of(id))).get("updated_at");
            assertTrue(((Number) age).longValue() > nearlyExpired + 30,
                    "reading a live session should push its expiry out, stored=" + age);
        }
    }
}
