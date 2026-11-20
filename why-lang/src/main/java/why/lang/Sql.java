package why.lang;

import java.util.List;

import why.WhyError;

/**
 * The {@code sql} global: the checked pieces of SQL that a prepared statement cannot bind.
 * Identifiers cannot be parameters, so a page that needs a dynamic table or column name must pass
 * the value through {@link #ident} with an explicit allowlist, keeping the concatenation auditable.
 */
public final class Sql {

    /** Returns {@code value} when it is one of {@code allowed}; otherwise throws. */
    public String ident(Object value, Object allowed, int line) {
        String name = Value.str(value);
        if (!(allowed instanceof List<?> list) || list.isEmpty()) {
            throw new WhyError("sql.ident needs a non-empty list of allowed names", null, line);
        }
        for (Object candidate : list) {
            if (name.equals(Value.str(candidate))) return name;
        }
        StringBuilder choices = new StringBuilder();
        for (Object candidate : list) {
            if (choices.length() > 0) choices.append(", ");
            choices.append(Value.str(candidate));
        }
        throw new WhyError("sql.ident does not allow '" + name + "'; use one of: " + choices, null, line);
    }
}
