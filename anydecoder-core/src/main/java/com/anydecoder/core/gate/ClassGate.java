package com.anydecoder.core.gate;

import com.anydecoder.core.gate.signals.AthrowSignal;
import com.anydecoder.core.gate.signals.ConstantSignal;
import com.anydecoder.core.gate.signals.ExceptionTableSignal;
import com.anydecoder.core.gate.signals.FieldAccessSignal;
import com.anydecoder.core.gate.signals.InstructionCountSignal;
import com.anydecoder.core.gate.signals.InvocationSignal;
import com.anydecoder.core.gate.signals.LocalVarSignal;
import com.anydecoder.core.gate.signals.StringBuilderSignal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Compares one original class with its recompilation and returns a verdict.
 *
 * <p>The gate answers a narrow, checkable question: <em>does the recompiled bytecode do the same
 * things as the original?</em> It deliberately does not answer "is this the source the author
 * wrote". Variable names, comments, formatting, whether a loop was written as {@code while} or
 * {@code for} -- none of that is recoverable and none of it is claimed. What is claimed is
 * behavioural equivalence, and only where the signals actually support it.
 */
public final class ClassGate {

    private static final List<Signal> METHOD_SIGNALS = List.of(
            new ExceptionTableSignal(),
            new AthrowSignal(),
            new InvocationSignal(),
            new FieldAccessSignal(),
            new ConstantSignal(),
            new InstructionCountSignal(),
            new StringBuilderSignal(),
            new LocalVarSignal());

    public ClassVerdict compare(ClassModel original, ClassModel rebuilt) {
        List<Divergence> divergences = new ArrayList<>();
        List<BenignDivergenceClassifier.Explanation> explanations = new ArrayList<>();
        List<Divergence> unexplained = new ArrayList<>();

        for (MethodModel method : original.methods()) {
            MethodModel counterpart = rebuilt.method(method.key());
            if (counterpart == null) {
                continue;
            }
            List<Divergence> methodDivergences = new ArrayList<>();
            for (Signal signal : METHOD_SIGNALS) {
                signal.compare(method, counterpart).ifPresent(methodDivergences::add);
            }
            divergences.addAll(methodDivergences);

            BenignDivergenceClassifier.Result result =
                    BenignDivergenceClassifier.classify(method, counterpart, methodDivergences);
            explanations.addAll(result.explanations());
            unexplained.addAll(result.unexplained());
        }

        // Class-level differences are never explicable by a compiler artefact, so they go
        // straight to unexplained rather than through the classifier. A changed signature, a
        // changed field, or a method the rebuild does not produce is a difference in what the
        // class offers, and no code-generation story explains that away.
        List<Divergence> classLevel = new ArrayList<>();
        ApiComparison.compare(original, rebuilt).ifPresent(classLevel::add);
        collectMethodSetDifferences(original, rebuilt, classLevel);
        divergences.addAll(classLevel);
        unexplained.addAll(classLevel);

        return new ClassVerdict(original.name(), verdictOf(divergences, unexplained),
                List.copyOf(divergences), List.copyOf(explanations), List.copyOf(unexplained), null);
    }

    private void collectMethodSetDifferences(ClassModel original, ClassModel rebuilt,
                                             List<Divergence> divergences) {
        Set<String> originalKeys = new HashSet<>();
        for (MethodModel m : original.methods()) {
            originalKeys.add(m.key());
        }
        Set<String> rebuiltKeys = new HashSet<>();
        for (MethodModel m : rebuilt.methods()) {
            rebuiltKeys.add(m.key());
        }

        for (String key : originalKeys) {
            if (!rebuiltKeys.contains(key) && !hasSyntheticAccessor(original, key)) {
                divergences.add(Divergence.of("members", key, Severity.HARD,
                        "method is absent from the rebuild"));
            }
        }
        for (String key : rebuiltKeys) {
            if (!originalKeys.contains(key) && !hasSyntheticAccessor(rebuilt, key)) {
                divergences.add(Divergence.of("members", key, Severity.HARD,
                        "method exists only in the rebuild"));
            }
        }
    }

    private boolean hasSyntheticAccessor(ClassModel model, String key) {
        int paren = key.indexOf('(');
        return paren > 0 && ApiComparison.isSyntheticAccessor(key.substring(0, paren));
    }

    private Verdict verdictOf(List<Divergence> divergences, List<Divergence> unexplained) {
        if (divergences.stream().noneMatch(d -> d.severity() != Severity.ADVISORY)) {
            return Verdict.EQUIVALENT;
        }
        if (unexplained.isEmpty()) {
            return Verdict.BENIGN;
        }
        boolean hard = unexplained.stream().anyMatch(d -> d.severity() == Severity.HARD);
        return hard ? Verdict.DAMAGED : Verdict.SUSPECT;
    }

    /** Exposed so callers can report which signals a given build of the gate runs. */
    public static List<String> signalIds() {
        List<String> ids = new ArrayList<>();
        ids.add("api");
        ids.add("members");
        METHOD_SIGNALS.forEach(s -> ids.add(s.id()));
        return List.copyOf(ids);
    }
}
