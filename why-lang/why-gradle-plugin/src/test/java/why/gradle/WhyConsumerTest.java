package why.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WhyConsumerTest {
    private static final String VERSION = requiredProperty("why.test.version");

    @TempDir
    Path project;

    @Test
    void checksTestsAndRunsThePublishedConsumer() throws Exception {
        Path repository = Path.of(requiredProperty("why.test.repo"));
        Path gradleHome = Path.of(requiredProperty("why.test.gradleHome"));
        int port = freePort();
        writeProject(repository, port);

        BuildResult check = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("whyCheck", "--stacktrace")
                .withPluginClasspath()
                .build();
        assertEquals(TaskOutcome.SUCCESS, check.task(":whyCheck").getOutcome());
        assertTrue(check.getOutput().contains("no problems"), check.getOutput());

        BuildResult test = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments("test", "--stacktrace")
                .withPluginClasspath()
                .build();
        assertEquals(TaskOutcome.SUCCESS, test.task(":test").getOutcome());

        assertWhyRun(gradleHome, port);
    }

    private void writeProject(Path repository, int port) throws IOException {
        String repo = repository.toUri().toString();
        write("settings.gradle.kts", """
                pluginManagement {
                    repositories {
                        maven { url = uri("%s") }
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }

                rootProject.name = "consumer"
                """.formatted(repo));
        write("build.gradle.kts", """
                plugins {
                    id("io.github.slayyyyer.why") version "%s"
                }

                group = "consumer"
                version = "1.0"

                repositories {
                    maven { url = uri("%s") }
                    mavenCentral()
                }

                why {
                    port.set(%d)
                }

                dependencies {
                    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
                    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
                }

                tasks.test {
                    useJUnitPlatform()
                }
                """.formatted(VERSION, repo, port));
        write("src/why/index.why", "view:\n<p>from packaged site</p>\n");
        write("src/test/java/demo/SiteFromClasspathTest.java", """
                package demo;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import java.net.URI;
                import java.net.http.HttpClient;
                import java.net.http.HttpRequest;
                import java.net.http.HttpResponse;

                import org.junit.jupiter.api.Test;

                import why.Site;
                import why.WhyServer;

                class SiteFromClasspathTest {
                    @Test
                    void servesThePackagedPage() throws Exception {
                        Site site = Site.fromClasspath();
                        WhyServer server = new WhyServer(site, 0);
                        server.start();
                        try {
                            HttpResponse<String> response = HttpClient.newHttpClient().send(
                                    HttpRequest.newBuilder(
                                            URI.create("http://127.0.0.1:" + server.port() + "/")).GET().build(),
                                    HttpResponse.BodyHandlers.ofString());
                            assertEquals(200, response.statusCode());
                            assertEquals("<p>from packaged site</p>\\n",
                                    response.body().replace("\\r\\n", "\\n"));
                        } finally {
                            server.stop();
                        }
                    }
                }
                """);
    }

    private void assertWhyRun(Path gradleHome, int port) throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path gradle = gradleHome.resolve("bin").resolve(windows ? "gradle.bat" : "gradle");
        List<String> command = new ArrayList<>();
        if (windows) {
            command.add("cmd.exe");
            command.add("/d");
            command.add("/c");
            command.add("\"" + gradle + "\" --no-daemon -p \"" + project + "\" whyRun");
        } else {
            command.add(gradle.toString());
            command.add("--no-daemon");
            command.add("-p");
            command.add(project.toString());
            command.add("whyRun");
        }

        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putIfAbsent("JAVA_HOME", System.getProperty("java.home"));
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader lines = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
            }
        });

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    fail("whyRun exited before serving:\n" + output);
                }
                try {
                    response = client.send(
                            HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + port + "/")).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) break;
                } catch (IOException ignored) {
                }
                Thread.sleep(100);
            }
            assertNotNull(response, () -> "whyRun did not become ready:\n" + output);
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("from packaged site"), response.body());
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            reader.join(1_000);
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing test property " + name);
        }
        return value;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void write(String path, String content) throws IOException {
        Path file = project.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
