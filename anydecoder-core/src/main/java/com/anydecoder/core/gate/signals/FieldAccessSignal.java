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
 * Compares which fields are read and written, and through which opcode.
 *
 * <p>Distinguishing {@code getstatic} from {@code getfield} matters: turning an instance read
 * into a static one compiles, and changes which storage is touched. The original gate had no
 * field signal at all; reading structured bytecode makes it free.
 */
public final class FieldAccessSignal implements Signal {

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
        Map<String, Integer> a = Multisets.counts(accesses(original), s -> s);
        Map<String, Integer> b = Multisets.counts(accesses(rebuilt), s -> s);
        if (a.equals(b)) {
            return Optional.empty();
        }
        return Optional.of(Divergence
                .of(id(), original.key(), severity(), Multisets.describeDiff(a, b, 6))
                .withEvidence("original", a.toString())
                .withEvidence("rebuilt", b.toString()));
    }

    private static List<String> accesses(MethodModel method) {
        List<String> out = new ArrayList<>();
        for (Insn insn : method.instructions()) {
            int op = insn.opcode();
            if (op == Opcodes.GETFIELD || op == Opcodes.PUTFIELD
                    || op == Opcodes.GETSTATIC || op == Opcodes.PUTSTATIC) {
                out.add(insn.mnemonic() + " " + insn.operand());
            }
        }
        return out;
    }
}
