package io.github.huyz0.jzap.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Tool-independent identity of a mutant, as specified in docs/parity-and-benchmarks.md.
 *
 * <p>Deliberately keyed on source line rather than bytecode offset: offsets shift under
 * different mutation strategies (schemata inserts branches), so they are not comparable
 * between engines, nor between jzap and PIT.
 *
 * @param className  binary class name, e.g. {@code com.example.Foo$Inner}
 * @param methodName method name, e.g. {@code compute} or {@code <init>}
 * @param descriptor JVM method descriptor, e.g. {@code (II)I}
 * @param line       source line number, or 0 when unknown
 * @param mutator    mutator id, e.g. {@code MATH}
 * @param ordinal    0-based index among mutants sharing (line, mutator), in bytecode order
 */
public record MutantKey(
        String className,
        String methodName,
        String descriptor,
        int line,
        String mutator,
        int ordinal) implements Comparable<MutantKey> {

    public MutantKey {
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("className is required");
        }
        if (mutator == null || mutator.isBlank()) {
            throw new IllegalArgumentException("mutator is required");
        }
    }

    /** Stable, human-readable rendering. This is the form written to reports and caches. */
    @JsonIgnore
    public String asString() {
        return className + "::" + methodName + descriptor + "::" + line + "::" + mutator + "#" + ordinal;
    }

    public static MutantKey parse(String s) {
        String[] parts = s.split("::", 4);
        if (parts.length != 4) {
            throw new IllegalArgumentException("not a mutant key: " + s);
        }
        int descStart = parts[1].indexOf('(');
        if (descStart < 0) {
            throw new IllegalArgumentException("not a mutant key, missing descriptor: " + s);
        }
        int hash = parts[3].lastIndexOf('#');
        if (hash < 0) {
            throw new IllegalArgumentException("not a mutant key, missing ordinal: " + s);
        }
        return new MutantKey(
                parts[0],
                parts[1].substring(0, descStart),
                parts[1].substring(descStart),
                Integer.parseInt(parts[2]),
                parts[3].substring(0, hash),
                Integer.parseInt(parts[3].substring(hash + 1)));
    }

    @Override
    public int compareTo(MutantKey o) {
        int c = className.compareTo(o.className);
        if (c != 0) return c;
        c = methodName.compareTo(o.methodName);
        if (c != 0) return c;
        c = descriptor.compareTo(o.descriptor);
        if (c != 0) return c;
        c = Integer.compare(line, o.line);
        if (c != 0) return c;
        c = mutator.compareTo(o.mutator);
        if (c != 0) return c;
        return Integer.compare(ordinal, o.ordinal);
    }

    @Override
    public String toString() {
        return asString();
    }
}
