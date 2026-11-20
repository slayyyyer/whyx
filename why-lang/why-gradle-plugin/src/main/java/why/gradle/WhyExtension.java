package why.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

/** Configuration for the Why Gradle plugin. */
public abstract class WhyExtension {
    /** Folder of .why pages and site assets. Default: {@code src/why}. */
    public abstract DirectoryProperty getSiteDir();

    /** Interface the development server binds. Default: loopback. */
    public abstract Property<String> getHost();

    /** Port for {@code whyRun}. Default: 8080. */
    public abstract Property<Integer> getPort();

    /** Show source, SQL and stack details in error pages. Default: false. */
    public abstract Property<Boolean> getDiagnostics();

    /** Mark session cookies Secure. Default: false. */
    public abstract Property<Boolean> getSecureCookies();

    /** Optional SQLite file for {@code whyRun}. */
    public abstract RegularFileProperty getDatabaseFile();
}
