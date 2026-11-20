package why;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import why.lang.Interpreter;

/** The interpreter's builtin list and the two language references must name the same functions. */
class BuiltinsDocTest {

    @Test
    void everyBuiltinAppearsInBothReferences() throws Exception {
        String readme = Files.readString(Path.of("README.md"));
        String usage = Files.readString(Path.of("docs/usage.html"));

        for (String builtin : Interpreter.BUILTINS) {
            assertTrue(readme.contains(builtin), "README.md does not mention the builtin '" + builtin + "'");
            assertTrue(usage.contains(builtin),
                    "docs/usage.html does not mention the builtin '" + builtin + "'");
        }
    }
}
