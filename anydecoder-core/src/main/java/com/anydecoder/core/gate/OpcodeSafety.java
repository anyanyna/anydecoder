package com.anydecoder.core.gate;

import org.objectweb.asm.Opcodes;

import java.util.Arrays;

/**
 * Which opcodes can throw.
 *
 * <p>This drives exception-table normalisation. javac and ecj segment a try block's protected
 * range differently -- ecj excludes trailing {@code ireturn} and {@code goto} instructions,
 * javac uses one continuous range -- and those differences are invisible in behaviour. An
 * exception-table entry that covers no throwing instruction is therefore not a difference.
 *
 * <p>The table is deliberately biased toward "can throw": an opcode not listed here is treated
 * as throwing, so an unhandled opcode can never cause two genuinely different exception tables
 * to be silently merged.
 *
 * <p>Note what is <em>absent</em>: {@code idiv}/{@code irem} and friends throw
 * {@code ArithmeticException}, {@code arraylength}/{@code aaload} throw {@code NPE} or
 * {@code AIOOBE}, {@code checkcast} throws {@code ClassCastException}, and every field access
 * and {@code new} can throw linkage errors. All are absent on purpose.
 */
public final class OpcodeSafety {

    private static final boolean[] CAN_THROW = new boolean[256];

    static {
        Arrays.fill(CAN_THROW, true);
        int[] safe = {
                Opcodes.NOP, Opcodes.GOTO, Opcodes.JSR, Opcodes.RET,

                Opcodes.RETURN, Opcodes.IRETURN, Opcodes.LRETURN,
                Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN,

                Opcodes.ACONST_NULL,
                // ASM represents every pool load as LDC; the _w and 2_w encodings are chosen
                // when the class is written, not something the tree API exposes.
                Opcodes.BIPUSH, Opcodes.SIPUSH, Opcodes.LDC,

                Opcodes.POP, Opcodes.POP2,
                Opcodes.DUP, Opcodes.DUP_X1, Opcodes.DUP_X2,
                Opcodes.DUP2, Opcodes.DUP2_X1, Opcodes.DUP2_X2,
                Opcodes.SWAP,

                Opcodes.IINC,
                Opcodes.INSTANCEOF,

                Opcodes.IADD, Opcodes.LADD, Opcodes.FADD,
                Opcodes.ISUB, Opcodes.LSUB, Opcodes.FSUB,
                Opcodes.IMUL, Opcodes.LMUL, Opcodes.FMUL,
                Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL,

                Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG,

                Opcodes.I2L, Opcodes.I2F, Opcodes.I2D, Opcodes.I2B, Opcodes.I2C, Opcodes.I2S,
                Opcodes.L2I, Opcodes.L2F, Opcodes.L2D,
                Opcodes.F2I, Opcodes.F2L, Opcodes.F2D,
                Opcodes.D2I, Opcodes.D2L, Opcodes.D2F,

                Opcodes.ISHL, Opcodes.LSHL, Opcodes.ISHR, Opcodes.LSHR,
                Opcodes.IUSHR, Opcodes.LUSHR,
                Opcodes.IAND, Opcodes.LAND, Opcodes.IOR, Opcodes.LOR,
                Opcodes.IXOR, Opcodes.LXOR,

                Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG,

                Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH,
        };
        for (int op : safe) {
            CAN_THROW[op] = false;
        }
        // Loading and storing a local cannot throw, for every type and every slot form.
        for (int op = Opcodes.ILOAD; op <= Opcodes.ALOAD; op++) CAN_THROW[op] = false;
        for (int op = Opcodes.ISTORE; op <= Opcodes.ASTORE; op++) CAN_THROW[op] = false;
        for (int op = Opcodes.ICONST_M1; op <= Opcodes.ICONST_5; op++) CAN_THROW[op] = false;
        for (int op = Opcodes.LCONST_0; op <= Opcodes.DCONST_1; op++) CAN_THROW[op] = false;
    }

    private OpcodeSafety() {
    }

    public static boolean canThrow(int opcode) {
        if (opcode < 0 || opcode >= CAN_THROW.length) {
            return true;
        }
        return CAN_THROW[opcode];
    }
}
