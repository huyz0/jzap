package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared state for one class visit: where we are in the bytecode, and whether the mutator
 * asking should actually apply its change.
 *
 * <p>Two modes. In {@code COLLECT} every registration is recorded and nothing is applied. In
 * {@code APPLY} exactly one mutation is applied, and the visitor chain contains only the
 * mutator that owns the target key — so no mutator ever observes bytecode another mutator
 * has already altered, which is what keeps ordinals identical between the two modes.
 */
public final class MutationContext {

    public enum Mode { COLLECT, APPLY }

    private final Mode mode;
    private final String moduleId;
    private final String className;
    private final MutantKey target;

    private String sourceFile;
    private SourceMap sourceMap = SourceMap.EMPTY;
    private boolean lineIsInlined;
    private String methodName = "";
    private String descriptor = "";
    private int line;
    private final Map<String, Integer> ordinals = new HashMap<>();
    private final List<Mutant> collected = new ArrayList<>();
    private boolean applied;

    private MutationContext(Mode mode, String moduleId, String className, MutantKey target) {
        this.mode = mode;
        this.moduleId = moduleId;
        this.className = className;
        this.target = target;
    }

    public static MutationContext collecting(String moduleId, String className) {
        return new MutationContext(Mode.COLLECT, moduleId, className, null);
    }

    public static MutationContext applying(String className, MutantKey target) {
        return new MutationContext(Mode.APPLY, null, className, target);
    }

    public void sourceFile(String sourceFile) {
        this.sourceFile = sourceFile;
    }

    /**
     * The class's line-number translation table, if it has one.
     *
     * <p>Kotlin gives inlined code synthetic line numbers past the end of the file. Translating
     * them here, rather than in the reporters, means the mutant key itself carries the real line
     * — and the key is what the cache, the parity harness and every report agree on.
     */
    public void sourceMap(SourceMap sourceMap) {
        this.sourceMap = sourceMap == null ? SourceMap.EMPTY : sourceMap;
    }

    /** Called when entering a method. Ordinals reset here, so keys survive unrelated edits. */
    public void enterMethod(String name, String desc) {
        this.methodName = name;
        this.descriptor = desc;
        this.line = 0;
        this.ordinals.clear();
    }

    public void line(int line) {
        this.lineIsInlined = sourceMap.isInlined(line);
        this.line = sourceMap.toSourceLine(line);
    }

    public int line() {
        return line;
    }

    public String className() {
        return className;
    }

    public String methodName() {
        return methodName;
    }

    public String descriptor() {
        return descriptor;
    }

    /**
     * Registers a mutation point and reports whether to apply it.
     *
     * @param mutatorId   the asking mutator's id
     * @param description human-readable, source-faithful description of the change
     * @return true only in APPLY mode, and only for the one targeted mutant
     */
    public boolean shouldMutate(String mutatorId, String description) {
        String bucket = line + "|" + mutatorId;
        int ordinal = ordinals.merge(bucket, 0, (a, b) -> a + 1);
        MutantKey key = new MutantKey(className, methodName, descriptor, line, mutatorId, ordinal);
        if (mode == Mode.COLLECT) {
            // Saying so matters: the same source line appears once per call site, and a reader
            // seeing it twice should know why rather than suspect the report.
            String annotated = lineIsInlined
                    ? description + " (in an inlined copy of this code)"
                    : description;
            String file = sourceMap.toSourceFile(line).orElse(sourceFile);
            collected.add(Mutant.discovered(key, moduleId, file, annotated));
            return false;
        }
        if (key.equals(target)) {
            applied = true;
            return true;
        }
        return false;
    }

    public List<Mutant> collected() {
        return List.copyOf(collected);
    }

    public boolean applied() {
        return applied;
    }

    public MutantKey target() {
        return target;
    }
}
