package why.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import why.WhyError;

class PasswordsTest {

    private static Object eval(String source) {
        Scope scope = new Scope(null);
        Interpreter interpreter = new Interpreter(Map.of(), scope);
        return interpreter.eval(new Parser(source, "test.why", 1).parseExpressionOnly(), scope);
    }

    @Test
    void aStoredHashVerifiesInConstantTime() {
        // PBKDF2-HMAC-SHA256("password", "salt", 10000 iterations, 32 bytes).
        String key = "5ec02b91a4b59c6f59dd5fbe4ca649ece4fa8568cdb8ba36cf41426e8805522b";
        String row = "pbkdf2-sha256$10000$salt$" + key;

        assertEquals(true, eval("password_matches(\"password\", \"" + row + "\")"));
        assertEquals(false, eval("password_matches(\"Password\", \"" + row + "\")"));
        assertEquals(false, eval("password_matches(\"password\", \"pbkdf2-sha256$10000$salt$"
                + "4ec02b91a4b59c6f59dd5fbe4ca649ece4fa8568cdb8ba36cf41426e8805522b\")"));

        for (String stored : List.of(
                "pbkdf2-sha256$10000$salt$" + key.toUpperCase(),
                "scrypt$10000$salt$" + key,
                "pbkdf2-sha256$10000$salt$nothex",
                "pbkdf2-sha256$10000$salt",
                "pbkdf2-sha256$9999$salt$" + key)) {
            assertEquals(false, eval("password_matches(\"password\", \"" + stored + "\")"), stored);
        }
    }

    @Test
    void hashesGetAFreshSaltAndReportTheirCost() {
        String first = (String) eval("password_hash(\"whylang\", 10000)");
        String second = (String) eval("password_hash(\"whylang\", 10000)");

        assertTrue(first.matches("pbkdf2-sha256\\$10000\\$[0-9a-f]{32}\\$[0-9a-f]{64}"), first);
        assertNotEquals(first, second, "each row should get its own salt");
        assertEquals(true, eval("password_matches(\"whylang\", \"" + first + "\")"));
        assertEquals(false, eval("password_needs_rehash(\"" + first + "\", 10000)"));
        assertEquals(true, eval("password_needs_rehash(\"" + first + "\", 600000)"));
        assertEquals(true, eval("password_needs_rehash(\"garbage\", 10000)"));
        assertEquals(true, eval("password_needs_rehash(\"pbkdf2-sha256$10000$salt$"
                + "5ec02b91a4b59c6f59dd5fbe4ca649ece4fa8568cdb8ba36cf41426e8805522b\", 600000)"));
        assertEquals(false, eval("password_needs_rehash(\"pbkdf2-sha256$20000$salt$"
                + "5ec02b91a4b59c6f59dd5fbe4ca649ece4fa8568cdb8ba36cf41426e8805522b\", 10000)"));
    }

    @Test
    void passwordHelpersRejectImpossibleArguments() {
        assertThrows(WhyError.class, () -> eval("password_hash(\"a\")"));
        assertThrows(WhyError.class, () -> eval("password_hash(\"a\", 1)"));
        assertThrows(WhyError.class, () -> eval("password_matches(\"a\")"));
        assertThrows(WhyError.class, () -> eval("password_needs_rehash(\"a\")"));
        assertThrows(WhyError.class, () -> eval("password_needs_rehash(\"a\", 5000000)"));

        assertTrue(Interpreter.BUILTINS.contains("password_hash"));
        assertTrue(Interpreter.BUILTINS.contains("password_matches"));
        assertTrue(Interpreter.BUILTINS.contains("password_needs_rehash"));
    }
}
