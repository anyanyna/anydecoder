package com.anydecoder.core.gate;

import com.anydecoder.core.gate.signals.AthrowSignal;
import com.anydecoder.core.gate.signals.ConstantSignal;
import com.anydecoder.core.gate.signals.ExceptionTableSignal;
import com.anydecoder.core.gate.signals.FieldAccessSignal;
import com.anydecoder.core.gate.signals.InstructionCountSignal;
import com.anydecoder.core.gate.signals.InvocationSignal;
import com.anydecoder.core.gate.signals.LocalVarSignal;
import com.anydecoder.core.gate.signals.SmallConstantSignal;
import com.anydecoder.core.gate.signals.StringBuilderSignal;
import com.anydecoder.core.gate.signals.ThrowsSignal;

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
 * {@code for} -- none of that is recoverable and none of it is claimed.
 *
 * <h2>The classpath is part of the comparison</h2>
 *
 * <p>Two of the signals need to know whether two types are in the same hierarchy, because a
 * decompiler that declares {@code ArrayList} where the source said {@code List} has not changed
 * which method is called -- only the type the call site names. Telling that apart from a call
 * genuinely moved to another class requires looking at supertypes, so the gate takes an index
 * built from the same classpath the rebuild used.
 *
 * <p>Without it, the two signals reported 1457 findings on source that had been verified
 * faithful by nine days of manual bytecode examination.
 */
public final class ClassGate {

    private final List<Signal> methodSignals;

    public ClassGate(HierarchyIndex hierarchy) {
        this.methodSignals = List.of(
                new ExceptionTableSignal(),
                new AthrowSignal(),
                new InvocationSignal(hierarchy),
                new FieldAccessSignal(hierarchy),
                new ConstantSignal(),
                new SmallConstantSignal(),
                new InstructionCountSignal(),
                new StringBuilderSignal(),
                new ThrowsSignal(),
                new LocalVarSignal());
    }

    /**
     * A gate with no hierarchy information. Receiver owners are then compared literally, which
     * is stricter than it should be -- useful for tests that construct their own bytecode, wrong
     * for a real rebuild.
     */
    public ClassGate() {
        this(HierarchyIndex.empty());
    }

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
            for (Signal signal : methodSignals) {
                signal.compare(method, counterpart).ifPresent(methodDivergences::add);
            }
            divergences.addAll(methodDivergences);

            BenignDivergenceClassifier.Result result =
                    BenignDivergenceClassifier.classify(original, rebuilt, method, counterpart,
                            methodDivergences);
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
        Set<String> originalKeys = declaredMethodKeys(original);
        Set<String> rebuiltKeys = declaredMethodKeys(rebuilt);

        for (String key : originalKeys) {
            if (!rebuiltKeys.contains(key)) {
                divergences.add(Divergence.of("members", key, Severity.HARD,
                        "method is absent from the rebuild"));
            }
        }
        for (String key : rebuiltKeys) {
            if (!originalKeys.contains(key)) {
                divergences.add(Divergence.of("members", key, Severity.HARD,
                        "method exists only in the rebuild"));
            }
        }
    }

    /**
     * The methods a class declares that have a source-level counterpart.
     *
     * <p>Synthetic and bridge methods are excluded, because whether they exist is a property of
     * the compiler rather than of the source. Two cases make this necessary. The Eclipse compiler
     * names inner-class accessors {@code access$0} where javac writes {@code access$000}. And
     * javac from JDK 15 on emits a private synthetic {@code $values()} for every enum, which
     * javac 8 did not -- so rebuilding an enum compiled a decade earlier with a current JDK
     * produces a method the original could not have had.
     */
    private static Set<String> declaredMethodKeys(ClassModel model) {
        Set<String> keys = new HashSet<>();
        for (MethodModel method : model.methods()) {
            if ((method.access() & (org.objectweb.asm.Opcodes.ACC_SYNTHETIC
                    | org.objectweb.asm.Opcodes.ACC_BRIDGE)) != 0) {
                continue;
            }
            keys.add(method.key());
        }
        return keys;
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

    /** The signal identifiers this build runs, for a report to state what was compared. */
    public List<String> signalIds() {
        List<String> ids = new ArrayList<>();
        ids.add("api");
        ids.add("members");
        methodSignals.forEach(s -> ids.add(s.id()));
        return List.copyOf(ids);
    }
}
