package io.github.huyz0.jzap.core.mutator;

import org.objectweb.asm.Opcodes;

/**
 * The instruction that produced the value about to be returned, when it is simple enough to name.
 *
 * <p>The return mutators use this to decline a mutation that would replace a value with the value
 * already there. Such a mutant is the original program: nothing can distinguish it, so it survives
 * every run forever and sits in the report asking for a test that already exists.
 *
 * <p>One implementation, shared, rather than one per visitor. Two passes consult it -- the mutating
 * pass and the schemata transformer -- and a mutator that declines also declines to take an
 * ordinal, so if the two tracked the instruction stream differently their ordinals would diverge
 * and a mutant would be seeded under another mutant's key. Keeping the state here makes that
 * impossible instead of merely tested for.
 *
 * <p>What it remembers is deliberately shallow: a constant push, a static call, a static field
 * read, and the one opcode before a call so that a boxed zero -- {@code ICONST_0} followed by
 * {@code Integer.valueOf} -- can be recognised. Anything else is a {@link #forget()}, because the
 * value then arrives from somewhere this cannot reason about.
 */
public final class PrecedingValue {

    private int opcode = -1;
    private Object constant;
    private String member;
    private int opcodeBeforeMember = -1;

    /** The value no longer has a single source this can name. */
    public void forget() {
        opcode = -1;
        constant = null;
        member = null;
        opcodeBeforeMember = -1;
    }

    /**
     * Records a plain instruction.
     *
     * <p>An instruction of the same opcode keeps the constant that came with it, so that the
     * instruction being inspected does not erase the record of itself.
     */
    public void insn(int nextOpcode) {
        Object previousConstant = opcode == nextOpcode ? constant : null;
        opcode = nextOpcode;
        constant = previousConstant;
        member = null;
        opcodeBeforeMember = -1;
    }

    public void constantPush(int pushOpcode, Object pushed) {
        opcode = pushOpcode;
        constant = pushed;
        member = null;
        opcodeBeforeMember = -1;
    }

    /** Records a call, keeping the opcode before it so a boxing call can be read with its input. */
    public void call(int callOpcode, String owner, String name, String descriptor) {
        int before = opcode;
        opcode = callOpcode;
        constant = null;
        member = callOpcode == Opcodes.INVOKESTATIC ? key(owner, name, descriptor) : null;
        opcodeBeforeMember = member == null ? -1 : before;
    }

    public void field(int fieldOpcode, String owner, String name, String descriptor) {
        opcode = fieldOpcode;
        constant = null;
        member = fieldOpcode == Opcodes.GETSTATIC ? key(owner, name, descriptor) : null;
        opcodeBeforeMember = -1;
    }

    /** {@code java/util/List.of:()Ljava/util/List;} */
    static String key(String owner, String name, String descriptor) {
        return owner + "." + name + ":" + descriptor;
    }

    public int opcode() {
        return opcode;
    }

    public Object constant() {
        return constant;
    }

    /** The static call or field read that produced the value, or null if it was neither. */
    public String member() {
        return member;
    }

    /** The opcode before {@link #member()}, when the member was a static call. */
    public int opcodeBeforeMember() {
        return opcodeBeforeMember;
    }
}
