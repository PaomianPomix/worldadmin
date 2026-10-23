package net.shiroha233.roadweaver.runtime;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Mutable state owned by a particular loaded level instance, never by coordinates or dimension id alone. */
public final class LevelScopedState<T> {
    private final Map<Object, T> states = new IdentityHashMap<>();

    public synchronized T get(Object level, Supplier<T> factory) {
        Objects.requireNonNull(level, "level");
        return states.computeIfAbsent(level, ignored -> Objects.requireNonNull(factory.get(), "state"));
    }

    public synchronized T peek(Object level) {
        return states.get(level);
    }

    public synchronized T remove(Object level) {
        return states.remove(level);
    }

    public synchronized void clear() {
        states.clear();
    }

    public synchronized List<T> valuesSnapshot() {
        return new ArrayList<>(states.values());
    }

    public synchronized List<Object> keysSnapshot() {
        return new ArrayList<>(states.keySet());
    }
}
