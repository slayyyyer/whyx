package why;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import why.testing.TestServer;

class TrustedProxyTest {

    @TempDir
    Path dir;

    @Test
    void aTrustedProxySuppliesTheForwardedClientAddress() throws Exception {
        Files.writeString(dir.resolve("remote.why"), "view:\n{{request.remote}}\n",
                StandardCharsets.UTF_8);

        try (TestServer server = TestServer.start(new Site(dir), false, false, true)) {
            TestServer.Response response = server.send("GET", "/remote", null, null,
                    Map.of("X-Forwarded-For", "198.51.100.7"));
            assertEquals("198.51.100.7", response.body().strip());
        }
    }

    @Test
    void anUntrustedPeerKeepsItsOwnAddress() throws Exception {
        InetAddress directPeer = InetAddress.getByName("203.0.113.9");
        assertEquals("203.0.113.9",
                WhyServer.clientAddress(directPeer, List.of("198.51.100.7"), true));

        InetAddress proxy = InetAddress.getByName("127.0.0.1");
        assertEquals("127.0.0.1",
                WhyServer.clientAddress(proxy, List.of("198.51.100.7"), false));
    }

    @Test
    void aClientSuppliedPrefixCannotSpoofTheAddress() throws Exception {
        InetAddress proxy = InetAddress.getByName("127.0.0.1");
        assertEquals("198.51.100.7",
                WhyServer.clientAddress(proxy, List.of("10.0.0.9, 198.51.100.7"), true));
    }
}
