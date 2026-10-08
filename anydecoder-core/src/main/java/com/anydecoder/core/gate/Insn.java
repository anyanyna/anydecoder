package com.anydecoder.core.gate;

/**
 * One JVM instruction, reduced to a form that survives recompilation.
 *
 * <p>Operands are resolved, never raw constant-pool indices: two compilations of the same
 * source assign different pool indices and different branch offsets, so comparing those
 * would report every method as different. What is kept is the part that carries meaning --
 * which method is called, which field is touched, which constant is loaded.
 */
public record Insn(int opcode, String mnemonic, String operand, boolean canThrow) {

    /** Mnemonic plus resolved operand, with an empty operand omitted. */
    public String render() {
        return operand.isEmpty() ? mnemonic : mnemonic + " " + operand;
    }
}
