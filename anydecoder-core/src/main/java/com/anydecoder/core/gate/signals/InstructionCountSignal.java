package com.anydecoder.core.gate.signals;

import com.anydecoder.core.gate.Divergence;
import com.anydecoder.core.gate.MethodModel;
import com.anydecoder.core.gate.Severity;
import com.anydecoder.core.gate.Signal;

import java.util.Optional;

/**
 * Flags a method whose body changed size far beyond what a compiler difference explains.
 *
 * <p>The threshold has both a relative and an absolute part, and both are needed. A relative-only
 * threshold fires on tiny accessors, where a method of nine instructions legitimately becomes
 * five -- ecj materialises a boolean as {@code ifeq/iconst_1/ireturn/iconst_0/ireturn} where
 * javac uses a single conditional branch. An absolute floor of six instructions absorbs that
 * without also excusing a real deletion.
 *
 * <p>This is SUSPECT rather than HARD: a large size change can come from the compiler's own
 * choices (ecj performs no {@code iinc} optimisation, so {@code ++x} costs four instructions
 * instead of one), and the report says which. It is a prompt to look, not a verdict.
 */
public final class InstructionCountSignal implements Signal {

    static final double RELATIVE_THRESHOLD = 0.15;
    static final int ABSOLUTE_FLOOR = 6;

    @Override
    public String id() {
        return "instrCount";
    }

    @Override
    public Severity severity() {
        return Severity.SUSPECT;
    }

    @Override
    public Optional<Divergence> compare(MethodModel original, MethodModel rebuilt) {
        int a = original.instructions().size();
        int b = rebuilt.instructions().size();
        int reference = Math.max(a, b);
        if (reference == 0) {
            return Optional.empty();
        }
        int delta = Math.abs(a - b);
        double ratio = (double) delta / reference;
        if (ratio <= RELATIVE_THRESHOLD || delta < ABSOLUTE_FLOOR) {
            return Optional.empty();
        }
        return Optional.of(Divergence
                .of(id(), original.key(), severity(),
                        "instruction count " + a + " vs " + b
                                + String.format(" (%+.0f%%)", 100.0 * (b - a) / reference))
                .withEvidence("original", Integer.toString(a))
                .withEvidence("rebuilt", Integer.toString(b))
                .withEvidence("delta", Integer.toString(delta))
                .withEvidence("ratio", String.format("%.3f", ratio)));
    }
}
