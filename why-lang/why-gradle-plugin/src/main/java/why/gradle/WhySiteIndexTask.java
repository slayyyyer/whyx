package why.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Writes the relative paths of every packaged site file, sorted for reproducible jars. */
public abstract class WhySiteIndexTask extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getSiteDir();

    @OutputFile
    public abstract RegularFileProperty getIndexFile();

    @TaskAction
    public void generate() throws IOException {
        Path site = getSiteDir().get().getAsFile().toPath();
        List<String> entries = new ArrayList<>();
        if (Files.isDirectory(site)) {
            try (Stream<Path> walk = Files.walk(site)) {
                walk.filter(Files::isRegularFile)
                        .filter(file -> !WhyPlugin.isDatabaseFile(file))
                        .map(file -> site.relativize(file).toString().replace('\\', '/'))
                        .sorted()
                        .forEach(entries::add);
            }
        }
        Files.createDirectories(getIndexFile().get().getAsFile().toPath().getParent());
        String index = entries.isEmpty() ? "" : String.join("\n", entries) + "\n";
        Files.writeString(getIndexFile().get().getAsFile().toPath(), index, StandardCharsets.UTF_8);
    }
}
