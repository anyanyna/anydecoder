package com.anydecoder.core.gate;

import java.util.List;

/**
 * The gate's conclusion about one class, with everything needed to audit it.
 *
 * @param className    internal name, {@code com/example/Foo}
 * @param verdict      the conclusion
 * @param divergences  every difference found, including advisory ones
 * @param explanations the compiler artefacts that accounted for blocking differences
 * @param unexplained  blocking differences nothing could account for; carries the verdict
 * @param note         set when the verdict is HARNESS_ERROR, explaining why
 */
public record ClassVerdict(
        String className,
        Verdict verdict,
        List<Divergence> divergences,
        List<BenignDivergenceClassifier.Explanation> explanations,
        List<Divergence> unexplained,
        String note) {

    public static ClassVerdict harnessError(String className, String note) {
        return new ClassVerdict(className, Verdict.HARNESS_ERROR, List.of(), List.of(), List.of(), note);
    }

    public List<Divergence> blocking() {
        return divergences.stream().filter(d -> d.severity() != Severity.ADVISORY).toList();
    }

    public List<Divergence> advisory() {
        return divergences.stream().filter(d -> d.severity() == Severity.ADVISORY).toList();
    }
}
