package why;

/** Thrown when a request exceeds its wall-clock execution budget. */
public final class RequestTimeout extends RuntimeException {
    public RequestTimeout() {
        super("request timed out", null, false, false);
    }
}
