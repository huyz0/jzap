package io.github.huyz0.jzap.core.mutator;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.Map;

/**
 * Makes a reference-returning method return an empty value of its own type. PIT's
 * EMPTY_RETURNS.
 *
 * <p>Restricted to types with an unambiguous empty form. Returning null from an arbitrary
 * method is a different and much noisier mutation (PIT's NULL_RETURNS, not part of its
 * default set), so it is not done here.
 */
public final class EmptyReturnsMutator extends ReturnValueMutator {

    public static final String ID = "EMPTY_RETURNS";

    private record Empty(String owner, String method, String descriptor, boolean isField) {
    }

    private static final Map<String, Empty> EMPTIES = Map.ofEntries(
            Map.entry("java/util/Optional", new Empty("java/util/Optional", "empty", "()Ljava/util/Optional;", false)),
            Map.entry("java/util/OptionalInt", new Empty("java/util/OptionalInt", "empty", "()Ljava/util/OptionalInt;", false)),
            Map.entry("java/util/OptionalLong", new Empty("java/util/OptionalLong", "empty", "()Ljava/util/OptionalLong;", false)),
            Map.entry("java/util/OptionalDouble", new Empty("java/util/OptionalDouble", "empty", "()Ljava/util/OptionalDouble;", false)),
            Map.entry("java/util/List", new Empty("java/util/List", "of", "()Ljava/util/List;", false)),
            Map.entry("java/util/Set", new Empty("java/util/Set", "of", "()Ljava/util/Set;", false)),
            Map.entry("java/util/Map", new Empty("java/util/Map", "of", "()Ljava/util/Map;", false)),
            Map.entry("java/util/Collection", new Empty("java/util/List", "of", "()Ljava/util/List;", false)),
            Map.entry("java/util/stream/Stream", new Empty("java/util/stream/Stream", "empty", "()Ljava/util/stream/Stream;", false)),
            Map.entry("java/lang/Boolean", new Empty("java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;", true)),
            Map.entry("java/lang/Integer", new Empty("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)),
            Map.entry("java/lang/Long", new Empty("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false)),
            Map.entry("java/lang/Short", new Empty("java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false)),
            Map.entry("java/lang/Byte", new Empty("java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false)),
            Map.entry("java/lang/Character", new Empty("java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false)),
            Map.entry("java/lang/Float", new Empty("java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false)),
            Map.entry("java/lang/Double", new Empty("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false)));

    @Override
    public String id() {
        return ID;
    }

    @Override
    protected boolean applies(Type returnType) {
        if (returnType.getSort() != Type.OBJECT) {
            return false;
        }
        return returnType.getInternalName().equals("java/lang/String")
                || EMPTIES.containsKey(returnType.getInternalName());
    }

    @Override
    protected String description(Type returnType) {
        if (returnType.getInternalName().equals("java/lang/String")) {
            return "replaced String return with \"\"";
        }
        return "replaced return value with an empty " + returnType.getClassName();
    }

    @Override
    protected boolean wouldBeNoOp(int opcode, Object constant) {
        // The only empty value javac can have just pushed as a constant is the empty string.
        return opcode == Opcodes.LDC && "".equals(constant);
    }

    @Override
    protected void pushReplacement(MethodVisitor mv, Type returnType) {
        String internal = returnType.getInternalName();
        if (internal.equals("java/lang/String")) {
            mv.visitLdcInsn("");
            return;
        }
        Empty empty = EMPTIES.get(internal);
        if (empty.isField()) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, empty.owner(), empty.method(), empty.descriptor());
            return;
        }
        // Boxed numerics need a zero argument before the valueOf call.
        switch (internal) {
            case "java/lang/Integer", "java/lang/Short", "java/lang/Byte", "java/lang/Character" ->
                    mv.visitInsn(Opcodes.ICONST_0);
            case "java/lang/Long" -> mv.visitInsn(Opcodes.LCONST_0);
            case "java/lang/Float" -> mv.visitInsn(Opcodes.FCONST_0);
            case "java/lang/Double" -> mv.visitInsn(Opcodes.DCONST_0);
            default -> {
                // no-arg factory
            }
        }
        boolean isInterface = switch (internal) {
            case "java/util/List", "java/util/Set", "java/util/Map", "java/util/Collection",
                 "java/util/stream/Stream" -> true;
            default -> false;
        };
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, empty.owner(), empty.method(), empty.descriptor(), isInterface);
    }
}
