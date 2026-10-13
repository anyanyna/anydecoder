package com.anydecoder.core.gate;

/**
 * One JVM instruction, reduced to a form that survives recompilation.
 *
 * <p>Operands are resolved, never raw constant-pool indices: two compilations of the same
 * source assign different pool indices and different branch offsets, so comparing those
 * would report every method as different. What is kept is the part that carries meaning --
 * which method is called, which field is touched, which constant is loaded.
 *
 * <p>{@code targets} is where control goes for a branch, as instruction <em>indices</em>. It is
 * deliberately not part of {@link #render()}: offsets and their encodings move between compilers,
 * so putting it in the comparison would reintroduce the noise the rest of this class exists to
 * remove. It is kept separately because deciding whether a difference is benign sometimes needs
 * the shape of the control flow, even though the comparison must not see it.
 *
 * <p>All of a branch's destinations are kept, including every case of a switch. A partial set
 * would understate the control flow, and an analysis reading it would then claim a dominance that
 * does not hold — reaching fewer nodes makes dominance look stronger, not weaker.
 */
public record Insn(int opcode, String mnemonic, String operand, int[] targets, boolean canThrow) {

    private static final int[] NO_TARGETS = new int[0];

    /** An index that is not a valid instruction position, used when a target cannot be resolved. */
    public static final int NO_TARGET = -1;

    public Insn(int opcode, String mnemonic, String operand, boolean canThrow) {
        this(opcode, mnemonic, operand, NO_TARGETS, canThrow);
    }

    public Insn(int opcode, String mnemonic, String operand, int target, boolean canThrow) {
        this(opcode, mnemonic, operand, target < 0 ? NO_TARGETS : new int[]{target}, canThrow);
    }

    /** Mnemonic plus resolved operand, with an empty operand omitted. */
    public String render() {
        return operand.isEmpty() ? mnemonic : mnemonic + " " + operand;
    }
}