package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.core.mutator.IncrementsMutator;
import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantKey;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Discovers mutants in a class and produces the bytecode for one of them.
 *
 * <p>Mutant bytecode is generated here, in the controller, and shipped to the analysis JVM
 * as bytes. The alternative — generating in the analysis JVM, as PIT does to save controller
 * memory — would put a bytecode library on the classpath of the code under test. Shipping a
 * few kilobytes per mutant is cheaper than the dependency-clash class of bug that buys.
 */
public final class MutationEngine {

    private final List<Mutator> mutators;
    private final MutantFilters filters;

    private final Reduction.Tally dropped = new Reduction.Tally();

    /** Discovers with the default filters: loop counters and Kotlin-generated code suppressed. */
    public MutationEngine(List<Mutator> mutators) {
        this(mutators, MutantFilters.defaults());
    }

    public MutationEngine(List<Mutator> mutators, MutantFilters filters) {
        this.mutators = List.copyOf(mutators);
        this.filters = filters;
    }

    public static MutationEngine withDefaults() {
        return new MutationEngine(Mutators.defaults());
    }

    /**
     * What the filters have dropped so far, accumulated across every class discovered.
     *
     * <p>Cumulative on purpose: the reduction report is about a whole run, not one class.
     */
    public Reduction reduction() {
        return dropped.snapshot();
    }

    /** Every mutant this engine can seed into the class, in deterministic bytecode order. */
    public List<Mutant> discover(String moduleId, byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        MutationContext ctx = MutationContext.collecting(moduleId, binaryName(reader));
        reader.accept(new MutatingClassVisitor(null, ctx, mutators, false), ClassReader.EXPAND_FRAMES);
        return filter(ctx.collected(), classBytes);
    }

    /**
     * Drops filtered mutants after collection rather than during it, so ordinals are assigned
     * identically in both phases and {@link #apply} can still find any key discovery returned.
     */
    private List<Mutant> filter(List<Mutant> discovered, byte[] classBytes) {
        // Order matters: cheap structural filters first, then the expensive one that has to
        // generate bytecode for every surviving mutant.
        List<Mutant> kept = filters.loopCounters()
                ? withoutLoopCounters(discovered, classBytes)
                : discovered;
        if (filters.kotlinJunk()) {
            kept = withoutKotlinJunk(kept, classBytes);
        }
        if (filters.arid()) {
            kept = withoutAridCode(kept, classBytes);
        }
        if (filters.onePerLine()) {
            kept = onePerLine(kept);
        }
        if (filters.equivalence()) {
            EquivalenceFilter.Result result = EquivalenceFilter.apply(this, classBytes, kept);
            dropped.equivalent += result.equivalent().size();
            dropped.duplicate += result.duplicates().size();
            kept = result.kept();
        }
        return kept;
    }

    private List<Mutant> withoutKotlinJunk(List<Mutant> discovered, byte[] classBytes) {
        Set<KotlinFilter.Position> positions = KotlinFilter.junkPositions(classBytes);
        return without(discovered, m -> KotlinFilter.drops(positions, m),
                positions.isEmpty(), () -> dropped.kotlinJunk++);
    }

    private List<Mutant> withoutAridCode(List<Mutant> discovered, byte[] classBytes) {
        Set<AridFilter.Position> positions = AridFilter.aridPositions(classBytes);
        return without(discovered, m -> AridFilter.drops(positions, m),
                positions.isEmpty(), () -> dropped.arid++);
    }

    /**
     * Drops the mutants a position-based filter rejects, counting them as it goes.
     *
     * @param nothingToDrop the filter found no positions, so the list is returned untouched
     *                      rather than copied -- the common case, since most classes have none
     */
    private static List<Mutant> without(List<Mutant> discovered, Predicate<Mutant> drops,
                                        boolean nothingToDrop, Runnable count) {
        if (nothingToDrop) {
            return discovered;
        }
        List<Mutant> kept = new ArrayList<>(discovered.size());
        for (Mutant m : discovered) {
            if (drops.test(m)) {
                count.run();
            } else {
                kept.add(m);
            }
        }
        return kept;
    }

    /**
     * At most one mutant per source line.
     *
     * <p>Google's choice, and a defensible one: a reviewer reading a line with six mutants on it
     * learns about as much as from one, and the other five cost a test run each. The survivor is
     * the first in bytecode order, which is deterministic and therefore reproducible.
     */
    private List<Mutant> onePerLine(List<Mutant> discovered) {
        Set<String> seen = new LinkedHashSet<>();
        List<Mutant> kept = new ArrayList<>();
        for (Mutant m : discovered) {
            String line = m.key().className() + "#" + m.key().methodName()
                    + m.key().descriptor() + ":" + m.key().line();
            if (seen.add(line)) {
                kept.add(m);
            } else {
                dropped.onePerLine++;
            }
        }
        return kept;
    }

    private List<Mutant> withoutLoopCounters(List<Mutant> discovered, byte[] classBytes) {
        Set<LoopCounterFilter.Position> suppressed = LoopCounterFilter.loopCounterPositions(classBytes);
        if (suppressed.isEmpty()) {
            return discovered;
        }
        List<Mutant> kept = new ArrayList<>(discovered.size());
        for (Mutant m : discovered) {
            boolean isLoopCounter = m.key().mutator().equals(IncrementsMutator.ID)
                    && suppressed.contains(new LoopCounterFilter.Position(
                            m.key().methodName(), m.key().descriptor(), m.key().line(), m.key().ordinal()));
            if (!isLoopCounter) {
                kept.add(m);
            }
        }
        return kept;
    }

    /**
     * Bytecode for one mutant without the runaway-loop guard.
     *
     * <p>Used for equivalence comparison, where the guard would be noise: it is identical in
     * every mutant of a method and says nothing about whether two mutants are the same program.
     */
    byte[] applyWithoutGuards(byte[] classBytes, MutantKey key) {
        return apply(classBytes, key, false);
    }

    /**
     * Bytecode for the class with exactly one mutant applied.
     *
     * @throws IllegalStateException if the mutant could not be seeded, which means the key
     *                               does not match this bytecode — a cache or staleness bug
     *                               rather than a user error
     */
    public byte[] apply(byte[] classBytes, MutantKey key) {
        return apply(classBytes, key, true);
    }

    private byte[] apply(byte[] classBytes, MutantKey key, boolean guardLoops) {
        ClassReader reader = new ClassReader(classBytes);
        // COMPUTE_MAXS is sufficient: no mutator alters control flow, so the frames recorded
        // in the original class remain valid and only peak stack depth can change.
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        MutationContext ctx = MutationContext.applying(binaryName(reader), key);
        // Only the mutator owning this key is in the chain, so no mutator can observe
        // bytecode another has already changed. That is what keeps ordinals stable between
        // discovery and application.
        List<Mutator> only = List.of(Mutators.byId(key.mutator()));
        reader.accept(new MutatingClassVisitor(writer, ctx, only, guardLoops), ClassReader.EXPAND_FRAMES);
        if (!ctx.applied()) {
            throw new IllegalStateException("mutant " + key.asString()
                    + " does not match the supplied bytecode for " + key.className()
                    + "; the class was probably recompiled since discovery");
        }
        return writer.toByteArray();
    }

    private static String binaryName(ClassReader reader) {
        return reader.getClassName().replace('/', '.');
    }

    /** Applies the mutator chain to every method worth mutating. */
    static final class MutatingClassVisitor extends ClassVisitor {

        private final MutationContext ctx;
        private final List<Mutator> mutators;
        private final boolean guardLoops;

        MutatingClassVisitor(ClassVisitor next, MutationContext ctx, List<Mutator> mutators,
                             boolean guardLoops) {
            super(Opcodes.ASM9, next);
            this.ctx = ctx;
            this.mutators = mutators;
            this.guardLoops = guardLoops;
        }

        @Override
        public void visitSource(String source, String debug) {
            ctx.sourceFile(source);
            ctx.sourceMap(SourceMap.parse(debug));
            super.visitSource(source, debug);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor writer = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (!MethodFilter.shouldMutate(access, name)) {
                return writer;
            }
            ctx.enterMethod(name, descriptor);
            // Guarding only mutated classes is enough: a mutant can only make a loop run away by
            // changing code in the class it was seeded into.
            MethodVisitor chain = guardLoops ? new BackEdgeInstrumenter(writer) : writer;
            // Built inside out, so the line tracker ends up outermost and every mutator sees
            // the current line before it decides anything.
            for (int i = mutators.size() - 1; i >= 0; i--) {
                chain = mutators.get(i).visit(ctx, chain);
            }
            return new LineTrackingMethodVisitor(ctx, chain);
        }
    }

    /** Keeps {@link MutationContext#line()} current for the mutators downstream. */
    static final class LineTrackingMethodVisitor extends MethodVisitor {

        private final MutationContext ctx;

        LineTrackingMethodVisitor(MutationContext ctx, MethodVisitor next) {
            super(Opcodes.ASM9, next);
            this.ctx = ctx;
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            ctx.line(line);
            super.visitLineNumber(line, start);
        }
    }
}
