package whyx.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import why.Site;

/**
 * The launcher's configuration and seed gate, without opening a server: flags and environment
 * defaults are parsed into {@link WhyApp.Options}, and seeding is applied only to a database that
 * did not exist before the site was opened.
 */
class WhyAppTest {
    @Test
    void usesDefaultsWhenNothingIsConfigured() {
        WhyApp.Options options = parse();

        assertEquals("127.0.0.1", options.host());
        assertEquals(8080, options.port());
        assertNull(options.db());
        assertFalse(options.diagnostics());
        assertFalse(options.secureCookies());
        assertFalse(options.trustProxy());
        assertFalse(options.help());
    }

    @Test
    void readsEnvironmentDefaults() {
        WhyApp.Options options = parse(new String[0], Map.of(
                "HOST", "0.0.0.0",
                "PORT", "9090",
                "WHY_DB", "env.db",
                "WHY_SECURE_COOKIES", "yes"));

        assertEquals("0.0.0.0", options.host());
        assertEquals(9090, options.port());
        assertEquals(Path.of("env.db"), options.db());
        assertTrue(options.secureCookies());
    }

    @Test
    void ignoresBlankEnvironmentValues() {
        WhyApp.Options options = parse(new String[0], Map.of(
                "HOST", " ",
                "PORT", "",
                "WHY_DB", "\t",
                "WHY_SECURE_COOKIES", " "));

        assertEquals("127.0.0.1", options.host());
        assertEquals(8080, options.port());
        assertNull(options.db());
        assertFalse(options.secureCookies());
    }

    @Test
    void parsesFlagsAndOverridesEnvironment() {
        WhyApp.Options options = parse(new String[] {
                "--host", "0.0.0.0",
                "-p", "1234",
                "--db", "flag.db",
                "--dev",
                "--secure-cookies",
                "--trust-proxy"
        }, Map.of(
                "HOST", "127.0.0.1",
                "PORT", "8080",
                "WHY_DB", "env.db",
                "WHY_SECURE_COOKIES", "false"));

        assertEquals("0.0.0.0", options.host());
        assertEquals(1234, options.port());
        assertEquals(Path.of("flag.db"), options.db());
        assertTrue(options.diagnostics());
        assertTrue(options.secureCookies());
        assertTrue(options.trustProxy());
        assertFalse(options.help());
    }

    @Test
    void acceptsLongPortAndHelpFlags() {
        WhyApp.Options options = parse("--port", "4321", "--help");

        assertEquals(4321, options.port());
        assertTrue(options.help());
    }

    @Test
    void helpStopsBeforeLaterArguments() {
        WhyApp.Options options = parse("-h", "--not-an-option");

        assertTrue(options.help());
    }

    @Test
    void rejectsUnknownOptionsAndMissingValues() {
        WhyApp.OptionException unknown = assertThrows(WhyApp.OptionException.class,
                () -> parse("--bogus"));
        assertEquals("unknown option '--bogus'", unknown.getMessage());

        WhyApp.OptionException host = assertThrows(WhyApp.OptionException.class,
                () -> parse("--host"));
        assertEquals("option --host needs a value", host.getMessage());

        WhyApp.OptionException port = assertThrows(WhyApp.OptionException.class,
                () -> parse("-p"));
        assertEquals("option -p needs a value", port.getMessage());
    }

    @Test
    void rejectsInvalidEnvironmentValues() {
        WhyApp.OptionException port = assertThrows(WhyApp.OptionException.class,
                () -> parse(new String[0], Map.of("PORT", "abc")));
        assertEquals("port must be a number, got 'abc'", port.getMessage());

        WhyApp.OptionException flag = assertThrows(WhyApp.OptionException.class,
                () -> parse(new String[0], Map.of("WHY_SECURE_COOKIES", "maybe")));
        assertEquals("WHY_SECURE_COOKIES must be true or false, got 'maybe'",
                flag.getMessage());
    }

    @Test
    void treatsMissingAndTemporaryDatabasesAsFresh(@TempDir Path dir) {
        assertTrue(WhyApp.isFresh(null), "the temporary database is new every run");
        assertTrue(WhyApp.isFresh(dir.resolve("missing.db")));
    }

    @Test
    void treatsAnExistingDatabaseAsNotFresh(@TempDir Path dir) throws IOException {
        assertFalse(WhyApp.isFresh(Files.createFile(dir.resolve("data.db"))));
    }

    @Test
    void appliesSeedOnlyWhenEnabledAndFresh(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("site"));
        Files.writeString(root.resolve("seed.sql"), "create table marker (id integer primary key);");
        Path db = dir.resolve("data.db");
        Site site = new Site(root, db);

        boolean fresh = WhyApp.isFresh(db);
        assertTrue(fresh);
        assertTrue(WhyApp.applySeed(true, site, fresh),
                "an enabled fresh database should be seeded");
        assertTrue(Files.exists(db), "seeding should create the database");
        assertFalse(WhyApp.isFresh(db));
        assertFalse(WhyApp.applySeed(true, site, WhyApp.isFresh(db)),
                "an existing database should not be seeded");
        Path disabledDb = dir.resolve("disabled.db");
        Site disabledSite = new Site(root, disabledDb);
        assertTrue(WhyApp.isFresh(disabledDb));
        assertFalse(WhyApp.applySeed(false, disabledSite, WhyApp.isFresh(disabledDb)),
                "seeding disabled should skip a fresh database");
    }

    private static WhyApp.Options parse(String... args) {
        return parse(args, Map.of());
    }

    private static WhyApp.Options parse(String[] args, Map<String, String> environment) {
        return WhyApp.Options.parse(args, environment::get);
    }
}
