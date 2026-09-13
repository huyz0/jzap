package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.model.Mutant;
import io.github.huyz0.jzap.model.MutantKey;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
    private final boolean filterLoopCounters;

    public MutationEngine(List<Mutator> mutators) {
        this(mutators, true);
    }

    /**
     * @param filterLoopCounters suppress INCREMENTS mutants on loop counters. On by default;
     *                           see {@link LoopCounterFilter} for the measurements behind that.
     */
    public MutationEngine(List<Mutator> mutators, boolean filterLoopCounters) {
        this.mutators = List.copyOf(mutators);
        this.filterLoopCounters = filterLoopCounters;
    }

    public static MutationEngine withDefaults() {
        return new MutationEngine(Mutators.defaults());
    }

    /** Every mutant this engine can seed into the class, in deterministic bytecode order. */
    public List<Mutant> discover(String moduleId, byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        MutationContext ctx = MutationContext.collecting(moduleId, binaryName(reader));
        reader.accept(new MutatingClassVisitor(null, ctx, mutators, false), ClassReader.EXPAND_FRAMES);
        return filter(ctx.collected(), classBytes);
    }

    /**
     * Bytecode for the class with exactly one mutant applied.
     *
     * @throws IllegalStateException if the mutant could not be seeded, which means the key
     *                               does not match this bytecode — a cache or staleness bug
     *                               rather than a user error
     */
    /**
     * Drops filtered mutants after collection rather than during it, so ordinals are assigned
     * identically in both phases and {@link #apply} can still find any key discovery returned.
     */
    private List<Mutant> filter(List<Mutant> discovered, byte[] classBytes) {
        if (!filterLoopCounters) {
            return discovered;
        }
        Set<LoopCounterFilter.Position> suppressed = LoopCounterFilter.loopCounterPositions(classBytes);
        if (suppressed.isEmpty()) {
            return discovered;
        }
        List<Mutant> kept = new ArrayList<>(discovered.size());
        for (Mutant m : discovered) {
            boolean isLoopCounter = m.key().mutator().equals(io.github.huyz0.jzap.core.mutator.IncrementsMutator.ID)
                    && suppressed.contains(new LoopCounterFilter.Position(
                            m.key().methodName(), m.key().descriptor(), m.key().line(), m.key().ordinal()));
            if (!isLoopCounter) {
                kept.add(m);
            }
        }
        return kept;
    }

    public byte[] apply(byte[] classBytes, MutantKey key) {
        ClassReader reader = new ClassReader(classBytes);
        // COMPUTE_MAXS is sufficient: no mutator alters control flow, so the frames recorded
        // in the original class remain valid and only peak stack depth can change.
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        MutationContext ctx = MutationContext.applying(binaryName(reader), key);
        // Only the mutator owning this key is in the chain, so no mutator can observe
        // bytecode another has already changed. That is what keeps ordinals stable between
        // discovery and application.
        List<Mutator> only = List.of(Mutators.byId(key.mutator()));
        reader.accept(new MutatingClassVisitor(writer, ctx, only, true), ClassReader.EXPAND_FRAMES);
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
        public void visitLineNumber(int line, org.objectweb.asm.Label start) {
            ctx.line(line);
            super.visitLineNumber(line, start);
        }
    }
}
