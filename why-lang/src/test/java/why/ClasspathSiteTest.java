package why;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import why.lang.Db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClasspathSiteTest {

    @TempDir
    Path dir;

    @Test
    void extractsSiteResourcesFromTheClasspath() throws Exception {
        Site site = Site.fromClasspath();

        assertEquals("<p>classpath site</p>\n", render(site));
        assertEquals("body{color:blue}\n",
                Files.readString(site.root().resolve("style.css")).replace("\r\n", "\n"));
    }

    @Test
    void extractsSiteResourcesFromAJarWithAnExplicitLoader() throws Exception {
        Path jar = dir.resolve("library.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            add(out, "META-INF/why-site.index", "index.why\nstyle.css\nschema.sql\n");
            add(out, "META-INF/why-site/index.why", "view:\n<p>from a jar</p>\n");
            add(out, "META-INF/why-site/style.css", "body{color:green}\n");
            add(out, "META-INF/why-site/schema.sql", "create table notes (id integer);\n");
        }

        URL index = URI.create("jar:" + jar.toUri() + "!/" + ClasspathSite.INDEX).toURL();
        ClassLoader loader = new ClassLoader(null) {
            @Override
            public Enumeration<URL> getResources(String name) {
                if (name.equals(ClasspathSite.INDEX)) return Collections.enumeration(List.of(index));
                return Collections.emptyEnumeration();
            }
        };
        Path database = dir.resolve("library.db");
        Site site = Site.fromClasspath(loader, database);

        assertEquals("<p>from a jar</p>\n", render(site));
        assertEquals("body{color:green}\n", Files.readString(site.root().resolve("style.css")));
        assertTrue(Files.isRegularFile(database));
        try (Db opened = new Db(database)) {
            assertEquals(1, opened.query(
                    "select name from sqlite_master where type = 'table' and name = 'notes'", List.of()).size());
        }
    }

    @Test
    void rejectsDuplicateResourceNamesAfterNormalization() throws Exception {
        Path jar = dir.resolve("duplicate.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            add(out, "META-INF/why-site.index", "a/b.why\na/./b.why\n");
            add(out, "META-INF/why-site/a/b.why", "view:\n<p>one</p>\n");
        }

        URL index = URI.create("jar:" + jar.toUri() + "!/" + ClasspathSite.INDEX).toURL();
        ClassLoader loader = new ClassLoader(null) {
            @Override
            public Enumeration<URL> getResources(String name) {
                if (name.equals(ClasspathSite.INDEX)) return Collections.enumeration(List.of(index));
                return Collections.emptyEnumeration();
            }
        };

        IOException error = assertThrows(IOException.class, () -> Site.fromClasspath(loader, null));
        assertTrue(error.getMessage().contains("duplicate"));
    }

    private static String render(Site site) {
        Path page = site.resolve("/");
        assertNotNull(page);
        return new PageCompiler(site).compile(page).render(Map.of());
    }

    private static void add(JarOutputStream out, String name, String content) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
