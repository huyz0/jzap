package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.model.Mutant;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drops mutants that compile to the same bytecode as the original, or as each other.
 *
 * <p>This is Trivial Compiler Equivalence, from Papadakis et al. A mutant whose compiled form is
 * identical to the original cannot be killed by anything, so it survives every run forever and is
 * pure noise in the report. Two mutants with identical compiled forms always share a verdict, so
 * analysing both is wasted work. The published figures are roughly 11% of Java mutants equivalent
 * or duplicated; jzap measures its own rather than assuming that.
 *
 * <p>Off by default. PIT does not do this, so enabling it would make every dropped mutant an
 * inventory difference against the correctness oracle — differences that are real but would bury
 * the ones worth looking at. Turn it on with {@code --dedup}.
 *
 * <p>Normalisation strips debug information and stack map frames before hashing, so two mutants
 * that differ only in line-number tables are recognised as the same program.
 */
final class EquivalenceFilter {

    public static final String ID = "TCE";

    /**
     * @param kept        mutants worth analysing
     * @param equivalent  mutants identical to the original, which can never be killed
     * @param duplicates  mutants identical to another mutant, which would share its verdict
     */
    public record Result(List<Mutant> kept, List<Mutant> equivalent, List<Mutant> duplicates) {

        public int dropped() {
            return equivalent.size() + duplicates.size();
        }
    }

    private EquivalenceFilter() {
    }

    static Result apply(MutationEngine engine, byte[] classBytes, List<Mutant> mutants) {
        String original = normalisedHash(classBytes);
        Map<String, Mutant> seen = new LinkedHashMap<>();
        List<Mutant> kept = new ArrayList<>();
        List<Mutant> equivalent = new ArrayList<>();
        List<Mutant> duplicates = new ArrayList<>();

        for (Mutant mutant : mutants) {
            String hash;
            try {
                hash = normalisedHash(engine.applyWithoutGuards(classBytes, mutant.key()));
            } catch (RuntimeException e) {
                // A mutant that cannot be generated here would fail later anyway; leaving it in
                // lets the normal path report it honestly rather than silently dropping it.
                kept.add(mutant);
                continue;
            }
            if (hash.equals(original)) {
                equivalent.add(mutant);
            } else if (seen.containsKey(hash)) {
                duplicates.add(mutant);
            } else {
                seen.put(hash, mutant);
                kept.add(mutant);
            }
        }
        return new Result(kept, equivalent, duplicates);
    }

    /** Hash of the class with debug information and frames removed. */
    private static String normalisedHash(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(writer, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return Hashes.of(writer.toByteArray());
    }
}
