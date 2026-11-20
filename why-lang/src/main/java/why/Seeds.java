package why;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import why.lang.Db;

/**
 * Optional demo data at {@code <site>/seed.sql}. Unlike {@code migrations/}, it is not schema: it
 * only runs when {@code --dev} starts against a database that did not exist before, so a deployed
 * service with a persistent disk never re-inserts demo rows and an ephemeral dev host still gets
 * them on first boot. A seed should still use {@code insert or ignore} where it can.
 */
public final class Seeds {
    /** File name, relative to the site root. */
    public static final String FILE = "seed.sql";

    private Seeds() {}

    /** Applies the seed, returning whether it ran; absent or non-dev/fresh runs do nothing. */
    public static boolean apply(Path siteRoot, Path dbFile, boolean fresh, boolean dev) throws IOException {
        if (!dev || !fresh) return false;
        Path seed = siteRoot.resolve(FILE);
        if (!Files.isRegularFile(seed)) return false;
        String script = Files.readString(seed, StandardCharsets.UTF_8);
        Db.applyScript(dbFile, script);
        return true;
    }
}
