package whyx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

import why.Seeds;
import why.Site;
import why.WhyServer;

/**
 * Entry point for the packaged whyx site. The Gradle plugin stores the pages under
 * {@code META-INF/why-site}, so they are extracted with {@link Site#fromClasspath} instead of being
 * read from a folder. Flags mirror the {@code why} CLI, and {@code PORT}, {@code HOST},
 * {@code WHY_DB} and {@code WHY_SECURE_COOKIES} supply the defaults.
 */
public final class Main {
    public static void main(String[] args) {
        String host = env("HOST", "127.0.0.1");
        Integer port = envPort(8080);
        Path db = envPath("WHY_DB");
        boolean diagnostics = false;
        boolean secureCookies = envFlag("WHY_SECURE_COOKIES");
        boolean trustProxy = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = value(args, ++i, "--host");
                case "-p", "--port" -> {
                    // Read the flag before ++i: a trailing --port would otherwise index past the
                    // array instead of reporting the missing value.
                    String flag = args[i];
                    port = parsePort(value(args, ++i, flag));
                }
                case "--db" -> db = Path.of(value(args, ++i, "--db"));
                case "--dev" -> diagnostics = true;
                case "--secure-cookies" -> secureCookies = true;
                case "--trust-proxy" -> trustProxy = true;
                case "-h", "--help" -> {
                    usage();
                    return;
                }
                default -> fail("unknown option '" + args[i] + "'");
            }
        }

        // A missing file means a brand-new database; the site extracts to a temporary folder when
        // no --db is given, and that database is new every run.
        boolean fresh = db == null || !Files.exists(db);
        try {
            Site site = Site.fromClasspath(Site.class.getClassLoader(), db);
            // whyx is a demo, so its demo accounts go in whenever the database is new, not only
            // under --dev. seed.sql uses insert or ignore, so applying it twice is harmless.
            if (Seeds.apply(site.root(), site.dbFile(), fresh, true)) {
                System.out.println("whyx: applied seed.sql to the new database");
            }
            WhyServer server = new WhyServer(site, host, port, diagnostics, secureCookies, trustProxy);
            server.start();
            String shown = switch (host) {
                case "127.0.0.1", "::1", "localhost" -> "localhost";
                default -> host;
            };
            System.out.println("Why is serving " + site.root() + " at http://" + shown + ":"
                    + server.port());
            System.out.println("press Ctrl+C to stop");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(Duration.ofSeconds(5))));
            new CountDownLatch(1).await();
        } catch (IOException e) {
            fail("cannot start server: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) fail("option " + option + " needs a value");
        return args[index];
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            fail("port must be a number, got '" + value + "'");
            return -1;
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static Path envPath(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : Path.of(value.strip());
    }

    private static int envPort(int fallback) {
        String value = System.getenv("PORT");
        return value == null || value.isBlank() ? fallback : parsePort(value);
    }

    private static boolean envFlag(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) return false;
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> {
                fail(name + " must be true or false, got '" + value + "'");
                yield false;
            }
        };
    }

    private static void fail(String message) {
        System.err.println("whyx: " + message);
        System.exit(1);
    }

    private static void usage() {
        System.out.println("""
                usage: java -jar whyx.jar [options]

                  -p, --port PORT      port to listen on (default: 8080)
                      --host HOST      interface to bind (default: 127.0.0.1; 0.0.0.0 exposes)
                      --db FILE        SQLite database file (default: a temporary file)
                      --dev            show source and SQL errors in 500 pages
                      --secure-cookies mark session cookies Secure (use behind TLS)
                      --trust-proxy    trust X-Forwarded-Proto and X-Forwarded-For
                  -h, --help           show this help

                Environment: PORT, HOST, WHY_DB and WHY_SECURE_COOKIES supply the defaults.
                The pages come from the jar; there is no site folder to pass.
                """);
    }
}
