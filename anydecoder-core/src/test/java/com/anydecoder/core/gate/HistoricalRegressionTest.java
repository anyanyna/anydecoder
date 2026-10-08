package com.anydecoder.core.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against the four defects the original javap-scraping gate shipped with.
 *
 * <p>Each test names the bug it encodes and states what the old implementation did. These are
 * not hypotheticals: two of them were silent, meaning the gate reported a pass on code it had
 * never actually compared.
 *
 * <p>Every class here is emitted from the same instruction sequence. Only the exception table
 * differs, so a failing assertion cannot be explained by anything else.
 */
class HistoricalRegressionTest {

    /** A catch-all row as ASM spells it: a null type means "any", i.e. a finally handler. */
    private static final String CATCH_ALL = "<any>";

    private final ClassGate gate = new ClassGate();

    private ClassVerdict compare(byte[] original, byte[] rebuilt) {
        return gate.compare(ClassModelReader.read(original), ClassModelReader.read(rebuilt));
    }

    // ------------------------------------------------------------------------------------------
    // The original parser read field 3 of an exception-table row, which is the literal token
    // "Class"; the caught type is field 4. Every row therefore appeared to have the same type,
    // and the signal degenerated into comparing row counts. Swapping a handler's type for an
    // unrelated one while keeping the row count the same reported "HARD FAIL: 0".
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("B1: a changed handler type is caught even when the row count is unchanged")
    void changedHandlerTypeIsDetected() {
        ClassVerdict verdict = compare(
                guarded("sample/A", "java/io/IOException", false, CATCH_ALL),
                guarded("sample/A", "java/lang/Exception", false, CATCH_ALL));

        assertEquals(Verdict.DAMAGED, verdict.verdict(),
                "an IOException handler rewritten as Exception changes which exceptions are caught");
        assertReportedBy(verdict, "excp");
    }

    // ------------------------------------------------------------------------------------------
    // javap lists members in class-file order and the two compilers disagree about where a
    // constructor sits. Comparing the ordered listing made a class look like an API change when
    // the member sets were identical.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("B2: member declaration order is not an API difference")
    void memberOrderIsNotAnApiDifference() {
        assertEquals(Verdict.EQUIVALENT,
                compare(membersInOrder("sample/B", "first", "second"),
                        membersInOrder("sample/B", "second", "first")).verdict(),
                "member order carries no semantics; the declared set is identical");
    }

    // ------------------------------------------------------------------------------------------
    // ecj and javac segment a protected range differently: ecj excludes trailing instructions
    // that cannot throw, so it emits more rows for the same protected code. Comparing the row
    // lists reported 39 phantom defects among 42, hiding the 3 real ones.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("B3: different row segmentation of the same protected code is not a defect")
    void segmentSplitOfTheSameProtectedCodeIsNotADefect() {
        assertEquals(Verdict.EQUIVALENT,
                compare(guarded("sample/C", "java/io/IOException", false, CATCH_ALL),
                        guarded("sample/C", "java/io/IOException", true, CATCH_ALL)).verdict(),
                "both tables protect the same throwing instructions; only the row boundaries differ");
    }

    @Test
    @DisplayName("B3: the same tolerance must not also excuse a different handler type")
    void segmentationToleranceDoesNotHideAChangedHandler() {
        assertNotEquals(Verdict.EQUIVALENT,
                compare(guarded("sample/C", "java/io/IOException", false, CATCH_ALL),
                        guarded("sample/C", "java/lang/IllegalStateException", false, CATCH_ALL)).verdict(),
                "a different handler type is a different table, not a segmentation artefact");
    }

    // ------------------------------------------------------------------------------------------
    // The parser required five whitespace-separated fields per row, but a finally handler is
    // rendered with four (its type is literally "any"). Every finally handler was silently
    // discarded -- 245 of them in a 300-class sample -- so a source that had dropped its
    // finally compared equal to the original.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("B4: a dropped finally handler is caught")
    void droppedFinallyHandlerIsDetected() {
        ClassVerdict verdict = compare(
                guarded("sample/D", "java/io/IOException", false, CATCH_ALL),
                guarded("sample/D", "java/io/IOException", false, null));

        assertEquals(Verdict.DAMAGED, verdict.verdict(),
                "losing a finally changes what runs on the exception path");
        assertReportedBy(verdict, "excp");
    }

    @Test
    @DisplayName("B4: a catch-all handler and an explicit catch of Throwable are the same thing")
    void catchAllEqualsExplicitThrowable() {
        assertEquals(Verdict.EQUIVALENT,
                compare(guarded("sample/E", "java/io/IOException", false, CATCH_ALL),
                        guarded("sample/E", "java/io/IOException", false, "java/lang/Throwable")).verdict(),
                "ecj writes a catch-all where javac can write catch (Throwable)");
    }

    // ------------------------------------------------------------------------------------------

    private static void assertReportedBy(ClassVerdict verdict, String signalId) {
        assertTrue(verdict.unexplained().stream().anyMatch(d -> d.signalId().equals(signalId)),
                "expected the '" + signalId + "' signal to report this, got " + verdict.unexplained());
    }

    /**
     * One class, one method {@code int f(Object)}: two calls to {@code hashCode()} with a
     * non-throwing {@code nop} between them, and a handler that returns 0.
     *
     * @param handlerType the caught type for the ordinary rows
     * @param split       emit the protected range as two rows instead of one, the shape ecj
     *                    produces when it excludes a non-throwing instruction at the end
     * @param finallyType {@link #CATCH_ALL} for a catch-all row, an internal name for an
     *                    explicit {@code catch (Throwable)}, or null for no such row
     */
    private static byte[] guarded(String name, String handlerType, boolean split, String finallyType) {
        return TestBytecode.clazz(name, cw -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "(Ljava/lang/Object;)I", null, null);
            mv.visitCode();

            Label start = new Label();
            Label afterFirst = new Label();
            Label secondStart = new Label();
            Label secondEnd = new Label();
            Label handler = new Label();

            if (split) {
                mv.visitTryCatchBlock(start, afterFirst, handler, handlerType);
                mv.visitTryCatchBlock(secondStart, secondEnd, handler, handlerType);
            } else {
                mv.visitTryCatchBlock(start, secondEnd, handler, handlerType);
            }
            if (finallyType != null) {
                mv.visitTryCatchBlock(start, secondEnd, handler,
                        CATCH_ALL.equals(finallyType) ? null : finallyType);
            }

            mv.visitLabel(start);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(afterFirst);
            mv.visitInsn(Opcodes.NOP);
            mv.visitLabel(secondStart);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(secondEnd);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IRETURN);

            mv.visitLabel(handler);
            mv.visitVarInsn(Opcodes.ASTORE, 2);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.IRETURN);

            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
    }

    private static byte[] membersInOrder(String name, String first, String second) {
        return TestBytecode.clazz(name, (ClassWriter cw) -> {
            cw.visitField(Opcodes.ACC_PRIVATE, first, "I", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PRIVATE, second, "I", null, null).visitEnd();
            TestBytecode.method(cw, first, "()I", mv -> {
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IRETURN);
            });
            TestBytecode.method(cw, second, "()I", mv -> {
                mv.visitInsn(Opcodes.ICONST_2);
                mv.visitInsn(Opcodes.IRETURN);
            });
        });
    }
}
