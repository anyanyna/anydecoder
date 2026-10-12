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
import java.util.Set;

/**
 * Compares the small constants that are encoded in an instruction rather than loaded from the
 * pool: {@code iconst_0} through {@code iconst_5}, the {@code lconst}/{@code fconst}/{@code dconst}
 * forms, {@code aconst_null}, and the {@code bipush}/{@code sipush} range.
 *
 * <p>Suspect rather than hard, and that distinction came from a measurement rather than a guess.
 * Treating these as hard closed a genuine blind spot — {@code return 1} and {@code return 2} had
 * been reported as equivalent, because the value lives in <em>which</em> instruction appears and
 * nothing compared instructions by kind — but it also produced 183 false positives on source
 * already verified faithful. How a compiler pushes a zero, a small integer or a null varies far
 * more than what a string literal says, so a difference here is worth reporting and is not
 * evidence on its own.
 *
 * <p>Pool-resident constants stay hard, in {@link ConstantSignal}.
 */
public final class SmallConstantSignal implements Signal {

    /** The families whose value is carried in the instruction, as named by ClassModelReader. */
    private static final Set<String> SMALL_PUSHES = Set.of(
            "iconst", "lconst", "fconst", "dconst", "aconst_null", "push");

    @Override
    public String id() {
        return "smallConsts";
    }

    @Override
    public Severity severity() {
        return Severity.SUSPECT;
    }

    @Override
    public Optional<Divergence> compare(MethodModel original, MethodModel rebuilt) {
        Map<String, Integer> a = Multisets.counts(pushes(original), s -> s);
        Map<String, Integer> b = Multisets.counts(pushes(rebuilt), s -> s);
        if (a.equals(b)) {
            return Optional.empty();
        }
        return Optional.of(Divergence
                .of(id(), original.key(), severity(), Multisets.describeDiff(a, b, 6)
                        + " -- a small constant pushed a different number of times")
                .withEvidence("original", a.toString())
                .withEvidence("rebuilt", b.toString()));
    }

    private static List<String> pushes(MethodModel method) {
        List<String> out = new ArrayList<>();
        for (Insn insn : method.instructions()) {
            if (SMALL_PUSHES.contains(insn.mnemonic())) {
                out.add(insn.operand());
            }
        }
        return out;
    }
}
