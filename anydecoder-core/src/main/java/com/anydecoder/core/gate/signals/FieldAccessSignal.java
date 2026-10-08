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
 * Compares which fields are read and written, and through which opcode.
 *
 * <p>Distinguishing {@code getstatic} from {@code getfield} matters: turning an instance read
 * into a static one compiles, and changes which storage is touched. The original gate had no
 * field signal at all; reading structured bytecode makes it free.
 *
 * <p>Receiver owners go through the same hierarchy check as calls, and need it for the same
 * reason: a field reached through a narrowed declared type is the same field. This signal
 * accounted for 132 findings on source verified faithful by hand before the check was added.
 */
public final class FieldAccessSignal implements Signal {

    private final HierarchyIndex hierarchy;

    public FieldAccessSignal(HierarchyIndex hierarchy) {
        this.hierarchy = hierarchy;
    }

    public FieldAccessSignal() {
        this(HierarchyIndex.empty());
    }

    @Override
    public String id() {
        return "fields";
    }

    @Override
    public Severity severity() {
        return Severity.HARD;
    }

    @Override
    public Optional<Divergence> compare(MethodModel original, MethodModel rebuilt) {
        // A field touched on one side only is a hard difference. The same field touched a
        // different number of times is reported as SUSPECT instead, because the common cause is
        // the compiler duplicating a store into each arm of a conditional that assigns one
        // field -- ecj does this for `this.x = cond ? a : b;` where javac emits a single
        // putfield after the branches join. That is benign, but proving it needs the control
        // flow, so it is surfaced for review rather than excused.
        return ResolvedTargets.compare(id(), original.key(), severity(),
                accesses(original), accesses(rebuilt), hierarchy, Severity.SUSPECT);
    }

    private static List<String> accesses(MethodModel method) {
        List<String> out = new ArrayList<>();
        for (Insn insn : method.instructions()) {
            int op = insn.opcode();
            if (op == Opcodes.GETFIELD || op == Opcodes.PUTFIELD
                    || op == Opcodes.GETSTATIC || op == Opcodes.PUTSTATIC) {
                out.add(insn.operand());
            }
        }
        return out;
    }
}
