package why.lang;

/** Thrown by {@code halt(status)} to stop the page after its response status is set. */
public final class Halt extends RuntimeException {
    public Halt() {
        super(null, null, false, false);
    }
}
