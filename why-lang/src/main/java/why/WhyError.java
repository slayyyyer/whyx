package why;

import java.util.ArrayList;
import java.util.List;

/** A load, syntax or runtime error in a page, with a source location when one is known. */
public final class WhyError extends RuntimeException {

    /** One step of the path that led to an error: a partial tag or an import line. */
    public record Frame(String file, int line) {}

    private final String file;
    private final int line;
    private final List<Frame> chain = new ArrayList<>();

    public WhyError(String message, String file, int line) {
        super(message);
        this.file = file;
        this.line = line;
    }

    public WhyError(String message) {
        this(message, null, 0);
    }

    public String file() {
        return file;
    }

    public int line() {
        return line;
    }

    /** The call sites that led here, outermost first. */
    public List<Frame> chain() {
        return List.copyOf(chain);
    }

    /** Prepends a call-site frame, unless the error already sits there. */
    public WhyError at(String file, int line) {
        if (file == null) return this;
        if (file.equals(this.file) && line == this.line) return this;
        chain.add(0, new Frame(file, line));
        return this;
    }

    /** Gives a file-less error its source file, keeping any line it already knows. */
    public WhyError locate(String file, int line) {
        if (this.file != null || file == null) return this;
        WhyError located = new WhyError(getMessage(), file, this.line > 0 ? this.line : line);
        located.chain.addAll(chain);
        return located;
    }

    /** Gives a line-less error the line it happened on. */
    public WhyError atLine(int line) {
        if (this.line > 0 || line <= 0) return this;
        WhyError located = new WhyError(getMessage(), file, line);
        located.chain.addAll(chain);
        return located;
    }

    /** {@code file:line}, or an empty string when the error has no location. */
    public String location() {
        if (file == null) return "";
        return line > 0 ? file + ":" + line : file;
    }

    @Override
    public String toString() {
        String location = location();
        return location.isEmpty() ? getMessage() : location + ": " + getMessage();
    }
}
