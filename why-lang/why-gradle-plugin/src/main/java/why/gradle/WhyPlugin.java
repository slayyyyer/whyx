package why.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.jvm.tasks.ProcessResources;

/** Packages {@code src/why} into the jar and adds {@code whyCheck} and {@code whyRun}. */
public final class WhyPlugin implements Plugin<Project> {
    private static final String GROUP = "io.github.slayyyyer";
    private static final String ARTIFACT = "why-lang";
    private static final String SITE_ROOT = "META-INF/why-site";

    /** The runtime coordinate, derived from the build's version so a release cannot drift. */
    static String runtimeCoordinate() {
        return GROUP + ":" + ARTIFACT + ":" + version();
    }

    private static String version() {
        try (InputStream in = WhyPlugin.class.getResourceAsStream("/why-version.properties")) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                String version = properties.getProperty("version");
                if (version != null && !version.isBlank()) return version.strip();
            }
        } catch (IOException ignored) {
            // Fall through to the jar manifest, then fail loudly below.
        }
        String fromManifest = WhyPlugin.class.getPackage().getImplementationVersion();
        if (fromManifest != null && !fromManifest.isBlank()) return fromManifest;
        throw new IllegalStateException(
                "why-version.properties is missing; the plugin jar was built without its version");
    }

    /** Development databases are not site content and must never be put in a published jar. */
    static boolean isDatabaseFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".db") || name.endsWith(".db-journal")
                || name.endsWith(".db-wal") || name.endsWith(".db-shm");
    }

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("java-library");

        WhyExtension extension = project.getExtensions().create("why", WhyExtension.class);
        extension.getSiteDir().convention(project.getLayout().getProjectDirectory().dir("src/why"));
        extension.getHost().convention("127.0.0.1");
        extension.getPort().convention(8080);
        extension.getDiagnostics().convention(false);
        extension.getSecureCookies().convention(false);

        // Java code in the project can call Site.fromClasspath() and embed WhyServer.
        project.getDependencies().add("api", runtimeCoordinate());

        SourceSet main = project.getExtensions().getByType(SourceSetContainer.class)
                .getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        ProcessResources resources = (ProcessResources) project.getTasks()
                .getByName(main.getProcessResourcesTaskName());

        TaskProvider<WhySiteIndexTask> index = project.getTasks()
                .register("whySiteIndex", WhySiteIndexTask.class, task -> {
                    task.setGroup("build");
                    task.setDescription("Indexes the Why site files packaged from src/why.");
                    task.getSiteDir().set(extension.getSiteDir());
                    task.getIndexFile().set(project.getLayout().getBuildDirectory()
                            .file("generated/why/META-INF/why-site.index"));
                });
        resources.from(extension.getSiteDir(), copy -> {
            copy.into(SITE_ROOT);
            copy.exclude(element -> isDatabaseFile(element.getFile().toPath()));
        });
        resources.from(index, copy -> copy.into("META-INF"));
        resources.dependsOn(index);

        Configuration whyRuntime = project.getConfigurations().create("whyRuntime", configuration -> {
            configuration.setDescription("The Why runtime used by whyCheck and whyRun.");
            configuration.setCanBeConsumed(false);
            configuration.setCanBeResolved(true);
        });
        project.getDependencies().add(whyRuntime.getName(), runtimeCoordinate());

        TaskProvider<JavaExec> check = project.getTasks().register("whyCheck", JavaExec.class, task -> {
            task.setGroup("verification");
            task.setDescription("Checks every page in the Why site directory.");
            task.setClasspath(whyRuntime);
            task.getMainClass().set("why.Main");
            task.getArgumentProviders().add(() -> List.of("check",
                    extension.getSiteDir().get().getAsFile().getAbsolutePath()));
            task.onlyIf(ignored -> extension.getSiteDir().get().getAsFile().isDirectory());
        });
        project.getTasks().named("check").configure(task -> task.dependsOn(check));

        project.getTasks().register("whyRun", JavaExec.class, task -> {
            task.setGroup("application");
            task.setDescription("Serves the Why site directory.");
            task.setClasspath(whyRuntime);
            task.getMainClass().set("why.Main");
            task.getArgumentProviders().add(() -> {
                List<String> arguments = new ArrayList<>();
                arguments.add(extension.getSiteDir().get().getAsFile().getAbsolutePath());
                arguments.add("--host");
                arguments.add(extension.getHost().get());
                arguments.add("--port");
                arguments.add(extension.getPort().get().toString());
                if (extension.getDiagnostics().get()) arguments.add("--dev");
                if (extension.getSecureCookies().get()) arguments.add("--secure-cookies");
                if (extension.getDatabaseFile().isPresent()) {
                    arguments.add("--db");
                    arguments.add(extension.getDatabaseFile().get().getAsFile().getAbsolutePath());
                }
                return arguments;
            });
            task.onlyIf(ignored -> extension.getSiteDir().get().getAsFile().isDirectory());
        });
    }
}
