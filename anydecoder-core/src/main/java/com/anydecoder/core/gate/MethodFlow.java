package com.anydecoder.core.gate;

import org.objectweb.asm.Opcodes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.List;

/**
 * The control-flow graph of one method.
 *
 * <h2>Why the gate needs it</h2>
 *
 * <p>Some differences cannot be judged from the instruction multiset alone. The clearest is a
 * field assigned once per arm of a conditional by one compiler and once after the join by the
 * other: the counts differ, the values do not, and whether that is observable depends entirely on
 * whether anything <em>reads</em> the field between the two stores.
 *
 * <p>Answering that needs to know which instructions can be reached from which others, which is
 * what this provides. It is the difference between excusing a divergence on a plausible story and
 * excusing it because the intermediate value provably cannot be seen.
 *
 * <h2>What it is not used for</h2>
 *
 * <p>Never for the comparison itself. Branch targets move between compilers — that is why
 * {@link Insn#render()} leaves them out — so a signal that looked at them would report every
 * method as different. This is analysis on the side, after the signals have had their say.
 */
public final class MethodFlow {

    private final List<Insn> instructions;

    public MethodFlow(MethodModel method) {
        this.instructions = method.instructions();
    }

    /**
     * The instructions reachable from {@code from}'s successors without passing through
     * {@code blocked}.
     *
     * <p>This answers the question a simple "is a store visible here" test cannot. A read that
     * happens before every store — the ordinary shape of {@code this.x = this.x == null ? a : b} —
     * is fine and is reached by no store at all. What matters is a read reachable from one store
     * without passing through the last one, because that read is seeing a value a later store is
     * about to overwrite.
     *
     * @param from    the instruction whose successors to start from
     * @param blocked an instruction to treat as removed, or {@link Insn#NO_TARGET} for none
     */
    public BitSet reachableFrom(int from, int blocked) {
        BitSet seen = new BitSet(instructions.size());
        if (from < 0 || from >= instructions.size()) {
            return seen;
        }
        Deque<Integer> pending = new ArrayDeque<>();
        for (int next : successors(from)) {
            if (next != blocked) {
                pending.add(next);
            }
        }
        while (!pending.isEmpty()) {
            int node = pending.poll();
            if (seen.get(node)) {
                continue;
            }
            seen.set(node);
            for (int next : successors(node)) {
                if (next != blocked && !seen.get(next)) {
                    pending.add(next);
                }
            }
        }
        return seen;
    }

    public int size() {
        return instructions.size();
    }

    /** The indices control can move to from {@code i}. */
    private List<Integer> successors(int i) {
        Insn insn = instructions.get(i);
        int op = insn.opcode();
        List<Integer> out = new ArrayList<>(2);

        for (int target : insn.targets()) {
            if (target >= 0 && target < instructions.size()) {
                out.add(target);
            }
        }

        // An unconditional transfer has no fall-through; everything else continues to the next
        // instruction as well as to its branch destinations.
        boolean fallsThrough = op != Opcodes.GOTO && op != Opcodes.JSR
                && op != Opcodes.RETURN && op != Opcodes.IRETURN && op != Opcodes.LRETURN
                && op != Opcodes.FRETURN && op != Opcodes.DRETURN && op != Opcodes.ARETURN
                && op != Opcodes.ATHROW && op != Opcodes.RET && op != Opcodes.TABLESWITCH
                && op != Opcodes.LOOKUPSWITCH;

        if (fallsThrough && i + 1 < instructions.size()) {
            out.add(i + 1);
        }
        return out;
    }
}