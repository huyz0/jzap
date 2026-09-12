package io.github.huyz0.jzap.model;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Source lines in scope, keyed by repository-relative path.
 *
 * <p>Deliberately a plain data structure with no knowledge of git. A CI system that can only
 * hand over a patch file produces one of these just as a git range does, which is what keeps
 * the engine independent of any VCS.
 *
 * <p>Mutants carry only the simple source file name from the class file, so matching is by
 * path suffix: {@code ex/Calc.java} matches a changed
 * {@code modules/app/src/main/java/ex/Calc.java}.
 */
public final class ChangedLines {

    private final Map<String, Set<Integer>> lines = new LinkedHashMap<>();

    public static ChangedLines empty() {
        return new ChangedLines();
    }

    public void add(String path, int line) {
        lines.computeIfAbsent(normalise(path), k -> new LinkedHashSet<>()).add(line);
    }

    public void addRange(String path, int firstLine, int lastLineInclusive) {
        Set<Integer> set = lines.computeIfAbsent(normalise(path), k -> new LinkedHashSet<>());
        for (int l = firstLine; l <= lastLineInclusive; l++) {
            set.add(l);
        }
    }

    /** Records a path as changed without any specific lines, for class-granularity scoping. */
    public void addPath(String path) {
        lines.computeIfAbsent(normalise(path), k -> new LinkedHashSet<>());
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public Set<String> paths() {
        return Set.copyOf(lines.keySet());
    }

    public int lineCount() {
        return lines.values().stream().mapToInt(Set::size).sum();
    }

    /** True if any changed path ends with {@code suffix} and that path changed at {@code line}. */
    public boolean containsLine(String suffix, int line) {
        String needle = normalise(suffix);
        for (Map.Entry<String, Set<Integer>> e : lines.entrySet()) {
            if (matches(e.getKey(), needle) && e.getValue().contains(line)) {
                return true;
            }
        }
        return false;
    }

    /** True if any changed path ends with {@code suffix}, regardless of which lines changed. */
    public boolean containsPath(String suffix) {
        String needle = normalise(suffix);
        for (String path : lines.keySet()) {
            if (matches(path, needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String path, String suffix) {
        return path.equals(suffix) || path.endsWith("/" + suffix);
    }

    private static String normalise(String path) {
        return path.replace('\\', '/');
    }

    @Override
    public String toString() {
        return "ChangedLines[" + lines.size() + " files, " + lineCount() + " lines]";
    }
}
