package com.anydecoder.core.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exception-table reduction, tested directly because it is what makes 39 of 42 phantom hard
 * failures disappear without hiding the three real ones.
 *
 * <p>The tests use a body of plain instructions and mark which indices can throw. That is enough
 * to exercise the two decisions the normaliser makes: drop rows that protect nothing that can
 * throw, and count disjoint protected groups per handler type rather than comparing row lists.
 */
class ExceptionTableNormalizerTest {

    @Test
    @DisplayName("ecj's fragmented range and javac's continuous range reduce to the same group")
    void fragmentedAndContinuousRangesNormaliseTheSame() {
        List<Insn> body = body(70, 5, 20, 40);

        // The measured case from the class comment: the extra ecj rows cover an ireturn and a
        // goto, neither of which can throw.
        List<ExceptionEntry> ecj = List.of(
                new ExceptionEntry(0, 13, 100, "java/lang/Exception"),
                new ExceptionEntry(14, 61, 100, "java/lang/Exception"),
                new ExceptionEntry(62, 65, 100, "java/lang/Exception"));
        List<ExceptionEntry> javac = List.of(
                new ExceptionEntry(0, 13, 100, "java/lang/Exception"),
                new ExceptionEntry(14, 64, 100, "java/lang/Exception"));

        assertEquals(List.of(new ExceptionTableNormalizer.TypeGroup("java/lang/Exception", 1)),
                ExceptionTableNormalizer.normalize(ecj, body));
        assertEquals(ExceptionTableNormalizer.normalize(ecj, body),
                ExceptionTableNormalizer.normalize(javac, body),
                "the two compilers protected the same code; only their row boundaries differ");
    }

    @Test
    @DisplayName("a row that protects no throwing instruction is dropped")
    void dropsRowsThatProtectNothing() {
        List<Insn> body = body(10, 2);

        List<ExceptionTableNormalizer.TypeGroup> result = ExceptionTableNormalizer.normalize(
                List.of(new ExceptionEntry(4, 8, 100, "java/lang/Exception")), body);

        assertTrue(result.isEmpty(), "nothing in that range can throw, so the row means nothing");
    }

    @Test
    @DisplayName("a row that protects one throwing instruction is kept")
    void keepsRowsThatProtectAThrowingInstruction() {
        List<Insn> body = body(10, 2);

        assertEquals(List.of(new ExceptionTableNormalizer.TypeGroup("java/lang/Exception", 1)),
                ExceptionTableNormalizer.normalize(
                        List.of(new ExceptionEntry(0, 3, 100, "java/lang/Exception")), body));
    }

    @Test
    @DisplayName("a dropped handler remains visible, because the type is part of the result")
    void keepsAChangedHandlerTypeVisible() {
        List<Insn> body = body(10, 2, 7);

        List<ExceptionTableNormalizer.TypeGroup> one = ExceptionTableNormalizer.normalize(
                List.of(new ExceptionEntry(0, 10, 100, "java/lang/Exception")), body);
        List<ExceptionTableNormalizer.TypeGroup> two = ExceptionTableNormalizer.normalize(
                List.of(new ExceptionEntry(0, 3, 100, "java/lang/Exception"),
                        new ExceptionEntry(3, 10, 200, "java/lang/Error")), body);

        assertEquals(1, one.size());
        assertEquals(2, two.size(), "a second handler is a real difference and must survive");
    }

    @Test
    @DisplayName("siblings of one type merge even when another type sits between them")
    void mergesOneTypeAcrossOtherTypes() {
        List<Insn> body = body(12, 1, 7);

        // Table order interleaves Exception, Error, Exception. The two Exception rows protect
        // contiguous code (nothing can throw at 5), so they are one group. Counting table order
        // would see two.
        List<ExceptionEntry> table = List.of(
                new ExceptionEntry(0, 5, 100, "java/lang/Exception"),
                new ExceptionEntry(0, 5, 200, "java/lang/Error"),
                new ExceptionEntry(6, 10, 100, "java/lang/Exception"));

        assertEquals(List.of(
                        new ExceptionTableNormalizer.TypeGroup("java/lang/Error", 1),
                        new ExceptionTableNormalizer.TypeGroup("java/lang/Exception", 1)),
                ExceptionTableNormalizer.normalize(table, body));
    }

    @Test
    @DisplayName("an any handler and catch(Throwable) are the same handler")
    void treatsAnyAndThrowableAsTheSameType() {
        List<Insn> body = body(8, 2);

        List<ExceptionTableNormalizer.TypeGroup> result = ExceptionTableNormalizer.normalize(
                List.of(new ExceptionEntry(0, 5, 100, null),
                        new ExceptionEntry(0, 5, 100, "java/lang/Throwable")), body);

        assertEquals(List.of(new ExceptionTableNormalizer.TypeGroup(ExceptionEntry.ANY, 1)), result,
                "ecj writes the catch-all, javac may write Throwable; they mean the same thing");
    }

    @Test
    @DisplayName("javac's finally self-protection row says nothing about the source")
    void ignoresSelfProtectionRows() {
        List<Insn> body = body(12, 2);

        List<ExceptionTableNormalizer.TypeGroup> result = ExceptionTableNormalizer.normalize(
                List.of(new ExceptionEntry(0, 5, 100, "java/lang/Exception"),
                        new ExceptionEntry(5, 10, 5, ExceptionEntry.ANY)), body);

        assertEquals(List.of(new ExceptionTableNormalizer.TypeGroup("java/lang/Exception", 1)),
                result, "the row where start == handler only protects the handler itself");
    }

    @Test
    @DisplayName("an empty table stays empty")
    void handlesAnEmptyTable() {
        assertTrue(ExceptionTableNormalizer.normalize(List.of(), body(5)).isEmpty());
    }

    private static List<Insn> body(int size, int... throwingIndices) {
        Set<Integer> throwing = new HashSet<>();
        for (int index : throwingIndices) {
            throwing.add(index);
        }
        List<Insn> instructions = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            boolean canThrow = throwing.contains(i);
            instructions.add(new Insn(canThrow ? 191 : 0,
                    canThrow ? "athrow" : "nop", "", canThrow));
        }
        return instructions;
    }
}
