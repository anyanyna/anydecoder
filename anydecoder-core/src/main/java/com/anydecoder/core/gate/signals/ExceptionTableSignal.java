package com.anydecoder.core.gate.signals;

import com.anydecoder.core.gate.Divergence;
import com.anydecoder.core.gate.ExceptionTableNormalizer;
import com.anydecoder.core.gate.MethodModel;
import com.anydecoder.core.gate.Severity;
import com.anydecoder.core.gate.Signal;

import java.util.List;
import java.util.Optional;

/**
 * The hard gate.
 *
 * <p>Exception-table shape is the one structural signal that survives the javac/ecj code
 * generation difference, so it is what actually decides whether a recompilation is faithful.
 * It is the only signal that catches a dropped {@code try} block, a {@code finally} rendered as
 * a try-with-resources, or a catch clause whose exception type changed -- all three of which
 * occurred in the corpus this gate was built from.
 */
public final class ExceptionTableSignal implements Signal {

    @Override
    public String id() {
        return "excp";
    }

    @Override
    public Severity severity() {
        return Severity.HARD;
    }

    @Override
    public Optional<Divergence> compare(MethodModel original, MethodModel rebuilt) {
        List<ExceptionTableNormalizer.TypeGroup> a = ExceptionTableNormalizer.normalize(original);
        List<ExceptionTableNormalizer.TypeGroup> b = ExceptionTableNormalizer.normalize(rebuilt);
        if (a.equals(b)) {
            return Optional.empty();
        }
        return Optional.of(Divergence
                .of(id(), original.key(), severity(),
                        "exception table shape differs: original " + a + ", rebuild " + b)
                .withEvidence("original", a.toString())
                .withEvidence("rebuilt", b.toString()));
    }
}
