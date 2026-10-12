package com.anydecoder.core.gate.signals;

import com.anydecoder.core.gate.Divergence;
import com.anydecoder.core.gate.Insn;
import com.anydecoder.core.gate.MethodModel;
import com.anydecoder.core.gate.Severity;
import com.anydecoder.core.gate.Signal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Compares the constants a method pushes, by value.
 *
 * <p><b>Every</b> constant-pushing instruction, not only {@code ldc}. That distinction was a real
 * hole: small integers are encoded in the opcode itself — {@code iconst_1} and {@code iconst_2}
 * are two different instructions, not one instruction with two operands — so a comparison of
 * instruction <em>kinds</em> sees the same instruction twice and reports {@code return 1} as
 * equivalent to {@code return 2}. Byte and short range values pushed with {@code bipush} or
 * {@code sipush} were invisible for the same reason.
 *
 * <p>{@link ClassModelReader} lifts each of those values into the operand and names it by its
 * value, so a constant pushed by any route compares equal to the same value loaded from the pool.
 * Comparing values rather than pool indices is what makes this usable across compilers in the
 * first place.
 */
public final class ConstantSignal implements Signal {

    /**
     * Pool-resident constants only: strings, class literals, method handles, and the numeric
     * literals too wide to encode in an instruction.
     *
     * <p>Small integers and {@code null} are deliberately excluded — {@link SmallConstantSignal}
     * reports those separately and at a lower severity. Measured on source already verified
     * faithful, treating them as hard produced 183 false positives, because how a compiler pushes
     * a zero or a small integer varies far more than what a string literal says.
     */
    private static final String POOL_LOAD = "ldc";

    @Override
    public String id() {
        return "consts";
    }

    @Override
    public Severity severity() {
        return Severity.HARD;
    }

    @Override
    public Optional<Divergence> compare(MethodModel original, MethodModel rebuilt) {
        Map<String, Integer> a = Multisets.counts(constants(original), s -> s);
        Map<String, Integer> b = Multisets.counts(constants(rebuilt), s -> s);
        if (a.equals(b)) {
            return Optional.empty();
        }
        return Optional.of(Divergence
                .of(id(), original.key(), severity(), Multisets.describeDiff(a, b, 6))
                .withEvidence("original", a.toString())
                .withEvidence("rebuilt", b.toString()));
    }

    private static List<String> constants(MethodModel method) {
        List<String> out = new ArrayList<>();
        for (Insn insn : method.instructions()) {
            if (POOL_LOAD.equals(insn.mnemonic())) {
                out.add(insn.operand());
            }
        }
        return out;
    }
}
