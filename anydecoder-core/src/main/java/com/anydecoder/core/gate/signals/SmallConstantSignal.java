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
        return Severity.ADVISORY;
    }

    /**
     * Advisory, and the measurement is why.
     *
     * <p>The signal closes a real blind spot: {@code return 1} and {@code return 2} had been
     * reported as equivalent, because the value lives in <em>which</em> instruction appears and
     * nothing compared instructions by kind.
     *
     * <p>But on 2962 classes of source already verified faithful by hand, it produced no true
     * positives and roughly 150 classes of noise, and the attempts to separate the two failed on
     * the evidence. A value present on one side only was the natural candidate for "the author
     * wrote something different" — it was rejected: 90 classes had a set difference and every one
     * was a compiler choice about how to initialise a local or push a zero.
     *
     * <p>So the difference is recorded rather than judged. A reader looking at a method sees the
     * note; the verdict does not claim more than it can support. The alternative — calling these
     * divergences, at either severity — would roughly double the review list with entries that
     * are not actionable.
     */
    @Override
    public Optional<Divergence> compare(MethodModel original, MethodModel rebuilt) {
        Map<String, Integer> a = Multisets.counts(pushes(original), s -> s);
        Map<String, Integer> b = Multisets.counts(pushes(rebuilt), s -> s);
        if (a.equals(b)) {
            return Optional.empty();
        }
        boolean sameValues = a.keySet().equals(b.keySet());
        String detail = (sameValues
                ? "the same small constants are pushed a different number of times: "
                : "a small constant value is pushed on one side only: ")
                + Multisets.describeDiff(a, b, 6);
        return Optional.of(Divergence
                .of(id(), original.key(), severity(), detail)
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
