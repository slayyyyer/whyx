package why.lang;

/**
 * Thrown by the {@code redirect} builtin. Pages have no response object, so this signal travels
 * out of the interpreter and the server answers with a 303 instead of a rendered view.
 */
public final class Redirect extends RuntimeException {
    private final String location;

    public Redirect(String location) {
        super(null, null, false, false);
        this.location = location;
    }

    public String location() {
        return location;
    }

    /** Only single-slash path-absolute locations on this origin are allowed. */
    public static boolean isSameOriginPath(String target) {
        if (target == null || target.isEmpty() || !target.startsWith("/") || target.startsWith("//")) {
            return false;
        }
        if (target.indexOf('\\') >= 0) return false;
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) return false;
        }
        return true;
    }
}
