package com.anydecoder.core.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A field stored in each arm of a conditional by one compiler and once where the branches meet by
 * the other.
 *
 * <p>Whether that is observable depends on where the <em>reads</em> are, which is the one thing
 * the instruction multiset cannot say. The third test here is the case that matters most: a read
 * before every store is the ordinary shape of {@code this.x = this.x == null ? a : b}, and a rule
 * that asks "was a store definitely executed yet" gets it wrong.
 */
class FieldStoreHoistingTest {

    private static final String FIELD = "sample/T.x:I";

    @Test
    @DisplayName("stores in each arm against one at the join, with nothing reading them")
    void excusesStoresSplitAcrossBranches() {
        // 0 iconst | 1 ifeq 4 | 2 aload | 3 putfield | 4 aload | 5 putfield | 6 return
        MethodModel original = flow("plain", "ifeq:4", "aload", "putfield", "aload", "putfield",
                "plain");
        // 0 iconst | 1 ifeq 3 | 2 aload | 3 aload | 4 putfield | 5 return
        MethodModel rebuilt = flow("plain", "ifeq:3", "aload", "aload", "putfield", "plain");

        assertTrue(classify(original, rebuilt).explained(),
                "nothing reads the field, so which arm stores it cannot be seen");
    }

    @Test
    @DisplayName("a read between two stores is not excused")
    void refusesWhenAReadCanSeeAnIntermediateStore() {
        // 0 aload | 1 putfield | 2 aload | 3 getfield | 4 plain | 5 aload | 6 putfield | 7 plain
        MethodModel original = flow("aload", "putfield", "aload", "getfield", "plain",
                "aload", "putfield", "plain");
        MethodModel rebuilt = flow("aload", "getfield", "plain", "aload", "putfield", "plain");

        assertEquals(List.of("fields"), classify(original, rebuilt).unexplained(),
                "the read after the first store sees a value the second one overwrites");
    }

    @Test
    @DisplayName("a read before every store is excused")
    void excusesAReadThatPrecedesEveryStore() {
        // 0 aload | 1 getfield | 2 ifnull 6 | 3 aload | 4 putfield | 5 goto 8
        // 6 aload | 7 putfield | 8 plain
        MethodModel original = flow("aload", "getfield", "ifnull:6", "aload", "putfield", "goto:8",
                "aload", "putfield", "plain");
        MethodModel rebuilt = flow("aload", "getfield", "ifnull:4", "aload", "aload", "putfield",
                "plain");

        assertTrue(classify(original, rebuilt).explained(),
                "this is `x = x == null ? a : b`: the read happens before either store, so no"
                        + " intermediate value can be observed");
    }

    @Test
    @DisplayName("a store that is added rather than removed is not excused")
    void refusesWhenTheRebuildStoresMoreOften() {
        MethodModel original = flow("aload", "putfield", "plain");
        MethodModel rebuilt = flow("aload", "putfield", "aload", "putfield", "plain");

        assertEquals(List.of("fields"), classify(original, rebuilt).unexplained(),
                "only a reduction is characteristic of merging");
    }

    // ------------------------------------------------------------------------------------------

    private record Result(boolean explained, List<String> unexplained) {
    }

    private static Result classify(MethodModel original, MethodModel rebuilt) {
        ClassModel empty = new ClassModel(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/T", null,
                List.of(), List.of(), List.of());
        Divergence divergence = new Divergence("fields", original.key(), Severity.SUSPECT,
                "a difference", Map.of());
        BenignDivergenceClassifier.Result result = BenignDivergenceClassifier.classify(
                empty, empty, original, rebuilt, HierarchyIndex.empty(), List.of(divergence));
        return new Result(result.explained(),
                result.unexplained().stream().map(Divergence::signalId).toList());
    }

    /**
     * A method from a compact description: {@code ifeq:<index>} and {@code goto:<index>} are
     * branches to an instruction index, {@code putfield} and {@code getfield} touch the field
     * under test, and anything else is a plain instruction that exists to occupy a position.
     */
    private static MethodModel flow(String... described) {
        List<Insn> instructions = new ArrayList<>();
        for (String item : described) {
            String name = item.contains(":") ? item.substring(0, item.indexOf(':')) : item;
            int target = item.contains(":")
                    ? Integer.parseInt(item.substring(item.indexOf(':') + 1))
                    : Insn.NO_TARGET;
            switch (name) {
                // `goto` is a real GOTO, not a conditional with the same target: a conditional
                // falls through to the next instruction as well, which would make two stores in
                // separate arms reach each other and quietly change what the test is exercising.
                case "goto" -> instructions.add(
                        new Insn(Opcodes.GOTO, "goto", "", target, false));
                case "ifeq", "ifnull" -> instructions.add(
                        new Insn(Opcodes.IFEQ, name, "", target, false));
                case "putfield" -> instructions.add(
                        new Insn(Opcodes.PUTFIELD, "putfield", FIELD, false));
                case "getfield" -> instructions.add(
                        new Insn(Opcodes.GETFIELD, "getfield", FIELD, false));
                case "aload" -> instructions.add(new Insn(Opcodes.ALOAD, "aload", "", false));
                default -> instructions.add(new Insn(Opcodes.NOP, "nop", "", false));
            }
        }
        return new MethodModel(Opcodes.ACC_PUBLIC, "f", "()V", null, List.of(),
                List.copyOf(instructions), List.of(), List.of());
    }
}