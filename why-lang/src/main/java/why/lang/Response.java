package why.lang;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import why.WhyError;
import why.util.Json;

/**
 * The {@code response} global: per-page response controls. A page may set the status, add headers,
 * choose a Content-Security-Policy, or answer JSON instead of the rendered view. Everything else
 * about the response stays controlled by the server.
 */
public final class Response {
    private static final int MAX_CSP_LENGTH = 4_096;
    private static final int MAX_HEADER_VALUE = 2_048;

    /** Headers the server owns: setting them from a page would break framing or the body. */
    private static final Set<String> RESERVED = Set.of(
            "content-length", "transfer-encoding", "connection", "keep-alive",
            "content-encoding", "set-cookie", "date", "server", "upgrade");

    private int status = 200;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private String csp;
    private String json;

    /** Sets the status code; the last call wins. Only final statuses are accepted. */
    public void status(Object code, int line) {
        long value = Value.toLong(code);
        if (value < 200 || value > 599) {
            throw new WhyError("response.status needs a code from 200 to 599, got " + value, null, line);
        }
        this.status = (int) value;
    }

    public int status() {
        return status;
    }

    /** Sets a response header; the last value for a name wins. */
    public void header(String name, String value, int line) {
        if (name == null || name.isBlank()) {
            throw new WhyError("response.header needs a header name", null, line);
        }
        String trimmedName = name.strip();
        if (!isToken(trimmedName)) {
            throw new WhyError("response.header name '" + trimmedName + "' is not a valid header name", null, line);
        }
        if (RESERVED.contains(trimmedName.toLowerCase(java.util.Locale.ROOT))) {
            throw new WhyError("response.header cannot set '" + trimmedName + "'", null, line);
        }
        String trimmedValue = value == null ? "" : value.strip();
        if (trimmedValue.length() > MAX_HEADER_VALUE) {
            throw new WhyError("response.header value is limited to " + MAX_HEADER_VALUE + " characters", null, line);
        }
        for (int i = 0; i < trimmedValue.length(); i++) {
            char c = trimmedValue.charAt(i);
            if (c == '\r' || c == '\n' || c < 0x20 || c == 0x7f) {
                throw new WhyError("response.header value must be one line of printable ASCII", null, line);
            }
        }
        this.headers.put(trimmedName, trimmedValue);
    }

    public Map<String, String> headers() {
        return headers;
    }

    /** Answers JSON instead of the rendered view; the last call wins. */
    public void json(Object value, int line) {
        this.json = Json.write(value, line);
    }

    /** The JSON body the page asked for, or null for HTML. */
    public String json() {
        return json;
    }

    /** Sets this page's policy; the last call wins. */
    public void csp(String policy, int line) {
        if (policy == null || policy.isBlank()) {
            throw new WhyError("response.csp needs a non-empty policy", null, line);
        }
        String trimmed = policy.strip();
        if (trimmed.length() > MAX_CSP_LENGTH) {
            throw new WhyError("response.csp is limited to " + MAX_CSP_LENGTH + " characters", null, line);
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                throw new WhyError("response.csp must be printable ASCII on one line", null, line);
            }
        }
        this.csp = trimmed;
    }

    /** The policy the page opted into, or null for the server default. */
    public String csp() {
        return csp;
    }

    /** RFC 9110 token: what a header name may contain. */
    private static boolean isToken(String text) {
        if (text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean allowed = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!allowed) return false;
        }
        return true;
    }
}
