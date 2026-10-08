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
 * Compares the constants a method loads, by value.
 *
 * <p>Only pool-resident constants are compared -- strings, class literals, large numeric
 * literals, method handles. Small integer constants arrive as dedicated opcodes
 * ({@code iconst_1}) which the instruction stream already covers. Comparing values rather than
 * pool indices is what makes this usable across compilers.
 */
public final class ConstantSignal implements Signal {

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
            // ClassModelReader renders every pool load under the mnemonic "ldc", folding the
            // _w and 2_w width variants that carry no meaning.
            if ("ldc".equals(insn.mnemonic())) {
                out.add(insn.operand());
            }
        }
        return out;
    }
}
