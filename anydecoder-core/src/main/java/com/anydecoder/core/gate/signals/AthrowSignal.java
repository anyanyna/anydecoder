package com.anydecoder.core.gate.signals;

import com.anydecoder.core.gate.Divergence;
import com.anydecoder.core.gate.Insn;
import com.anydecoder.core.gate.MethodModel;
import com.anydecoder.core.gate.Severity;
import com.anydecoder.core.gate.Signal;
import org.objectweb.asm.Opcodes;

import java.util.Optional;

/**
 * Counts {@code athrow}.
 *
 * <p>A rethrow rewritten into a plain jump is swallowed silently: the exception table still
 * looks right and the instruction delta is a couple of bytes, so every other signal stays
 * quiet. Only the rethrow count shows it. This happened in the corpus, where a
 * {@code continue} was moved into a {@code finally} and compiled to a {@code goto}.
 */
public final class AthrowSignal implements Signal {

    @Override
    public String id() {
        return "athrow";
    }

    @Override
    public Severity severity() {
        return Severity.HARD;
    }

    @Override
    public Optional<Divergence> compare(MethodModel original, MethodModel rebuilt) {
        int a = count(original);
        int b = count(rebuilt);
        if (a == b) {
            return Optional.empty();
        }
        return Optional.of(Divergence
                .of(id(), original.key(), severity(),
                        "rethrow count differs: original " + a + ", rebuild " + b
                                + " (an exception may have been swallowed)")
                .withEvidence("original", Integer.toString(a))
                .withEvidence("rebuilt", Integer.toString(b)));
    }

    private static int count(MethodModel method) {
        int n = 0;
        for (Insn insn : method.instructions()) {
            if (insn.opcode() == Opcodes.ATHROW) {
                n++;
            }
        }
        return n;
    }
}
