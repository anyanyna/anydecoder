package com.anydecoder.core.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The class-level half of the gate.
 *
 * <p>The first test here exists because of a real bug: class-level divergences were collected
 * separately from method-level ones and never reached the "unexplained" set, so a class whose
 * only problem was a changed API came out {@code BENIGN} — reported as safe. Nothing else caught
 * it, because a class with a changed signature and no other difference looks exactly like a class
 * with an explained compiler artefact from the outside.
 */
class ClassGateTest {

    private final ClassGate gate = new ClassGate();

    private ClassVerdict compare(byte[] original, byte[] rebuilt) {
        return gate.compare(ClassModelReader.read(original), ClassModelReader.read(rebuilt));
    }

    @Test
    @DisplayName("a missing field is damage, not a compiler artefact")
    void missingFieldIsDamage() {
        byte[] original = withField("sample/F", "kept", "I");
        byte[] rebuilt = withField("sample/F", "renamed", "I");

        ClassVerdict verdict = compare(original, rebuilt);

        assertEquals(Verdict.DAMAGED, verdict.verdict());
        assertTrue(verdict.unexplained().stream().anyMatch(d -> d.signalId().equals("api")),
                "class-level differences must not be routed through the benign classifier");
    }

    @Test
    @DisplayName("a changed field type is damage")
    void changedFieldTypeIsDamage() {
        assertEquals(Verdict.DAMAGED,
                compare(withField("sample/F", "value", "I"),
                        withField("sample/F", "value", "J")).verdict());
    }

    @Test
    @DisplayName("a changed visibility is damage")
    void changedVisibilityIsDamage() {
        byte[] original = withField("sample/F", "value", "I");
        byte[] rebuilt = TestBytecode.clazz("sample/F", cw ->
                cw.visitField(Opcodes.ACC_PUBLIC, "value", "I", null, null).visitEnd());

        assertEquals(Verdict.DAMAGED, compare(original, rebuilt).verdict(),
                "widening a field from private to public changes what callers can do");
    }

    @Test
    @DisplayName("a method the rebuild does not produce is damage")
    void missingMethodIsDamage() {
        byte[] original = withMethod("sample/M", true);
        byte[] rebuilt = withMethod("sample/M", false);

        ClassVerdict verdict = compare(original, rebuilt);

        assertEquals(Verdict.DAMAGED, verdict.verdict());
        assertTrue(verdict.unexplained().stream().anyMatch(d -> d.signalId().equals("members")));
    }

    @Test
    @DisplayName("an identical class is equivalent")
    void identicalIsEquivalent() {
        assertEquals(Verdict.EQUIVALENT,
                compare(withField("sample/F", "value", "I"), withField("sample/F", "value", "I")).verdict());
    }

    @Test
    @DisplayName("a class with only advisory differences is still equivalent")
    void advisoryOnlyIsEquivalent() {
        // A rebuild that declares more locals is the LVT advisory firing and nothing else.
        assertEquals(Verdict.EQUIVALENT,
                compare(locals("sample/L", 0), locals("sample/L", 1)).verdict(),
                "an advisory divergence must never move a verdict");
    }

    // ------------------------------------------------------------------------------------------

    private static byte[] withField(String name, String fieldName, String descriptor) {
        return TestBytecode.clazz(name, cw ->
                cw.visitField(Opcodes.ACC_PRIVATE, fieldName, descriptor, null, null).visitEnd());
    }

    private static byte[] withMethod(String name, boolean includeExtra) {
        return TestBytecode.clazz(name, cw -> {
            TestBytecode.method(cw, "kept", "()I", mv -> {
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IRETURN);
            });
            if (includeExtra) {
                TestBytecode.method(cw, "extra", "()I", mv -> {
                    mv.visitInsn(Opcodes.ICONST_2);
                    mv.visitInsn(Opcodes.IRETURN);
                });
            }
        });
    }

    /**
     * A class whose single method declares {@code extraLocals} additional locals beyond the
     * receiver. Only the LocalVariableTable changes, so only the advisory signal can fire.
     */
    private static byte[] locals(String name, int extraLocals) {
        return TestBytecode.clazz(name, cw -> {
            var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "f", "(Ljava/lang/Object;)I", null, null);
            mv.visitCode();
            var start = new org.objectweb.asm.Label();
            var end = new org.objectweb.asm.Label();
            mv.visitLabel(start);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitLabel(end);
            for (int i = 0; i < extraLocals; i++) {
                mv.visitLocalVariable("extra" + i, "I", null, start, end, 2 + i);
            }
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
    }
}
