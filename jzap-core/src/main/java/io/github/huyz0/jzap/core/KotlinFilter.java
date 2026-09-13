package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.core.mutator.ConditionalsBoundaryMutator;
import io.github.huyz0.jzap.core.mutator.NegateConditionalsMutator;
import io.github.huyz0.jzap.core.mutator.VoidMethodCallsMutator;
import io.github.huyz0.jzap.model.Mutant;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Suppresses mutants in code the Kotlin compiler wrote rather than the developer.
 *
 * <p>Kotlin compiles to ordinary bytecode, which is why mutating it works at all, and it emits a
 * great deal of scaffolding that has no counterpart in the source. A mutant in that scaffolding
 * is junk in the precise sense that matters: no mistake a developer could make would produce it,
 * so a reader cannot act on it, and it will sit in the report forever.
 *
 * <p>Every rule here is gated on the class carrying {@code kotlin.Metadata}. Java classes are
 * untouched, which keeps the PIT comparison on the Java fixtures exactly as it was.
 *
 * <p>The rules, each derived from reading the bytecode kotlinc actually emits rather than from
 * what it might emit:
 *
 * <ul>
 *   <li><b>Generated property accessors.</b> A getter whose entire body is a field read, or a
 *       setter whose entire body is a field write. {@code val id: String} has no body to get
 *       wrong.
 *   <li><b>Data class members.</b> {@code componentN}, {@code copy}, {@code copy$default}, and
 *       {@code equals}/{@code hashCode}/{@code toString} on a class that has {@code componentN}
 *       methods. Most of these already carry no line numbers and are dropped for that reason;
 *       naming them makes it deliberate rather than lucky.
 *   <li><b>Null-check intrinsics.</b> Removing a {@code kotlin.jvm.internal.Intrinsics} call is
 *       not a fault a developer can commit. These sit before the first line-number entry, so
 *       they too were already being dropped by accident; a rule stops that from regressing
 *       silently.
 *   <li><b>Iterator loop scaffolding.</b> The conditional driving {@code for (x in xs)} is a
 *       {@code hasNext} check the compiler wrote. Negating it makes the loop skip everything or
 *       never end, which is the same uninformative outcome as mutating a loop counter in Java --
 *       and the Java loop-counter filter cannot see it, because a Kotlin for-each loop has no
 *       increment.
 * </ul>
 */
final class KotlinFilter {

    public static final String ID = "KOTLIN";

    private static final String METADATA = "Lkotlin/Metadata;";
    private static final String INTRINSICS = "kotlin/jvm/internal/Intrinsics";

    private KotlinFilter() {
    }

    /** Whether this class was produced by the Kotlin compiler. */
    static boolean isKotlin(ClassNode node) {
        List<AnnotationNode> annotations = node.visibleAnnotations;
        if (annotations == null) {
            return false;
        }
        return annotations.stream().anyMatch(a -> METADATA.equals(a.desc));
    }

    public static boolean isKotlin(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        return isKotlin(node);
    }

    /** A suppressed mutant. A null mutator with line -1 means the whole method. */
    record Position(String methodName, String descriptor, String mutator, int line, int ordinal) {

        static Position wholeMethod(String methodName, String descriptor) {
            return new Position(methodName, descriptor, null, -1, -1);
        }
    }

    static Set<Position> junkPositions(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_FRAMES);
        if (!isKotlin(node)) {
            return Set.of();
        }

        boolean looksLikeDataClass = node.methods.stream()
                .anyMatch(m -> m.name.matches("component\\d+"));

        Set<Position> positions = new HashSet<>();
        for (MethodNode method : node.methods) {
            if (method.instructions == null || method.instructions.size() == 0) {
                continue;
            }
            if (isGeneratedMember(method, looksLikeDataClass) || isPropertyAccessor(method)) {
                positions.add(Position.wholeMethod(method.name, method.desc));
                continue;
            }
            collectIntrinsicCalls(method, positions);
            collectIteratorConditionals(method, positions);
        }
        return positions;
    }

    private static boolean isGeneratedMember(MethodNode method, boolean looksLikeDataClass) {
        if (method.name.matches("component\\d+") || method.name.equals("copy")
                || method.name.equals("copy$default")) {
            return true;
        }
        return looksLikeDataClass
                && (method.name.equals("equals") || method.name.equals("hashCode")
                || method.name.equals("toString"));
    }

    /** A body that is nothing but a field read, or nothing but a field write. */
    private static boolean isPropertyAccessor(MethodNode method) {
        List<AbstractInsnNode> body = realInstructions(method);
        if (body.size() == 3) {
            return body.get(0) instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD
                    && load.var == 0
                    && body.get(1) instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && isReturn(body.get(2).getOpcode());
        }
        if (body.size() == 4) {
            return body.get(0) instanceof VarInsnNode receiver
                    && receiver.getOpcode() == Opcodes.ALOAD && receiver.var == 0
                    && body.get(1) instanceof VarInsnNode value && value.var == 1
                    && body.get(2) instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && body.get(3).getOpcode() == Opcodes.RETURN;
        }
        return false;
    }

    private static boolean isReturn(int opcode) {
        return opcode >= Opcodes.IRETURN && opcode <= Opcodes.ARETURN;
    }

    private static List<AbstractInsnNode> realInstructions(MethodNode method) {
        List<AbstractInsnNode> body = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null;
             insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) {
                body.add(insn);
            }
        }
        return body;
    }

    private static void collectIntrinsicCalls(MethodNode method, Set<Position> positions) {
        Map<Integer, Integer> ordinals = new HashMap<>();
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
            int ordinal = ordinals.merge(line, 0, (a, b) -> a + 1);
            if (call.owner.equals(INTRINSICS)) {
                positions.add(new Position(method.name, method.desc,
                        VoidMethodCallsMutator.ID, line, ordinal));
            }
        }
    }

    /** The conditional that drives a for-each loop is a compiler-written {@code hasNext} check. */
    private static void collectIteratorConditionals(MethodNode method, Set<Position> positions) {
        Map<String, Integer> ordinals = new HashMap<>();
        int line = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null;
             insn = insn.getNext()) {
            if (insn instanceof LineNumberNode lineNode) {
                line = lineNode.line;
                continue;
            }
            if (!(insn instanceof JumpInsnNode jump)) {
                continue;
            }
            boolean negatable = NegateConditionalsMutator.handles(jump.getOpcode());
            boolean boundary = ConditionalsBoundaryMutator.handles(jump.getOpcode());
            int negateOrdinal = negatable
                    ? ordinals.merge(line + "|" + NegateConditionalsMutator.ID, 0, (a, b) -> a + 1)
                    : -1;
            int boundaryOrdinal = boundary
                    ? ordinals.merge(line + "|" + ConditionalsBoundaryMutator.ID, 0, (a, b) -> a + 1)
                    : -1;
            if (!fedByHasNext(jump)) {
                continue;
            }
            if (negatable) {
                positions.add(new Position(method.name, method.desc,
                        NegateConditionalsMutator.ID, line, negateOrdinal));
            }
            if (boundary) {
                positions.add(new Position(method.name, method.desc,
                        ConditionalsBoundaryMutator.ID, line, boundaryOrdinal));
            }
        }
    }

    private static boolean fedByHasNext(JumpInsnNode jump) {
        AbstractInsnNode previous = jump.getPrevious();
        while (previous != null && previous.getOpcode() < 0) {
            previous = previous.getPrevious();
        }
        return previous instanceof MethodInsnNode call && call.name.equals("hasNext");
    }

    static boolean drops(Set<Position> positions, Mutant mutant) {
        if (positions.contains(Position.wholeMethod(
                mutant.key().methodName(), mutant.key().descriptor()))) {
            return true;
        }
        return positions.contains(new Position(mutant.key().methodName(),
                mutant.key().descriptor(), mutant.key().mutator(),
                mutant.key().line(), mutant.key().ordinal()));
    }
}
