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
 * Compares <em>which</em> methods are called, not merely how many.
 *
 * <p>The original gate counted invocations and got a useful signal only for methods under 300
 * instructions: on a large method a shifted invocation is invisible in a count. Comparing the
 * resolved multiset of callees removes that size caveat, so a duplicated or missing
 * {@code close()} is caught in a 2000-instruction method too.
 *
 * <p>Calls into {@code StringBuilder} and {@code StringBuffer} are excluded, because ecj and
 * javac build concatenations differently by construction: for {@code String.valueOf(x) + y},
 * ecj emits {@code new StringBuilder(String.valueOf(x))} followed by one {@code append}, while
 * javac emits {@code new StringBuilder()} followed by two. That traffic always differs for
 * identical source. {@link StringBuilderSignal} records it for the reader instead of letting it
 * decide anything. Calls to {@code String.valueOf} itself are <em>kept</em>: they are semantic,
 * and rewriting one away can silently change which overload binds.
 */
public final class InvocationSignal implements Signal {

    private static final List<String> CONCATENATION_OWNERS = List.of(
            "java/lang/StringBuilder", "java/lang/StringBuffer");

    @Override
    public String id() {
        return "invokes";
    }

    @Override
    public Severity severity() {
        return Severity.HARD;
    }

    @Override
    public Optional<Divergence> compare(MethodModel original, MethodModel rebuilt) {
        Map<String, Integer> a = Multisets.counts(callees(original), s -> s);
        Map<String, Integer> b = Multisets.counts(callees(rebuilt), s -> s);
        if (a.equals(b)) {
            return Optional.empty();
        }
        return Optional.of(Divergence
                .of(id(), original.key(), severity(), Multisets.describeDiff(a, b, 6))
                .withEvidence("original", describe(a))
                .withEvidence("rebuilt", describe(b)));
    }

    static List<String> callees(MethodModel method) {
        List<String> out = new ArrayList<>();
        for (Insn insn : method.instructions()) {
            if (insn.opcode() < Opcodes.INVOKEVIRTUAL || insn.opcode() > Opcodes.INVOKEDYNAMIC) {
                continue;
            }
            if (isConcatenationTraffic(insn.operand())) {
                continue;
            }
            out.add(insn.operand());
        }
        return out;
    }

    static boolean isConcatenationTraffic(String resolvedCallee) {
        for (String owner : CONCATENATION_OWNERS) {
            if (resolvedCallee.startsWith(owner + ".")) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .map(e -> e.getValue() > 1 ? e.getKey() + " x" + e.getValue() : e.getKey())
                .sorted()
                .reduce((x, y) -> x + ", " + y)
                .orElse("(none)");
    }
}
