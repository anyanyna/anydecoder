package com.anydecoder.core.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link OpcodeSafety} must and must not call safe.
 *
 * <p>This table decides whether an exception-table entry that covers no throwing instruction is
 * treated as a difference. It exists because ecj and javac segment protected ranges differently,
 * which accounted for 39 of 42 phantom hard failures in the corpus.
 *
 * <p>The failure mode in the other direction is the dangerous one: adding an opcode to the safe
 * list that can in fact throw would let two genuinely different exception tables be merged
 * silently. So the tests below pin the deliberate absences as tightly as the inclusions.
 */
class OpcodeSafetyTest {

    @Test
    @DisplayName("instructions that cannot throw are reported safe")
    void reportsNonThrowingInstructionsSafe() {
        int[] safe = {
                Opcodes.NOP, Opcodes.GOTO, Opcodes.JSR, Opcodes.RET,
                Opcodes.RETURN, Opcodes.IRETURN, Opcodes.ARETURN,
                Opcodes.ACONST_NULL, Opcodes.BIPUSH, Opcodes.SIPUSH, Opcodes.LDC,
                Opcodes.POP, Opcodes.DUP, Opcodes.SWAP,
                Opcodes.IINC, Opcodes.INSTANCEOF,
                Opcodes.IADD, Opcodes.LMUL, Opcodes.DSUB,
                Opcodes.INEG, Opcodes.I2L, Opcodes.D2F,
                Opcodes.ISHL, Opcodes.LXOR,
                Opcodes.LCMP, Opcodes.DCMPG,
                Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH,
        };

        for (int opcode : safe) {
            assertFalse(OpcodeSafety.canThrow(opcode),
                    "opcode " + opcode + " is pure computation and cannot throw");
        }
    }

    @Test
    @DisplayName("loads, stores and small constants are safe for every type")
    void reportsLoadsStoresAndConstantsSafe() {
        for (int opcode = Opcodes.ILOAD; opcode <= Opcodes.ALOAD; opcode++) {
            assertFalse(OpcodeSafety.canThrow(opcode), "load " + opcode);
        }
        for (int opcode = Opcodes.ISTORE; opcode <= Opcodes.ASTORE; opcode++) {
            assertFalse(OpcodeSafety.canThrow(opcode), "store " + opcode);
        }
        for (int opcode = Opcodes.ICONST_M1; opcode <= Opcodes.ICONST_5; opcode++) {
            assertFalse(OpcodeSafety.canThrow(opcode), "int constant " + opcode);
        }
        for (int opcode = Opcodes.LCONST_0; opcode <= Opcodes.DCONST_1; opcode++) {
            assertFalse(OpcodeSafety.canThrow(opcode), "wide constant " + opcode);
        }
    }

    @Test
    @DisplayName("division, arrays, casts, fields, allocation and dispatch stay unsafe on purpose")
    void keepsTheDeliberateAbsencesUnsafe() {
        int[] canThrow = {
                Opcodes.IDIV, Opcodes.IREM, Opcodes.LDIV, Opcodes.LREM,
                Opcodes.FDIV, Opcodes.FREM, Opcodes.DDIV, Opcodes.DREM,
                Opcodes.ARRAYLENGTH, Opcodes.IALOAD, Opcodes.AALOAD,
                Opcodes.CHECKCAST,
                Opcodes.GETFIELD, Opcodes.PUTFIELD, Opcodes.GETSTATIC, Opcodes.PUTSTATIC,
                Opcodes.NEW, Opcodes.NEWARRAY,
                Opcodes.ATHROW,
                Opcodes.MONITORENTER, Opcodes.MONITOREXIT,
                Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL,
                Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE, Opcodes.INVOKEDYNAMIC,
        };

        for (int opcode : canThrow) {
            assertTrue(OpcodeSafety.canThrow(opcode),
                    "opcode " + opcode + " can throw; calling it safe would merge real"
                            + " exception-table differences");
        }
    }

    @Test
    @DisplayName("an opcode nobody classified is assumed able to throw")
    void treatsUnknownOpcodesConservatively() {
        assertTrue(OpcodeSafety.canThrow(-1), "out of range, and the safe answer is 'can throw'");
        assertTrue(OpcodeSafety.canThrow(256), "out of range, and the safe answer is 'can throw'");
        assertTrue(OpcodeSafety.canThrow(200), "unlisted opcodes must not default to safe");
    }
}
