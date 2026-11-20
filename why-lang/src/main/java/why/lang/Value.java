package why.lang;

import java.util.List;
import java.util.Map;

import why.WhyError;

/** Runtime value helpers: truthiness, string conversion and operators. */
public final class Value {
    private Value() {}

    /** Ruby-style truthiness: only nil and false are falsy. */
    public static boolean truthy(Object v) {
        return v != null && v != Boolean.FALSE;
    }

    public static String str(Object v) {
        if (v == null) return "";
        if (v instanceof String s) return s;
        return String.valueOf(v);
    }

    public static String typeName(Object v) {
        if (v == null) return "nil";
        if (v instanceof Long || v instanceof Integer || v instanceof Short) return "int";
        if (v instanceof Double || v instanceof Float) return "float";
        if (v instanceof String) return "string";
        if (v instanceof Boolean) return "bool";
        if (v instanceof List) return "list";
        if (v instanceof Map) return "hash";
        if (v instanceof Db) return "db";
        if (v instanceof Sql) return "sql";
        return v.getClass().getSimpleName();
    }

    public static Object binary(String op, Object a, Object b, int line) {
        return switch (op) {
            case "+" -> (a instanceof String || b instanceof String) ? str(a) + str(b) : arithmetic(op, a, b, line);
            case "-", "*", "/", "%" -> arithmetic(op, a, b, line);
            case "==" -> equal(a, b);
            case "!=" -> !equal(a, b);
            case "<", "<=", ">", ">=" -> comparison(op, a, b, line);
            default -> throw new WhyError("unknown operator '" + op + "'", null, line);
        };
    }

    public static boolean equal(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number x && b instanceof Number y) return x.doubleValue() == y.doubleValue();
        return a.equals(b);
    }

    public static Object negate(Object v, int line) {
        if (v instanceof Number n) {
            return (v instanceof Double || v instanceof Float) ? (Object) (-n.doubleValue()) : (Object) (-n.longValue());
        }
        throw new WhyError("cannot negate " + typeName(v), null, line);
    }

    public static long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof Boolean b) return b ? 1 : 0;
        if (v == null) return 0;
        if (v instanceof String s) {
            try {
                return (long) Double.parseDouble(s.strip());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        throw new WhyError("cannot convert " + typeName(v) + " to int", null, 0);
    }

    public static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof Boolean b) return b ? 1 : 0;
        if (v == null) return 0;
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.strip());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        throw new WhyError("cannot convert " + typeName(v) + " to float", null, 0);
    }

    private static Object arithmetic(String op, Object a, Object b, int line) {
        double x = number(a, line);
        double y = number(b, line);
        boolean floating = a instanceof Double || a instanceof Float || b instanceof Double || b instanceof Float;
        if (floating) {
            return switch (op) {
                case "+" -> x + y;
                case "-" -> x - y;
                case "*" -> x * y;
                case "/" -> x / divisor(y, line);
                default -> x % divisor(y, line);
            };
        }
        long l = (long) x;
        long m = (long) y;
        return switch (op) {
            case "+" -> l + m;
            case "-" -> l - m;
            case "*" -> l * m;
            case "/" -> l / divisor(m, line);
            default -> l % divisor(m, line);
        };
    }

    private static double number(Object v, int line) {
        if (v instanceof Number n) return n.doubleValue();
        throw new WhyError("expected a number, got " + typeName(v), null, line);
    }

    private static double divisor(double value, int line) {
        if (value == 0) throw new WhyError("division by zero", null, line);
        return value;
    }

    private static long divisor(long value, int line) {
        if (value == 0) throw new WhyError("division by zero", null, line);
        return value;
    }

    private static boolean comparison(String op, Object a, Object b, int line) {
        int cmp;
        if (a instanceof String sa && b instanceof String sb) {
            cmp = sa.compareTo(sb);
        } else {
            cmp = Double.compare(number(a, line), number(b, line));
        }
        return switch (op) {
            case "<" -> cmp < 0;
            case "<=" -> cmp <= 0;
            case ">" -> cmp > 0;
            default -> cmp >= 0;
        };
    }
}
