package why.lang;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.regex.Pattern;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import why.WhyError;

/**
 * Password key derivation for pages: PBKDF2-HMAC-SHA256 over the password and salt bytes as
 * UTF-8, returning a 32-byte key as lowercase hex. Iterating the derivation makes each guess
 * expensive in a way a single SHA-256 does not.
 *
 * <p>A stored row keeps its parameters, for example
 * {@code pbkdf2-sha256$600000$<salt>$<key>}, so the cost can be raised later without invalidating
 * existing rows. {@link #verify} compares in constant time, {@link #hash} writes a fresh salt, and
 * {@link #needsRehash} tells a page when a successful sign-in should replace an old row.
 */
public final class Passwords {
    /** A floor that keeps a misconfigured page from storing a cheap, guessable key. */
    public static final int MIN_ITERATIONS = 10_000;
    public static final int MAX_ITERATIONS = 1_000_000;

    private static final String ALGORITHM = "pbkdf2-sha256";
    private static final int KEY_BITS = 256;
    private static final int KEY_BYTES = KEY_BITS / 8;
    private static final int SALT_BYTES = 16;
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{" + (KEY_BYTES * 2) + "}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {}

    /** Hex PBKDF2-HMAC-SHA256 key for one password and salt. */
    public static String pbkdf2(String password, String salt, long iterations) {
        checkIterations(iterations);
        char[] chars = password == null ? new char[0] : password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars,
                (salt == null ? "" : salt).getBytes(StandardCharsets.UTF_8),
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

    /**
     * Whether {@code password} matches a {@code pbkdf2-sha256$rounds$salt$key} row, comparing the
     * derived keys without an early exit. A row that cannot be parsed is a mismatch, not an error.
     */
    public static boolean verify(String password, String stored) {
        Stored parsed = parse(stored);
        if (parsed == null) return false;
        byte[] expected = parsed.key().getBytes(StandardCharsets.US_ASCII);
        byte[] actual = pbkdf2(password, parsed.salt(), parsed.iterations())
                .getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(actual, expected);
    }

    /**
     * Whether a stored row should be replaced after a successful sign-in: it is malformed, or its
     * cost is below {@code iterations}. Raising the site's target therefore upgrades rows lazily.
     */
    public static boolean needsRehash(String stored, long iterations) {
        checkIterations(iterations);
        Stored parsed = parse(stored);
        return parsed == null || parsed.iterations() < iterations;
    }

    /** A fresh 16-byte salt and the row to store: {@code pbkdf2-sha256$rounds$salt$key}. */
    public static String hash(String password, long iterations) {
        checkIterations(iterations);
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        String encoded = HexFormat.of().formatHex(salt);
        return ALGORITHM + "$" + iterations + "$" + encoded + "$"
                + pbkdf2(password, encoded, iterations);
    }

    private static void checkIterations(long iterations) {
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new WhyError("pbkdf2 needs an iteration count from " + MIN_ITERATIONS + " to "
                    + MAX_ITERATIONS + ", got " + iterations);
        }
    }

    /** A parsed row, or null when it is not in the one format this class writes. */
    private static Stored parse(String stored) {
        if (stored == null) return null;
        String[] parts = stored.split("\\$", -1);
        if (parts.length != 4 || !parts[0].equals(ALGORITHM)) return null;
        if (parts[2].isEmpty() || parts[2].length() > 256) return null;
        if (!KEY.matcher(parts[3]).matches()) return null;
        int iterations;
        try {
            iterations = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) return null;
        return new Stored(iterations, parts[2], parts[3]);
    }

    private record Stored(int iterations, String salt, String key) {}
}
