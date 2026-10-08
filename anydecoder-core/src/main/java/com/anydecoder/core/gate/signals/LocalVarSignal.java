package com.anydecoder.core.gate.signals;

import com.anydecoder.core.gate.Divergence;
import com.anydecoder.core.gate.LocalVar;
import com.anydecoder.core.gate.MethodModel;
import com.anydecoder.core.gate.Severity;
import com.anydecoder.core.gate.Signal;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Compares the declared local variables a method's debug information records.
 *
 * <p>Advisory only, and deliberately so. A decompiler routinely declares a narrower type than
 * the original -- {@code DeleteOnCloseFileInputStream} where the source said
 * {@code InputStream} -- and that changes nothing about behaviour. The signal still earns its
 * place as a review prompt: a local the original declared as {@code int} coming back as
 * {@code Integer} is the signature of an autoboxing defect that would otherwise pass every
 * other signal.
 *
 * <p>Variable names are compared, but note that CFR does not recover them: it emits
 * {@code var1}, {@code var2}. Only the type column carries information about the original.
 */
public final class LocalVarSignal implements Signal {

    @Override
    public String id() {
        return "lvt";
    }

    @Override
    public Severity severity() {
        return Severity.ADVISORY;
    }

    @Override
    public Optional<Divergence> compare(MethodModel original, MethodModel rebuilt) {
        Map<String, Integer> a = Multisets.counts(original.localVariables(), LocalVar::render);
        Map<String, Integer> b = Multisets.counts(rebuilt.localVariables(), LocalVar::render);
        if (a.equals(b)) {
            return Optional.empty();
        }
        return Optional.of(Divergence
                .of(id(), original.key(), severity(),
                        "declared local variables differ: " + Multisets.describeDiff(a, b, 5))
                .withEvidence("original", types(original.localVariables()))
                .withEvidence("rebuilt", types(rebuilt.localVariables())));
    }

    private static String types(List<LocalVar> vars) {
        return vars.stream().map(LocalVar::descriptor).distinct().sorted()
                .reduce((x, y) -> x + "," + y).orElse("(none)");
    }
}
