package why;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import why.lang.Db;

/**
 * A folder of pages, and the rules for turning a request path into a file inside it.
 * Files whose name starts with {@code _} are import-only and are never returned by
 * {@link #resolve(String)}. Only pages and allowlisted public assets are served;
 * database, schema and other project files stay private.
 */
public final class Site {

    /** The page file extension. Defined once, here. */
    public static final String PAGE_EXTENSION = ".why";

    private static final String INDEX_PAGE = "index" + PAGE_EXTENSION;
    private static final Set<String> PUBLIC_EXTENSIONS = Set.of(
            ".html", ".htm", ".css", ".js", ".mjs", ".json", ".svg", ".png", ".jpg",
            ".jpeg", ".gif", ".webp", ".ico", ".woff2", ".txt");

    private final Path root;
    private final Path dbFile;

    public Site(Path root) {
        this(root, root.resolve("data.db"));
    }

    public Site(Path root, Path dbFile) {
        this.root = root.toAbsolutePath().normalize();
        this.dbFile = dbFile.toAbsolutePath().normalize();
    }

    /**
     * Extracts a site packaged by the Why Gradle plugin into a temporary folder.
     * The default database lives beside the extracted pages; pass a file to
     * {@link #fromClasspath(ClassLoader, Path)} to keep data across restarts.
     */
    public static Site fromClasspath() throws IOException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return fromClasspath(loader == null ? Site.class.getClassLoader() : loader, null);
    }

    /** Extracts a packaged site using an explicit class loader and optional database file. */
    public static Site fromClasspath(ClassLoader loader, Path dbFile) throws IOException {
        Path root = ClasspathSite.extract(loader == null ? Site.class.getClassLoader() : loader);
        Path database = (dbFile == null ? root.resolve("data.db") : dbFile).toAbsolutePath().normalize();
        Path schema = root.resolve("schema.sql");
        boolean migrations = Files.isDirectory(root.resolve(Migrations.DIRECTORY));
        if (!migrations && !Files.exists(database) && Files.isRegularFile(schema)) {
            Path parent = database.getParent();
            if (parent != null) Files.createDirectories(parent);
            try {
                Db.applyScript(database, Files.readString(schema, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw new IOException("could not apply schema.sql: " + e.getMessage(), e);
            }
        }
        try {
            Migrations.apply(root, database);
        } catch (RuntimeException e) {
            throw new IOException("could not apply migrations: " + e.getMessage(), e);
        }
        return new Site(root, database);
    }

    public Path root() {
        return root;
    }

    public Path dbFile() {
        return dbFile;
    }

    /** The page name as written in error messages: the path relative to the site root. */
    public String relative(Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    public boolean isPage(Path file) {
        return file.getFileName().toString().endsWith(PAGE_EXTENSION);
    }

    /**
     * Maps a URL path to a file inside the site, or null when there is no such page,
     * the path would escape the site folder, or the file is not public.
     *
     * <p>{@code /} and directories map to {@code index.why}; {@code /about} also tries
     * {@code /about.why}, which is the extension-free URL.
     */
    public Path resolve(String urlPath) {
        String path = urlPath == null || urlPath.isEmpty() ? "/" : urlPath;
        if (!path.startsWith("/")) path = "/" + path;

        Path candidate = root.resolve(path.substring(1)).normalize();
        if (!candidate.startsWith(root)) return null;

        if (Files.isDirectory(candidate)) candidate = candidate.resolve(INDEX_PAGE);
        Path found = Files.isRegularFile(candidate) ? candidate : null;
        if (found == null) {
            String name = candidate.getFileName().toString();
            if (!name.contains(".")) {
                Path withExtension = candidate.resolveSibling(name + PAGE_EXTENSION);
                if (Files.isRegularFile(withExtension)) found = withExtension;
            }
        }
        if (found == null || isImportOnly(found) || !isInsideSite(found) || isDatabase(found)) return null;
        return isPage(found) || isPublicAsset(found) ? found : null;
    }

    /**
     * Whether the real file is still under the real site root. This rejects
     * symlinks and symlinked directories that point outside the site.
     */
    public boolean isInsideSite(Path file) {
        try {
            return file.toRealPath().startsWith(root.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    /** One source line for a diagnostic, or null when the path is outside the site or unreadable. */
    String sourceLine(String file, int line) {
        if (file == null || line <= 0) return null;
        Path path = root.resolve(file).normalize();
        if (!path.startsWith(root) || !isInsideSite(path) || !Files.isRegularFile(path)) return null;
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            return line <= lines.size() ? lines.get(line - 1) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** The configured database is never a public asset, whatever its filename or extension. */
    private boolean isDatabase(Path file) {
        try {
            return file.toRealPath().equals(dbFile.toRealPath());
        } catch (IOException e) {
            return file.toAbsolutePath().normalize().equals(dbFile);
        }
    }

    /** Files whose name starts with {@code _} can be imported but have no URL. */
    public static boolean isImportOnly(Path file) {
        return file.getFileName().toString().startsWith("_");
    }

    /**
     * The static asset allowlist. Pages are handled separately. Dotfiles, database
     * files, schema files, backups and unknown extensions are not public.
     */
    public static boolean isPublicAsset(Path file) {
        String name = file.getFileName().toString();
        if (name.startsWith(".")) return false;
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return false;
        return PUBLIC_EXTENSIONS.contains(name.substring(dot).toLowerCase(Locale.ROOT));
    }
}
