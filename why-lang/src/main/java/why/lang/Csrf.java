package why.lang;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * The {@code csrf} global: a per-browser token rendered into a form and checked against the
 * request's {@code why_csrf} cookie on POST. The cookie is HttpOnly, so a page can only learn the
 * token from the server-rendered HTML, which is exactly what a cross-site request cannot read.
 */
public final class Csrf {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String token;
    private final boolean fresh;
    private boolean read;

    public Csrf(String token, boolean fresh) {
        this.token = token;
        this.fresh = fresh;
    }

    /** A new unguessable token. */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Whether a cookie value is shaped like a token this server mints. */
    public static boolean valid(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{16,128}");
    }

    /** The token to put in a hidden form field; reading it opts the response into a cookie. */
    public String token() {
        read = true;
        return token;
    }

    /** True when the page used a token the browser does not have yet. */
    public boolean shouldSetCookie() {
        return fresh && read;
    }

    /** True when a submitted form field carries this browser's token. */
    public boolean matches(Object submitted) {
        return !fresh && token.equals(Value.str(submitted));
    }
}
