package why;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Extracts the site that the Why Gradle plugin packages under {@code META-INF/why-site}. */
final class ClasspathSite {
    static final String INDEX = "META-INF/why-site.index";
    static final String ROOT = "META-INF/why-site/";

    private ClasspathSite() {}

    static Path extract(ClassLoader loader) throws IOException {
        Enumeration<URL> indexes = loader.getResources(INDEX);
        if (!indexes.hasMoreElements()) {
            throw new IOException("no Why site resources on the classpath; missing " + INDEX);
        }
        Path target = Files.createTempDirectory("why-site-");
        target.toFile().deleteOnExit();
        Set<String> seen = new HashSet<>();
        while (indexes.hasMoreElements()) {
            extractIndex(indexes.nextElement(), target, seen);
        }
        return target;
    }

    private static void extractIndex(URL index, Path target, Set<String> seen) throws IOException {
        if ("file".equals(index.getProtocol())) {
            Path indexPath = path(index);
            List<String> names;
            try (InputStream in = Files.newInputStream(indexPath)) {
                names = readIndex(in);
            }
            Path root = indexPath.getParent().resolve("why-site");
            for (String name : names) {
                Path out = targetFor(target, name, seen);
                Path source = root.resolve(name).normalize();
                if (!source.startsWith(root)) {
                    throw new IOException("unsafe Why site resource name '" + name + "'");
                }
                if (!Files.isRegularFile(source)) {
                    throw new IOException("missing Why site resource '" + source + "'");
                }
                prepare(out);
                Files.copy(source, out, StandardCopyOption.REPLACE_EXISTING);
                register(out, target);
            }
            return;
        }
        if ("jar".equals(index.getProtocol())) {
            JarURLConnection connection = (JarURLConnection) index.openConnection();
            connection.setUseCaches(false);
            try (JarFile jar = new JarFile(path(connection.getJarFileURL()).toFile())) {
                JarEntry indexEntry = jar.getJarEntry(INDEX);
                if (indexEntry == null) throw new IOException("missing Why site index '" + INDEX + "'");
                List<String> names;
                try (InputStream in = jar.getInputStream(indexEntry)) {
                    names = readIndex(in);
                }
                for (String name : names) {
                    Path out = targetFor(target, name, seen);
                    JarEntry entry = jar.getJarEntry(ROOT + name);
                    if (entry == null) {
                        throw new IOException("missing Why site resource '" + ROOT + name + "'");
                    }
                    prepare(out);
                    try (InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                    register(out, target);
                }
            }
            return;
        }
        throw new IOException("unsupported Why site resource URL: " + index);
    }

    private static List<String> readIndex(InputStream in) throws IOException {
        List<String> names = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String name = line.strip();
                if (!name.isEmpty()) names.add(name);
            }
        }
        return names;
    }

    private static Path targetFor(Path target, String name, Set<String> seen) throws IOException {
        Path out = target.resolve(name).normalize();
        if (name.startsWith("/") || name.contains("\\") || !out.startsWith(target)) {
            throw new IOException("unsafe Why site resource name '" + name + "'");
        }
        Path relative = target.relativize(out);
        if (relative.toString().isEmpty()) {
            throw new IOException("unsafe Why site resource name '" + name + "'");
        }
        if (!seen.add(relative.toString().replace('\\', '/'))) {
            throw new IOException("duplicate Why site resource '" + name + "' on the classpath");
        }
        return out;
    }

    private static void prepare(Path out) throws IOException {
        Files.createDirectories(out.getParent());
    }

    private static void register(Path out, Path target) {
        List<Path> directories = new ArrayList<>();
        Path directory = out.getParent();
        while (directory != null && directory.startsWith(target) && !directory.equals(target)) {
            directories.add(directory);
            directory = directory.getParent();
        }
        // deleteOnExit removes in reverse registration order: files, deepest folder, then target.
        for (int i = directories.size() - 1; i >= 0; i--) {
            directories.get(i).toFile().deleteOnExit();
        }
        out.toFile().deleteOnExit();
    }

    private static Path path(URL url) throws IOException {
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IOException("invalid Why site resource URL: " + url, e);
        }
    }
}
