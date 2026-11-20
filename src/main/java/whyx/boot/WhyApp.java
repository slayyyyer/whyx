package whyx.boot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

import why.Seeds;
import why.Site;
import why.WhyServer;

/**
 * Boots a packaged Why site from the class annotated with {@link WhyApplication}: it opens the
 * site under {@code META-INF/why-site}, applies {@code seed.sql} to a new database when the
 * annotation opts in, reads the environment and command-line flags, starts the server and waits
 * until the process is stopped.
 *
 * <p>A site's entry point is then just:
 *
 * <pre>{@code
 * @WhyApplication(seed = true)
 * public final class Main {
 *     public static void main(String[] args) {
 *         WhyApp.run(Main.class, args);
 *     }
 * }
 * }</pre>
 */
public final class WhyApp {
    private WhyApp() {}

    /** Starts the application, or prints help and returns. Exits 1 on a usage or startup error. */
    public static void run(Class<?> application, String[] args) {
        WhyApplication annotation = application.getAnnotation(WhyApplication.class);
        if (annotation == null) {
            throw new IllegalArgumentException(
                    application.getName() + " is not annotated with @WhyApplication");
        }

        String name = application.getPackageName();
        if (name.isBlank()) name = "why";

        try {
            Options options = Options.parse(args);
            if (options.help()) {
                usage();
                return;
            }

            Path db = options.db();
            // Site.fromClasspath creates the schema while opening the database, so decide whether
            // it is new first.
            boolean fresh = isFresh(db);
            Site site = Site.fromClasspath(application.getClassLoader(), db);
            if (applySeed(annotation.seed(), site, fresh)) {
                System.out.println(name + ": applied seed.sql to the new database");
            }

            WhyServer server = new WhyServer(site, options.host(), options.port(),
                    options.diagnostics(), options.secureCookies(), options.trustProxy());
            server.start();
            String shown = switch (options.host()) {
                case "127.0.0.1", "::1", "localhost" -> "localhost";
                default -> options.host();
            };
            System.out.println("Why is serving " + site.root() + " at http://" + shown + ":"
                    + server.port());
            System.out.println("press Ctrl+C to stop");
            Runtime.getRuntime().addShutdownHook(
                    new Thread(() -> server.stop(Duration.ofSeconds(5))));
            new CountDownLatch(1).await();
        } catch (OptionException e) {
            fail(name, e.getMessage());
        } catch (IOException e) {
            fail(name, "cannot start server: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Whether this run has to create the database. A null path is the temporary database. */
    static boolean isFresh(Path db) {
        return db == null || !Files.exists(db);
    }

    /**
     * Applies {@code seed.sql} when seeding is enabled and the database did not exist before the
     * site was opened.
     */
    static boolean applySeed(boolean enabled, Site site, boolean fresh) throws IOException {
        return Seeds.apply(site.root(), site.dbFile(), fresh, enabled);
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) throw new OptionException("option " + option + " needs a value");
        return args[index];
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new OptionException("port must be a number, got '" + value + "'");
        }
    }

    private static String env(Function<String, String> environment, String name,
            String fallback) {
        String value = environment.apply(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static Path envPath(Function<String, String> environment, String name) {
        String value = env(environment, name, null);
        return value == null ? null : Path.of(value);
    }

    private static int envPort(Function<String, String> environment, int fallback) {
        String value = env(environment, "PORT", null);
        return value == null ? fallback : parsePort(value);
    }

    private static boolean envFlag(Function<String, String> environment, String name) {
        String value = env(environment, name, null);
        if (value == null) return false;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> throw new OptionException(
                    name + " must be true or false, got '" + value + "'");
        };
    }

    private static void fail(String name, String message) {
        System.err.println(name + ": " + message);
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

    /** Parsed flags and environment defaults. */
    record Options(String host, int port, Path db, boolean diagnostics, boolean secureCookies,
            boolean trustProxy, boolean help) {
        static Options parse(String[] args) {
            return parse(args, System::getenv);
        }

        static Options parse(String[] args, Function<String, String> environment) {
            String host = env(environment, "HOST", "127.0.0.1");
            int port = envPort(environment, 8080);
            Path db = envPath(environment, "WHY_DB");
            boolean diagnostics = false;
            boolean secureCookies = envFlag(environment, "WHY_SECURE_COOKIES");
            boolean trustProxy = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--host" -> host = value(args, ++i, arg);
                    case "-p", "--port" -> port = parsePort(value(args, ++i, arg));
                    case "--db" -> db = Path.of(value(args, ++i, arg));
                    case "--dev" -> diagnostics = true;
                    case "--secure-cookies" -> secureCookies = true;
                    case "--trust-proxy" -> trustProxy = true;
                    case "-h", "--help" -> {
                        return new Options(host, port, db, diagnostics, secureCookies,
                                trustProxy, true);
                    }
                    default -> throw new OptionException("unknown option '" + arg + "'");
                }
            }
            return new Options(host, port, db, diagnostics, secureCookies, trustProxy, false);
        }
    }

    /** A problem with the command line or environment, reported as one line before exit. */
    static final class OptionException extends RuntimeException {
        OptionException(String message) {
            super(message);
        }
    }
}
