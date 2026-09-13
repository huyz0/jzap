package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.core.mutator.VoidMethodCallsMutator;
import io.github.huyz0.jzap.model.Mutant;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Suppresses mutants in code that reports rather than decides.
 *
 * <p>Google's arid-node idea, narrowed to what bytecode can tell us honestly. Removing a log
 * statement is not a fault any reasonable test suite is expected to catch, so a mutant that does
 * it survives forever and teaches nobody anything.
 *
 * <p>Two rules, both driven by the glob list in {@code arid-rules.txt}:
 *
 * <ol>
 *   <li>A VOID_METHOD_CALLS mutant whose target matches is dropped.
 *   <li>Every mutant in a void method is dropped when all of that method's calls are arid. That
 *       is the shape of a method whose whole job is to report.
 * </ol>
 *
 * <p>Rule 2 is deliberately conservative: one non-arid call anywhere in the method and the whole
 * method is left alone. Over-suppression is the failure mode that matters here, because a
 * suppressed mutant is invisible — it does not appear in the report as anything, not even as
 * something skipped.
 *
 * <p>Off by default. PIT does not do this, so every dropped mutant would become a difference
 * against the correctness oracle, burying the ones worth reading.
 */
public final class AridFilter {

    public static final String ID = "ARID";

    private static final List<String> RULES = loadRules();

    private AridFilter() {
    }

    private static List<String> loadRules() {
        List<String> rules = new ArrayList<>();
        try (InputStream in = AridFilter.class.getResourceAsStream("arid-rules.txt")) {
            if (in == null) {
                return List.of();
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    rules.add(trimmed);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the arid rules", e);
        }
        return List.copyOf(rules);
    }

    /** The rules in force, so a report can say what was applied. */
    public static List<String> rules() {
        return RULES;
    }

    static boolean isArid(String owner, String name) {
        String target = owner + "#" + name;
        for (String rule : RULES) {
            if (Globs.matches(rule, target)) {
                return true;
            }
        }
        return false;
    }

    /** Mutants this filter drops, identified the same way {@link MutationContext} keys them. */
    static Set<Position> aridPositions(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_FRAMES);

        Set<Position> positions = new HashSet<>();
        for (MethodNode method : node.methods) {
            if (method.instructions == null || method.instructions.size() == 0) {
                continue;
            }
            if (methodIsEntirelyArid(method)) {
                positions.add(new Position(method.name, method.desc, -1, -1));
                continue;
            }
            collectAridCalls(method, positions);
        }
        return positions;
    }

    /** A void method whose calls are all arid, and which makes at least one. */
    private static boolean methodIsEntirelyArid(MethodNode method) {
        if (Type.getReturnType(method.desc).getSort() != Type.VOID) {
            return false;
        }
        boolean sawAridCall = false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null;
             insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call)) {
                continue;
            }
            if ("<init>".equals(call.name)) {
                continue;   // string builders and the like, used to assemble a message
            }
            if (isArid(call.owner, call.name)) {
                sawAridCall = true;
            } else if (!isMessageAssembly(call)) {
                return false;
            }
        }
        return sawAridCall;
    }

    /** Calls that only build the text being reported, not decisions the method makes. */
    private static boolean isMessageAssembly(MethodInsnNode call) {
        return call.owner.equals("java/lang/StringBuilder")
                || call.owner.equals("java/lang/String")
                || call.owner.equals("java/lang/invoke/StringConcatFactory");
    }

    private static void collectAridCalls(MethodNode method, Set<Position> positions) {
        Map<Integer, Integer> ordinalPerLine = new HashMap<>();
        int line = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null;
             insn = insn.getNext()) {
            if (insn instanceof LineNumberNode lineNode) {
                line = lineNode.line;
                continue;
            }
            if (!(insn instanceof MethodInsnNode call)) {
                continue;
            }
            boolean isVoid = Type.getReturnType(call.desc).getSort() == Type.VOID;
            if (!isVoid || "<init>".equals(call.name)) {
                continue;   // matches what VoidMethodCallsMutator seeds
            }
            int ordinal = ordinalPerLine.merge(line, 0, (a, b) -> a + 1);
            if (isArid(call.owner, call.name)) {
                positions.add(new Position(method.name, method.desc, line, ordinal));
            }
        }
    }

    /** A dropped mutant, or a whole method when line and ordinal are -1. */
    record Position(String methodName, String descriptor, int line, int ordinal) {
    }

    /** Whether this mutant is dropped by the positions computed above. */
    static boolean drops(Set<Position> positions, Mutant mutant) {
        if (positions.contains(new Position(
                mutant.key().methodName(), mutant.key().descriptor(), -1, -1))) {
            return true;
        }
        return mutant.key().mutator().equals(VoidMethodCallsMutator.ID)
                && positions.contains(new Position(mutant.key().methodName(),
                        mutant.key().descriptor(), mutant.key().line(), mutant.key().ordinal()));
    }
}
