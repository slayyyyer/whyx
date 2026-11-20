package why;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import why.testing.TestServer;

class CookieSecurityTest {

    @TempDir
    Path dir;

    @BeforeEach
    void page() throws IOException {
        Files.writeString(dir.resolve("token.why"), """
                view:
                {{csrf.token}}
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("session.why"), """
                controller:
                session.set("seen", "1")

                view:
                ok
                """, StandardCharsets.UTF_8);
    }

    @Test
    void forwardedHttpsMarksCookiesSecureOnlyWhenTrusted() throws Exception {
        try (TestServer trusted = TestServer.start(new Site(dir), false, false, true)) {
            String secure = cookie(trusted.send("GET", "/token", null, null,
                    Map.of("Cookie", "", "X-Forwarded-Proto", "https")));
            assertTrue(secure.contains("Secure"), secure);

            String plain = cookie(trusted.send("GET", "/token", null, null,
                    Map.of("Cookie", "", "X-Forwarded-Proto", "http")));
            assertFalse(plain.contains("Secure"), plain);

            String session = cookie(trusted.send("GET", "/session", null, null,
                    Map.of("Cookie", "", "X-Forwarded-Proto", "https")));
            assertTrue(session.contains("why_sid="), session);
            assertTrue(session.contains("Secure"), session);
        }

        try (TestServer untrusted = TestServer.start(new Site(dir), false, false, false)) {
            String header = cookie(untrusted.send("GET", "/token", null, null,
                    Map.of("Cookie", "", "X-Forwarded-Proto", "https")));
            assertFalse(header.contains("Secure"), header);
        }
    }

    @Test
    void theFirstForwardedValueWins() throws Exception {
        try (TestServer trusted = TestServer.start(new Site(dir), false, false, true)) {
            String secure = cookie(trusted.send("GET", "/token", null, null,
                    Map.of("Cookie", "", "X-Forwarded-Proto", "https, http")));
            assertTrue(secure.contains("Secure"), secure);
        }
    }

    private static String cookie(TestServer.Response response) {
        return String.join("; ", response.headers().getOrDefault("set-cookie", java.util.List.of()));
    }
}
