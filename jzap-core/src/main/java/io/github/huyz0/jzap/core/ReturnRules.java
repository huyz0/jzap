package io.github.huyz0.jzap.core;

import io.github.huyz0.jzap.core.mutator.EmptyReturnsMutator;
import io.github.huyz0.jzap.core.mutator.FalseReturnsMutator;
import io.github.huyz0.jzap.core.mutator.PrimitiveReturnsMutator;
import io.github.huyz0.jzap.core.mutator.TrueReturnsMutator;
import io.github.huyz0.jzap.model.MutantKey;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Emits the dispatch call that stands in for a mutated return value.
 *
 * <p>Applicability and the no-op rule come from the mutators themselves rather than from a copy
 * here. A mutator that declines to seed a mutant also declines to take an ordinal, so a
 * disagreement about either would shift every later ordinal in the method and run the wrong
 * mutant against the right key.
 */
final class ReturnRules {

    private ReturnRules() {
    }

    /**
     * @return true when a dispatch call replaced the plain return value
     */
    static boolean emitSchemata(MutationContext ctx, MethodVisitor mv, Type returnType,
                                int lastOpcode, Object lastConstant) {
        if (TrueReturnsMutator.appliesTo(returnType)) {
            // Both boolean mutators act on the same return, so one call carries both ids.
            int falseId = FalseReturnsMutator.isNoOp(lastOpcode)
                    ? -1
                    : index(ctx, FalseReturnsMutator.ID);
            int trueId = TrueReturnsMutator.isNoOp(lastOpcode)
                    ? -1
                    : index(ctx, TrueReturnsMutator.ID);
            if (trueId < 0 && falseId < 0) {
                return false;
            }
            Bytecode.pushInt(mv, trueId);
            Bytecode.pushInt(mv, falseId);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, SchemataTransformer.OPS,
                    "booleanReturn", "(ZII)Z", false);
            return true;
        }

        if (EmptyReturnsMutator.appliesTo(returnType)) {
            if (EmptyReturnsMutator.isNoOp(lastOpcode, lastConstant)) {
                return false;
            }
            int id = index(ctx, EmptyReturnsMutator.ID);
            if (id < 0) {
                return false;
            }
            EmptyReturnsMutator.pushEmpty(mv, returnType);
            Bytecode.pushInt(mv, id);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, SchemataTransformer.OPS, "emptyReturn",
                    "(Ljava/lang/Object;Ljava/lang/Object;I)Ljava/lang/Object;", false);
            mv.visitTypeInsn(Opcodes.CHECKCAST, returnType.getInternalName());
            return true;
        }

        if (PrimitiveReturnsMutator.appliesTo(returnType)) {
            if (PrimitiveReturnsMutator.isNoOp(lastOpcode)) {
                return false;
            }
            int id = index(ctx, PrimitiveReturnsMutator.ID);
            if (id < 0) {
                return false;
            }
            String name = switch (returnType.getSort()) {
                case Type.LONG -> "longReturn";
                case Type.FLOAT -> "floatReturn";
                case Type.DOUBLE -> "doubleReturn";
                default -> "intReturn";
            };
            String primitive = switch (returnType.getSort()) {
                case Type.LONG -> "J";
                case Type.FLOAT -> "F";
                case Type.DOUBLE -> "D";
                default -> "I";
            };
            Bytecode.pushInt(mv, id);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, SchemataTransformer.OPS, name,
                    "(" + primitive + "I)" + primitive, false);
            return true;
        }
        return false;
    }

    private static int index(MutationContext ctx, String mutatorId) {
        MutantKey key = ctx.register(mutatorId);
        return ctx.schemataIndex(key);
    }
}
