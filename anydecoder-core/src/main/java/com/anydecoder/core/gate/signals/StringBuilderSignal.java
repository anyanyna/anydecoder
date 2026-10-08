package com.anydecoder.core.gate.signals;

import com.anydecoder.core.gate.Divergence;
import com.anydecoder.core.gate.Insn;
import com.anydecoder.core.gate.MethodModel;
import com.anydecoder.core.gate.Severity;
import com.anydecoder.core.gate.Signal;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Records the string-concatenation traffic that {@link InvocationSignal} deliberately ignores.
 *
 * <p>This is advisory only. It exists so the exclusion is visible in the report rather than
 * hidden inside a comparison: a reader can see that, say, the rebuild has one extra
 * {@code append}, and know that is the expected javac/ecj difference rather than something the
 * gate overlooked.
 */
public final class StringBuilderSignal implements Signal {

    @Override
    public String id() {
        return "concat";
    }

    @Override
    public Severity severity() {
        return Severity.ADVISORY;
    }

    @Override
    public Optional<Divergence> compare(MethodModel original, MethodModel rebuilt) {
        Map<String, Integer> a = Multisets.counts(concatTraffic(original), s -> s);
        Map<String, Integer> b = Multisets.counts(concatTraffic(rebuilt), s -> s);
        if (a.equals(b)) {
            return Optional.empty();
        }
        return Optional.of(Divergence
                .of(id(), original.key(), severity(),
                        "string concatenation traffic differs, as expected between compilers: "
                                + Multisets.describeDiff(a, b, 4))
                .withEvidence("original", a.toString())
                .withEvidence("rebuilt", b.toString()));
    }

    private static List<String> concatTraffic(MethodModel method) {
        List<String> out = new ArrayList<>();
        for (Insn insn : method.instructions()) {
            if (insn.opcode() < Opcodes.INVOKEVIRTUAL || insn.opcode() > Opcodes.INVOKEDYNAMIC) {
                continue;
            }
            if (InvocationSignal.isConcatenationTraffic(insn.operand())) {
                out.add(insn.operand());
            }
        }
        return out;
    }
}
