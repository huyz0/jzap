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
 * <h2>What a mutator can see</h2>
 *
 * Only {@link #line()}, {@link #methodName()}, {@link #descriptor()} and
 * {@link #shouldMutate} are public; the rest is package-private. A mutator needs to know where
 * it is and to ask whether to act, and nothing else. In particular it cannot touch the ordinal
 * bookkeeping, which is what keys stay stable across discovery and application -- and therefore
 * what every cached verdict is addressed by.
 *
 * <p>Three modes, listed on {@link Mode}. In {@code COLLECT} every registration is recorded and
 * nothing is applied. In {@code APPLY} exactly one mutation is applied, and the visitor chain
 * contains only the mutator that owns the target key — so no mutator ever observes bytecode
 * another mutator has already altered, which is what keeps ordinals identical across modes.
 * {@code SCHEMATA} compiles every mutant in at once.
 */
public final class MutationContext {

    enum Mode {
        /** Record every mutation point, change nothing. */
        COLLECT,
        /** Apply exactly one mutation. */
        APPLY,
        /**
         * Compile every mutant in at once, selected at run time.
         *
         * <p>Shares this class's ordinal bookkeeping rather than reimplementing it, because a
         * schemata index that disagreed with a discovered key by even one ordinal would run the
         * wrong mutant and report the result against the right one.
         */
        SCHEMATA
    }

    private final Mode mode;
    private final String moduleId;
    private final String className;
    private final MutantKey target;
    private Map<MutantKey, Integer> schemataIndices = Map.of();

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

    static MutationContext collecting(String moduleId, String className) {
        return new MutationContext(Mode.COLLECT, moduleId, className, null);
    }

    static MutationContext applying(String className, MutantKey target) {
        return new MutationContext(Mode.APPLY, null, className, target);
    }

    /** @param indices schemata index per mutant; a key that is absent is not seeded */
    static MutationContext schemata(String className, Map<MutantKey, Integer> indices) {
        MutationContext ctx = new MutationContext(Mode.SCHEMATA, null, className, null);
        ctx.schemataIndices = Map.copyOf(indices);
        return ctx;
    }

    void sourceFile(String sourceFile) {
        this.sourceFile = sourceFile;
    }

    /**
     * The class's line-number translation table, if it has one.
     *
     * <p>Kotlin gives inlined code synthetic line numbers past the end of the file. Translating
     * them here, rather than in the reporters, means the mutant key itself carries the real line
     * — and the key is what the cache, the parity harness and every report agree on.
     */
    void sourceMap(SourceMap sourceMap) {
        this.sourceMap = sourceMap == null ? SourceMap.EMPTY : sourceMap;
    }

    /** Called when entering a method. Ordinals reset here, so keys survive unrelated edits. */
    void enterMethod(String name, String desc) {
        this.methodName = name;
        this.descriptor = desc;
        this.line = 0;
        this.ordinals.clear();
    }

    /**
     * Positions this context at a line the caller determined for itself.
     *
     * <p>Two callers, for different reasons. {@link LineNumberTracker} calls it for every line
     * number in the instruction stream, which is how ordinary mutators know where they are. A
     * whole-method mutator does not sit in that stream at all -- it replaces a method body
     * wholesale -- so no line is ever tracked for it, and only it knows that its mutation point
     * is the method's first line.
     *
     * <p>Nothing else may call it. An ordinary mutator overriding its tracked line would put the
     * mutant's key on a line that does not contain it, and the key is what the cache, the parity
     * harness and every report address that mutant by.
     */
    public void positionAtLine(int line) {
        this.lineIsInlined = sourceMap.isInlined(line);
        this.line = sourceMap.toSourceLine(line);
    }

    public int line() {
        return line;
    }

    String className() {
        return className;
    }

    public String methodName() {
        return methodName;
    }

    public String descriptor() {
        return descriptor;
    }

    /**
     * Assigns this mutation point its key, in the one place that does so.
     *
     * <p>Every mode goes through here, so a key means the same thing whether it was discovered,
     * applied on its own, or compiled into a schemata class.
     */
    MutantKey register(String mutatorId) {
        String bucket = line + "|" + mutatorId;
        int ordinal = ordinals.merge(bucket, 0, (a, b) -> a + 1);
        return new MutantKey(className, methodName, descriptor, line, mutatorId, ordinal);
    }

    /**
     * The schemata index for a key, or {@code -1} when no mutant was seeded there.
     *
     * <p>{@code -1} matches {@link io.github.huyz0.jzap.agent.MutantSwitch#NONE}, so a dispatch call compiled
     * with it can never match the active mutant.
     */
    int schemataIndex(MutantKey key) {
        return schemataIndices.getOrDefault(key, -1);
    }

    /**
     * Registers a mutation point and reports whether to apply it.
     *
     * @param mutatorId   the asking mutator's id
     * @param description human-readable, source-faithful description of the change
     * @return true only in APPLY mode, and only for the one targeted mutant
     */
    public boolean shouldMutate(String mutatorId, String description) {
        MutantKey key = register(mutatorId);
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

    List<Mutant> collected() {
        return List.copyOf(collected);
    }

    boolean applied() {
        return applied;
    }
}
