package com.anydecoder.core.gate.signals;

import com.anydecoder.core.gate.Divergence;
import com.anydecoder.core.gate.MethodModel;
import com.anydecoder.core.gate.Severity;
import com.anydecoder.core.gate.Signal;

import java.util.List;
import java.util.Optional;

/**
 * Compares the {@code throws} clause.
 *
 * <p>Advisory, because it cannot change what the code does at runtime: the clause is a
 * compile-time declaration and appears nowhere in the executed bytecode.
 *
 * <p>It earns its place by being invisible to everything else here. A method's descriptor does not
 * include the exceptions it declares — {@code void f()} and {@code void f() throws IOException}
 * have the same one — so a decompiler that drops a constructor, or drops its {@code throws}, can
 * produce source that matches the original on every other signal.
 *
 * <p>That is not hypothetical. A real corpus contains an abstract class whose constructor the
 * decompiler omitted entirely; javac then synthesises a default constructor with no {@code throws},
 * and the result is indistinguishable from the original under every other signal. The declared
 * exceptions differ, and that is the only place the difference shows. It matters because a
 * subclass may not declare a checked exception its superclass's constructor does not — so the
 * recovered source can be unusable as a base class while appearing perfectly faithful.
 */
public final class ThrowsSignal implements Signal {

    @Override
    public String id() {
        return "throws";
    }

    @Override
    public Severity severity() {
        return Severity.ADVISORY;
    }

    @Override
    public Optional<Divergence> compare(MethodModel original, MethodModel rebuilt) {
        List<String> a = sorted(original.declaredExceptions());
        List<String> b = sorted(rebuilt.declaredExceptions());
        if (a.equals(b)) {
            return Optional.empty();
        }
        return Optional.of(Divergence
                .of(id(), original.key(), severity(),
                        "declared exceptions differ: original " + render(a)
                                + ", rebuild " + render(b))
                .withEvidence("original", render(a))
                .withEvidence("rebuilt", render(b)));
    }

    private static List<String> sorted(List<String> values) {
        List<String> copy = new java.util.ArrayList<>(values);
        java.util.Collections.sort(copy);
        return copy;
    }

    private static String render(List<String> values) {
        return values.isEmpty() ? "(none)" : String.join(", ", values);
    }
}
