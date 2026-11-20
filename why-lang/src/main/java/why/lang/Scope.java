package why.lang;

import java.util.HashMap;
import java.util.Map;

/** A lexical scope chain. Assignment updates the nearest scope that already owns the name. */
public final class Scope {
    private final Scope parent;
    private final Map<String, Object> vars = new HashMap<>();

    public Scope(Scope parent) {
        this.parent = parent;
    }

    public Scope child() {
        return new Scope(this);
    }

    public void define(String name, Object value) {
        vars.put(name, value);
    }

    /** Undefined names read as nil, which keeps templates forgiving. */
    public Object get(String name) {
        if (vars.containsKey(name)) return vars.get(name);
        return parent == null ? null : parent.get(name);
    }

    public Object set(String name, Object value) {
        Scope owner = owner(name);
        if (owner == null) define(name, value);
        else owner.vars.put(name, value);
        return value;
    }

    private Scope owner(String name) {
        if (vars.containsKey(name)) return this;
        return parent == null ? null : parent.owner(name);
    }
}
