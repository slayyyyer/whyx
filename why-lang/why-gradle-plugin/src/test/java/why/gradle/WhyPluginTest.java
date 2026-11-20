package why.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WhyPluginTest {

    @TempDir
    Path project;

    @Test
    void packagesTheSiteIntoTheJarAndRegistersTasks() throws IOException {
        write("settings.gradle.kts", "rootProject.name = \"demo\"\n");
        write("build.gradle.kts", "plugins { id(\"io.github.slayyyyer.why\") }\n\nversion = \"1.0\"\n");
        write("src/why/index.why", "view:\n<p>packaged</p>\n");
        write("src/why/assets/style.css", "body{}\n");
        write("src/why/data.db", "local database contents\n");

        BuildResult result = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("jar", "--stacktrace")
                .withPluginClasspath()
                .build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":jar").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":whySiteIndex").getOutcome());

        Path jarFile = project.resolve("build/libs/demo-1.0.jar");
        assertTrue(Files.isRegularFile(jarFile));
        try (JarFile jar = new JarFile(jarFile.toFile())) {
            assertNotNull(jar.getEntry("META-INF/why-site/index.why"));
            assertNotNull(jar.getEntry("META-INF/why-site/assets/style.css"));
            assertNull(jar.getEntry("META-INF/why-site/data.db"));

            JarEntry indexEntry = jar.getJarEntry("META-INF/why-site.index");
            assertNotNull(indexEntry);
            String index = new String(jar.getInputStream(indexEntry).readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("assets/style.css\nindex.why\n", index);
        }
    }

    private void write(String path, String content) throws IOException {
        Path file = project.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
