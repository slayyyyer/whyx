package whyx;

import whyx.boot.WhyApp;
import whyx.boot.WhyApplication;

/**
 * Entry point for the packaged whyx site. The Why Gradle plugin stores the pages under
 * {@code META-INF/why-site}; {@link WhyApp} opens them from the classpath, applies the demo seed to
 * a new database, and starts the server using {@code --host}, {@code --port}, {@code --db},
 * {@code --dev}, {@code --secure-cookies} and {@code --trust-proxy}, with {@code HOST},
 * {@code PORT}, {@code WHY_DB} and {@code WHY_SECURE_COOKIES} as the defaults.
 */
@WhyApplication(seed = true)
public final class Main {
    public static void main(String[] args) {
        WhyApp.run(Main.class, args);
    }
}
