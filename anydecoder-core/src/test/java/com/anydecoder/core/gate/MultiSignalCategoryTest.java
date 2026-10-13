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
 * The categories that account for more than one signal at once.
 *
 * <p>Each of these exists because a single compiler behaviour shows up as several signals, and
 * reasoning about the signals one at a time meant investigating one cause several times. They are
 * also the categories most able to hide a real difference, so each test here pairs the case it
 * should excuse with a case it must not.
 */
class MultiSignalCategoryTest {

    private static final String APPEND =
            "java/lang/StringBuilder.append(Ljava/lang/String;)Ljava/lang/StringBuilder;";

    @Test
    @DisplayName("two adjacent literals folded into one are excused")
    void excusesFoldedStringLiterals() {
        MethodModel original = method("ldc:a", "invoke:" + APPEND,
                "ldc:b", "invoke:" + APPEND, "plain");
        MethodModel rebuilt = method("ldc:ab", "invoke:" + APPEND, "plain");

        Result result = classify(original, rebuilt, "consts");

        assertTrue(result.explained(), "the concatenated string is the same either way");
        assertEquals("STRING_LITERAL_FOLDING", result.categories().get(0));
    }

    @Test
    @DisplayName("a folded literal is not excused when the instruction count does not fit")
    void refusesFoldingWhenTheCountDoesNotAddUp() {
        MethodModel original = method("ldc:a", "invoke:" + APPEND,
                "ldc:b", "invoke:" + APPEND, "plain");
        // Two instructions short of what one fold accounts for; something else is going on.
        MethodModel rebuilt = method("ldc:ab", "invoke:" + APPEND);

        Result result = classify(original, rebuilt, "consts");

        assertEquals(List.of("consts"), result.unexplained(),
                "the arithmetic is the evidence; without it the literals merely look plausible");
    }

    @Test
    @DisplayName("an appended empty literal is excused, and the arithmetic still has to fit")
    void excusesAnAppendedEmptyLiteral() {
        MethodModel original = method("ldc:a", "invoke:" + APPEND, "plain");
        MethodModel rebuilt = method("ldc:", "invoke:" + APPEND,
                "ldc:a", "invoke:" + APPEND, "plain");

        assertTrue(classify(original, rebuilt, "consts").explained(),
                "append(\"\") cannot change the resulting string");
    }

    @Test
    @DisplayName("a changed literal value is still a difference")
    void doesNotExcuseAChangedLiteral() {
        MethodModel original = method("ldc:a", "invoke:" + APPEND, "plain");
        MethodModel rebuilt = method("ldc:b", "invoke:" + APPEND, "plain");

        assertEquals(List.of("consts"), classify(original, rebuilt, "consts").unexplained(),
                "a merge cannot account for this, so it stays a finding");
    }

    @Test
    @DisplayName("boxing is excused alongside a receiver narrowed by hierarchy")
    void excusesBoxingAlongsideANarrowedReceiver() {
        // The call targets differ only in owner -- List against ArrayList -- which the hierarchy
        // accounts for, and then in box/unbox traffic. Comparing the call lists as raw strings
        // counted the narrowed receiver as a difference and the rule gave up on the class.
        MethodModel original = method(
                "invoke:java/util/List.add(Ljava/lang/Object;)Z",
                "invoke:java/util/List.add(Ljava/lang/Object;)Z",
                "invoke:java/lang/Integer.intValue()I",
                "plain");
        MethodModel rebuilt = method(
                "invoke:java/util/ArrayList.add(Ljava/lang/Object;)Z",
                "invoke:java/util/ArrayList.add(Ljava/lang/Object;)Z",
                "invoke:java/lang/Integer.intValue()I",
                "invoke:java/lang/Integer.intValue()I",
                "plain");

        Result result = classify(original, rebuilt, "invokes");

        assertTrue(result.explained(),
                "the only unpaired call is an extra unbox, and every other call lines up");
        assertEquals("BOXING_MATERIALIZATION", result.categories().get(0));
    }

    @Test
    @DisplayName("boxing is not excused when a real call moved as well")
    void doesNotExcuseBoxingThatHidesAMovedCall() {
        MethodModel original = method(
                "invoke:java/util/List.add(Ljava/lang/Object;)Z",
                "invoke:com/example/Service.run()V",
                "plain");
        MethodModel rebuilt = method(
                "invoke:java/util/ArrayList.add(Ljava/lang/Object;)Z",
                "invoke:java/lang/Integer.intValue()I",
                "plain");

        assertEquals(List.of("invokes"),
                classify(original, rebuilt, "invokes").unexplained(),
                "a call genuinely disappeared; boxing must not account for that");
    }

    // ------------------------------------------------------------------------------------------

    private record Result(boolean explained, List<String> categories, List<String> unexplained) {
    }

    private static Result classify(MethodModel original, MethodModel rebuilt, String signal) {
        ClassModel empty = new ClassModel(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/T", null,
                List.of(), List.of(), List.of());
        Divergence divergence = new Divergence(signal, original.key(), Severity.HARD, "a difference",
                Map.of());
        BenignDivergenceClassifier.Result result = BenignDivergenceClassifier.classify(
                empty, empty, original, rebuilt, HierarchyIndex.empty(), List.of(divergence));
        return new Result(result.explained(),
                result.explanations().stream().map(BenignDivergenceClassifier.Explanation::category).toList(),
                result.unexplained().stream().map(Divergence::signalId).toList());
    }

    /**
     * A method from a compact description of its instructions.
     *
     * <p>{@code ldc:<value>} is a pool load; {@code invoke:<callee>} a call; anything else a plain
     * instruction that exists to move the instruction count by one.
     */
    private static MethodModel method(String... described) {
        List<Insn> instructions = new ArrayList<>();
        for (String item : described) {
            if (item.startsWith("ldc:")) {
                instructions.add(new Insn(Opcodes.LDC, "ldc", "String:" + item.substring(4), false));
            } else if (item.startsWith("invoke:")) {
                instructions.add(new Insn(Opcodes.INVOKEVIRTUAL, "invokevirtual",
                        item.substring("invoke:".length()), true));
            } else {
                instructions.add(new Insn(Opcodes.NOP, "nop", "", false));
            }
        }
        return new MethodModel(Opcodes.ACC_PUBLIC, "f", "()V", null, List.of(),
                List.copyOf(instructions), List.of(), List.of());
    }
}