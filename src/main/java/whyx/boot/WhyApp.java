package whyx.boot;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
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
 *         int status = WhyApp.run(Main.class, args);
 *         if (status != 0) System.exit(status);
 *     }
 * }
 * }</pre>
 */
public final class WhyApp {
    private static final int FAILURE = 1;

    private WhyApp() {}

    /**
     * Runs the application until it stops and returns the process exit status: 0 after help or a
     * clean shutdown, 1 for a configuration or startup error.
     */
    public static int run(Class<?> application, String[] args) {
        return run(application, args, System::getenv, System.out, System.err);
    }

    static int run(Class<?> application, String[] args, Function<String, String> environment,
            PrintStream out, PrintStream err) {
        try {
            Options options = Options.parse(args, environment);
            if (options.help()) {
                usage(out);
                return 0;
            }

            Lifecycle lifecycle = start(application, options, out);
            Runtime.getRuntime().addShutdownHook(new Thread(lifecycle::stop, "why-shutdown"));
            lifecycle.await();
            return 0;
        } catch (LaunchException e) {
            return reportFailure(application, err, e.getMessage());
        } catch (IOException e) {
            return reportFailure(application, err, "cannot start server: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FAILURE;
        }
    }

    /**
     * Opens the annotated application's site and starts the server. The caller owns the returned
     * lifecycle and must stop it to release the port.
     */
    static Lifecycle start(Class<?> application, Options options, PrintStream out)
            throws IOException {
        WhyApplication configuration = configuration(application);
        Path db = options.db();
        // Site.fromClasspath creates the schema while opening the database, so decide whether it
        // is new first.
        boolean fresh = isFresh(db);
        Site site = Site.fromClasspath(application.getClassLoader(), db);
        if (applySeed(configuration.seed(), site, fresh)) {
            out.println(name(application) + ": applied seed.sql to the new database");
        }

        WhyServer server = new WhyServer(site, options.host(), options.port(),
                options.diagnostics(), options.secureCookies(), options.trustProxy());
        server.start();
        String shown = switch (options.host()) {
            case "127.0.0.1", "::1", "localhost" -> "localhost";
            default -> options.host();
        };
        out.println("Why is serving " + site.root() + " at http://" + shown + ":"
                + server.port());
        out.println("press Ctrl+C to stop");
        return new Lifecycle(server);
    }

    /** Reads the launch configuration declared by the annotated application class. */
    static WhyApplication configuration(Class<?> application) {
        WhyApplication configuration = application.getAnnotation(WhyApplication.class);
        if (configuration == null) {
            throw new LaunchException(
                    application.getName() + " is not annotated with @WhyApplication");
        }
        return configuration;
    }

    /** The name used for startup messages: the package, or {@code why} in the default package. */
    private static String name(Class<?> application) {
        String name = application.getPackageName();
        return name.isBlank() ? "why" : name;
    }

    private static int reportFailure(Class<?> application, PrintStream err, String message) {
        err.println(name(application) + ": " + message);
        return FAILURE;
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
        if (index >= args.length) throw new LaunchException("option " + option + " needs a value");
        return args[index];
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new LaunchException("port must be a number, got '" + value + "'");
        }
    }

    private static Path databasePath(String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            throw new LaunchException("invalid database path '" + value + "'");
        }
    }

    private static String env(Function<String, String> environment, String name,
            String fallback) {
        String value = environment.apply(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static boolean envFlag(Function<String, String> environment, String name) {
        String value = env(environment, name, null);
        if (value == null) return false;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> throw new LaunchException(
                    name + " must be true or false, got '" + value + "'");
        };
    }

    private static void usage(PrintStream out) {
        out.println("""
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

    /** A running application. {@link #stop()} drains requests and releases the port. */
    static final class Lifecycle {
        private final WhyServer server;
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final AtomicBoolean stopping = new AtomicBoolean();

        private Lifecycle(WhyServer server) {
            this.server = server;
        }

        /** Blocks until the lifecycle is stopped. */
        void await() throws InterruptedException {
            stopped.await();
        }

        /** Stops accepting requests, drains for up to five seconds and releases the port. */
        void stop() {
            if (!stopping.compareAndSet(false, true)) return;
            try {
                server.stop(Duration.ofSeconds(5));
            } finally {
                stopped.countDown();
            }
        }
    }

    /** Parsed flags and environment defaults. */
    record Options(String host, int port, Path db, boolean diagnostics, boolean secureCookies,
            boolean trustProxy, boolean help) {
        static Options parse(String[] args, Function<String, String> environment) {
            String host = env(environment, "HOST", "127.0.0.1");
            int port = parsePort(env(environment, "PORT", "8080"));
            String database = env(environment, "WHY_DB", null);
            Path db = database == null ? null : databasePath(database);
            boolean diagnostics = false;
            boolean secureCookies = envFlag(environment, "WHY_SECURE_COOKIES");
            boolean trustProxy = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--host" -> host = value(args, ++i, arg);
                    case "-p", "--port" -> port = parsePort(value(args, ++i, arg));
                    case "--db" -> db = databasePath(value(args, ++i, arg));
                    case "--dev" -> diagnostics = true;
                    case "--secure-cookies" -> secureCookies = true;
                    case "--trust-proxy" -> trustProxy = true;
                    case "-h", "--help" -> {
                        return new Options(host, port, db, diagnostics, secureCookies,
                                trustProxy, true);
                    }
                    default -> throw new LaunchException("unknown option '" + arg + "'");
                }
            }
            return new Options(host, port, db, diagnostics, secureCookies, trustProxy, false);
        }
    }

    /** A problem with the application class, command line or environment. */
    static final class LaunchException extends RuntimeException {
        LaunchException(String message) {
            super(message);
        }
    }
}
