package com.anydecoder.core.gate.signals;

import com.anydecoder.core.gate.Divergence;
import com.anydecoder.core.gate.HierarchyIndex;
import com.anydecoder.core.gate.Insn;
import com.anydecoder.core.gate.MethodModel;
import com.anydecoder.core.gate.Severity;
import com.anydecoder.core.gate.Signal;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Compares <em>which</em> methods are called, not merely how many.
 *
 * <p>The original gate counted invocations and got a useful signal only for methods under 300
 * instructions: on a large method a shifted invocation is invisible in a count. Comparing the
 * resolved set of callees removes that size caveat, so a duplicated or missing {@code close()}
 * is caught in a two-thousand-instruction method too.
 *
 * <p>Compare the receiver as well, but not naively: a decompiler that declares
 * {@code ArrayList} where the source said {@code List} changes the call from
 * {@code invokeinterface List.add} to {@code invokevirtual ArrayList.add}, which is the same
 * member reached through a narrower type. {@link ResolvedTargets} pairs owners that share a
 * hierarchy and reports only the ones that do not. Without that, this signal produced 1325
 * findings on source already verified faithful by hand.
 *
 * <p>Calls into {@code StringBuilder} and {@code StringBuffer} are excluded, because ecj and
 * javac build concatenations differently by construction. Calls to {@code String.valueOf} are
 * <em>kept</em>: they are semantic, and rewriting one away can silently change which overload
 * binds.
 */
public final class InvocationSignal implements Signal {

    private static final List<String> CONCATENATION_OWNERS = List.of(
            "java/lang/StringBuilder", "java/lang/StringBuffer");

    private final HierarchyIndex hierarchy;

    public InvocationSignal(HierarchyIndex hierarchy) {
        this.hierarchy = hierarchy;
    }

    /** An index with no classpath, for callers that only care about non-receiver differences. */
    public InvocationSignal() {
        this(HierarchyIndex.empty());
    }

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
        // A changed call count is a difference in what the method does: a duplicated or missing
        // call is exactly the defect this signal exists to find, and evaluation order does not
        // change how many calls are written.
        return ResolvedTargets.compare(id(), original.key(), severity(),
                callees(original), callees(rebuilt), hierarchy, Severity.HARD);
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
            out.add(canonicalNullCheck(insn.operand()));
        }
        return out;
    }

    /**
     * Collapses the two encodings of an implicit null check into one name.
     *
     * <p>When a qualifier has to be evaluated for its side effect but its result is discarded —
     * a static field read through an instance, or {@code outer.new Inner()} — the compiler inserts
     * a null check. The older compiler emitted
     * {@code aload x; invokevirtual Object.getClass; pop}; javac 17 emits
     * {@code aload x; invokestatic Objects.requireNonNull; pop}. Same guard, same stack effect,
     * different generation.
     *
     * <p>This was one of the most common false positives on the validation corpus, appearing in
     * classes whose only difference was this substitution.
     */
    static String canonicalNullCheck(String callee) {
        if (callee.equals("java/lang/Object.getClass()Ljava/lang/Class;")
                || callee.startsWith("java/util/Objects.requireNonNull(")) {
            return "java/lang/Object.nullCheck()V";
        }
        return callee;
    }

    static boolean isConcatenationTraffic(String resolvedCallee) {
        for (String owner : CONCATENATION_OWNERS) {
            if (resolvedCallee.startsWith(owner + ".")) {
                return true;
            }
        }
        return false;
    }
}
