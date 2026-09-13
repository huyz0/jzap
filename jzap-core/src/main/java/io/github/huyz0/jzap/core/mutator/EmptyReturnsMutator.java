package io.github.huyz0.jzap.core.mutator;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.Map;
import java.util.Set;

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

    public static boolean appliesTo(Type returnType) {
        if (returnType.getSort() != Type.OBJECT) {
            return false;
        }
        return returnType.getInternalName().equals("java/lang/String")
                || EMPTIES.containsKey(returnType.getInternalName());
    }

    /**
     * Members that already produce the empty value of a type, keyed by that type.
     *
     * <p>Includes the {@code Collections.empty*} forms as well as the factories this mutator
     * emits. They are not byte-identical to what it emits, but they are the same empty immutable
     * collection, and PIT declines them too -- established by running its EMPTY_RETURNS over a
     * probe of these shapes rather than assumed.
     */
    private static final Map<String, Set<String>> ALREADY_EMPTY = Map.ofEntries(
            Map.entry("java/util/List", Set.of(
                    "java/util/List.of:()Ljava/util/List;",
                    "java/util/Collections.emptyList:()Ljava/util/List;")),
            Map.entry("java/util/Set", Set.of(
                    "java/util/Set.of:()Ljava/util/Set;",
                    "java/util/Collections.emptySet:()Ljava/util/Set;")),
            Map.entry("java/util/Map", Set.of(
                    "java/util/Map.of:()Ljava/util/Map;",
                    "java/util/Collections.emptyMap:()Ljava/util/Map;")),
            Map.entry("java/util/Collection", Set.of(
                    "java/util/List.of:()Ljava/util/List;",
                    "java/util/Collections.emptyList:()Ljava/util/List;")),
            Map.entry("java/util/Optional", Set.of("java/util/Optional.empty:()Ljava/util/Optional;")),
            Map.entry("java/util/OptionalInt", Set.of("java/util/OptionalInt.empty:()Ljava/util/OptionalInt;")),
            Map.entry("java/util/OptionalLong", Set.of("java/util/OptionalLong.empty:()Ljava/util/OptionalLong;")),
            Map.entry("java/util/OptionalDouble", Set.of("java/util/OptionalDouble.empty:()Ljava/util/OptionalDouble;")),
            Map.entry("java/util/stream/Stream", Set.of("java/util/stream/Stream.empty:()Ljava/util/stream/Stream;")),
            Map.entry("java/lang/Boolean", Set.of("java/lang/Boolean.FALSE:Ljava/lang/Boolean;")));

    /** The zero each boxing factory has to have been handed for the box to be an empty value. */
    private static final Map<String, Integer> BOXED_ZERO = Map.of(
            "java/lang/Integer.valueOf:(I)Ljava/lang/Integer;", Opcodes.ICONST_0,
            "java/lang/Short.valueOf:(S)Ljava/lang/Short;", Opcodes.ICONST_0,
            "java/lang/Byte.valueOf:(B)Ljava/lang/Byte;", Opcodes.ICONST_0,
            "java/lang/Character.valueOf:(C)Ljava/lang/Character;", Opcodes.ICONST_0,
            "java/lang/Long.valueOf:(J)Ljava/lang/Long;", Opcodes.LCONST_0,
            "java/lang/Float.valueOf:(F)Ljava/lang/Float;", Opcodes.FCONST_0,
            "java/lang/Double.valueOf:(D)Ljava/lang/Double;", Opcodes.DCONST_0);

    /**
     * Whether the value about to be returned is already the empty one for this type.
     *
     * <p>Three shapes, and all three come out of ordinary source. {@code return ""} is a constant
     * push. {@code return List.of()} and {@code return Boolean.FALSE} are a static call and a
     * static field read -- invisible to a guard that inspects only constants, which is why these
     * used to be seeded and then survive forever. {@code return 0} from a method returning
     * {@code Integer} is a zero followed by a boxing call, so it needs the instruction before the
     * call as well.
     */
    public static boolean isNoOp(Type returnType, PrecedingValue preceding) {
        if (returnType.getInternalName().equals("java/lang/String")) {
            return preceding.opcode() == Opcodes.LDC && "".equals(preceding.constant());
        }
        String member = preceding.member();
        if (member == null) {
            return false;
        }
        if (ALREADY_EMPTY.getOrDefault(returnType.getInternalName(), Set.of()).contains(member)) {
            return true;
        }
        Integer zero = BOXED_ZERO.get(member);
        return zero != null && preceding.opcodeBeforeMember() == zero;
    }

    @Override
    protected boolean applies(Type returnType) {
        return appliesTo(returnType);
    }

    @Override
    protected String description(Type returnType) {
        if (returnType.getInternalName().equals("java/lang/String")) {
            return "replaced String return with \"\"";
        }
        return "replaced return value with an empty " + returnType.getClassName();
    }

    @Override
    protected boolean wouldBeNoOp(Type returnType, PrecedingValue preceding) {
        return isNoOp(returnType, preceding);
    }

    @Override
    protected void pushReplacement(MethodVisitor mv, Type returnType) {
        pushEmpty(mv, returnType);
    }

    /** Pushes the empty value for this reference type. */
    public static void pushEmpty(MethodVisitor mv, Type returnType) {
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
