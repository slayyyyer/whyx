package whyx.boot;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the entry point of a packaged Why site. {@link WhyApp#run} opens the site under
 * {@code META-INF/why-site}, applies {@code seed.sql} to a new database when {@link #seed()} is
 * set, reads the environment and flags, and starts the server, so the annotated class only needs
 * a {@code main} that delegates.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface WhyApplication {
    /** Apply {@code seed.sql} to a database created by this run. Defaults to {@code false}. */
    boolean seed() default false;
}
