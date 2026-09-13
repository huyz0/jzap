package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.core.mutator.IncrementsMutator;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Identifies increments that drive a loop, so mutants on them can be suppressed.
 *
 * <p>Negating a loop counter rarely tells you anything. It either hangs the test or crashes it
 * immediately, so the mutant dies for a reason unrelated to what the test actually checks. On
 * the generated bench fixture, 79 of 80 such mutants were killed, and the {@code accumulate}
 * ones ran to integer wraparound — roughly two billion iterations each — before dying. That is
 * a large slice of the run time in exchange for almost no information.
 *
 * <p>PIT filters the same case by default. That was established by running PIT's INCREMENTS
 * mutator alone over a probe class with a {@code for} loop, a {@code while} loop, an indexed
 * loop and a standalone {@code value++}: PIT produced a mutant only for the standalone one.
 *
 * <p>The filter is on by default and can be switched off, because "this mutant is not worth
 * seeding" is a judgement rather than a fact, and a user analysing loop-heavy numeric code may
 * disagree.
 */
final class LoopCounterFilter {

    public static final String ID = "LOOP_COUNTER";

    /**
     * Identity of a filtered mutant, matching how {@link MutationContext} assigns ordinals.
     *
     * <p>"Matching" is a real obligation, not a note. The ordinal is recomputed here from the
     * bytecode, so this is a second implementation of the rule in
     * {@link MutationContext#register}: per method, per line, counting only the increments that
     * actually seed a mutant. If the two disagree by one, this filter suppresses a different
     * mutant than the one it identified.
     */
    record Position(String methodName, String descriptor, int line, int ordinal) {
    }

    private LoopCounterFilter() {
    }

    /** Positions of INCREMENTS mutants that mutate a loop counter. */
    static Set<Position> loopCounterPositions(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_FRAMES);

        Set<Position> filtered = new HashSet<>();
        for (MethodNode method : node.methods) {
            if (method.instructions == null || method.instructions.size() == 0) {
                continue;
            }
            filtered.addAll(analyse(method));
        }
        return filtered;
    }

    private static Set<Position> analyse(MethodNode method) {
        InsnList instructions = method.instructions;
        Map<AbstractInsnNode, Integer> indexOf = new HashMap<>();
        List<AbstractInsnNode> ordered = new ArrayList<>();
        for (AbstractInsnNode insn = instructions.getFirst(); insn != null; insn = insn.getNext()) {
            indexOf.put(insn, ordered.size());
            ordered.add(insn);
        }

        // A backward jump is a loop's back edge; the instructions between its target and itself
        // are the loop body.
        List<int[]> loops = new ArrayList<>();
        for (AbstractInsnNode insn : ordered) {
            if (insn instanceof JumpInsnNode jump) {
                Integer target = indexOf.get(jump.label);
                Integer here = indexOf.get(insn);
                if (target != null && here != null && target < here) {
                    loops.add(new int[]{target, here});
                }
            }
        }

        Set<Position> filtered = new HashSet<>();
        Map<Integer, Integer> ordinalPerLine = new HashMap<>();
        int line = 0;
        for (int i = 0; i < ordered.size(); i++) {
            AbstractInsnNode insn = ordered.get(i);
            if (insn instanceof LineNumberNode lineNode) {
                line = lineNode.line;
                continue;
            }
            if (!(insn instanceof IincInsnNode iinc)) {
                continue;
            }
            if (!IncrementsMutator.canNegate(iinc.incr)) {
                // No mutant is seeded here, so no ordinal is taken here either. Counting it would
                // shift every later ordinal on this line past the mutant it belongs to, and this
                // filter would then suppress an ordinal nothing has -- leaving the loop counter
                // mutant in, which is the whole point of the filter. javac can put three
                // increments on one line: a "for" update list does exactly that.
                continue;
            }
            int ordinal = ordinalPerLine.merge(line, 0, (a, b) -> a + 1);
            if (drivesALoop(ordered, indexOf, loops, i, iinc.var)) {
                filtered.add(new Position(method.name, method.desc, line, ordinal));
            }
        }
        return filtered;
    }

    /**
     * True when the increment sits inside a loop whose exit test reads the same variable.
     *
     * <p>Requiring the variable to appear in the condition is what keeps an ordinary
     * {@code count++} inside a loop body mutable: only the variable the loop turns on is
     * suppressed.
     */
    private static boolean drivesALoop(List<AbstractInsnNode> ordered,
                                       Map<AbstractInsnNode, Integer> indexOf,
                                       List<int[]> loops, int iincIndex, int variable) {
        for (int[] loop : loops) {
            if (iincIndex < loop[0] || iincIndex > loop[1]) {
                continue;
            }
            if (conditionReads(ordered, loop, variable)) {
                return true;
            }
        }
        return false;
    }

    private static boolean conditionReads(List<AbstractInsnNode> ordered, int[] loop, int variable) {
        // The comparison may also sit just past the back edge, which is where javac puts a
        // for-loop's condition, so the scan runs a little beyond the loop body.
        int end = Math.min(ordered.size() - 1, loop[1] + 8);
        for (int i = loop[0]; i <= end; i++) {
            if (!(ordered.get(i) instanceof VarInsnNode load)
                    || load.getOpcode() != Opcodes.ILOAD || load.var != variable) {
                continue;
            }
            for (int j = i + 1; j <= Math.min(end, i + 3); j++) {
                if (ordered.get(j) instanceof JumpInsnNode jump && isConditional(jump.getOpcode())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isConditional(int opcode) {
        return switch (opcode) {
            case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE,
                 Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
                 Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE -> true;
            default -> false;
        };
    }
}
