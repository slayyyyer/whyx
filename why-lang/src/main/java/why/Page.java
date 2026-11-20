package why;

import java.nio.file.Path;
import java.util.List;

/** A page file split into its sections. Line numbers are 1-based lines in the file. */
public record Page(Path file, List<Import> imports, String controllerSource, int controllerLine,
                   String viewSource, int viewLine) {

    /** One name from the {@code imports:} section. */
    public record Import(String name, int line) {}
}
