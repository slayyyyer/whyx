package why.lang;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.HexFormat;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import why.WhyError;

/**
 * Password key derivation for pages: PBKDF2-HMAC-SHA256 over the password and salt bytes as
 * UTF-8, returning a 32-byte key as lowercase hex. Iterating the derivation makes each guess
 * expensive in a way a single SHA-256 does not.
 *
 * <p>A page is expected to store the parameters with the key, for example
 * {@code pbkdf2-sha256$120000$<salt>$<key>}, so the cost can be raised later without
 * invalidating existing rows.
 */
public final class Passwords {
    /** Enough iterations to be meaningful, and low enough to finish inside a request. */
    public static final int MIN_ITERATIONS = 1;
    public static final int MAX_ITERATIONS = 1_000_000;
    private static final int KEY_BITS = 256;
    private static final int KEY_BYTES = KEY_BITS / 8;

    private Passwords() {}

    /** Hex PBKDF2-HMAC-SHA256 key for one password and salt. */
    public static String pbkdf2(String password, String salt, long iterations) {
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new WhyError("pbkdf2 needs an iteration count from " + MIN_ITERATIONS + " to "
                    + MAX_ITERATIONS + ", got " + iterations);
        }
        char[] chars = password == null ? new char[0] : password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, (salt == null ? "" : salt).getBytes(StandardCharsets.UTF_8),
                (int) iterations, KEY_BITS);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
            if (key.length != KEY_BYTES) {
                throw new WhyError("pbkdf2 produced " + key.length + " bytes instead of " + KEY_BYTES);
            }
            return HexFormat.of().formatHex(key);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new WhyError("pbkdf2 is unavailable in this JVM: " + e.getMessage());
        } finally {
            spec.clearPassword();
            Arrays.fill(chars, '\0');
        }
    }
}
