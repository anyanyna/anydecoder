package com.anydecoder.core.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The comparison of call and field targets across a class hierarchy.
 *
 * <p>Two things vary between two compilations of the same source and mean nothing:
 *
 * <ul>
 *   <li>a receiver declared as a subtype of what the source named — {@code ArrayList} where the
 *       source said {@code List}, which changes {@code invokeinterface} to
 *       {@code invokevirtual} on the same member;</li>
 *   <li>a covariant override — {@code CloseableHttpClient.execute} returns a subtype of
 *       {@code HttpClient.execute}'s return type, and is the same method.</li>
 * </ul>
 *
 * <p>Taking either literally is not a small error. On source already verified faithful by hand,
 * the first produced 1325 findings and the second another 57.
 */
class HierarchyAwareComparisonTest {

    @Test
    @DisplayName("a receiver narrowed within its hierarchy is the same call")
    void narrowedReceiverIsTheSameCall(@TempDir Path work) throws Exception {
        byte[] original = callerOn("sample/impl/Concrete", "sample/Service", "sample/Caller");
        byte[] rebuilt = callerOn("sample/impl/Concrete", "sample/impl/Concrete", "sample/Caller");

        ClassVerdict verdict = gateOver(work, original, rebuilt);

        assertEquals(Verdict.EQUIVALENT, verdict.verdict(),
                "Service and impl/Concrete are the same hierarchy, so the method reached is the"
                        + " same one");
    }

    @Test
    @DisplayName("a call on an unrelated type is still a difference")
    void unrelatedReceiverIsADifference(@TempDir Path work) throws Exception {
        // Both types declare `run()`, so the signature matches; only the class differs.
        byte[] original = callerOn("sample/Service", "sample/Service", "sample/Caller");
        byte[] rebuilt = callerOn("sample/Other", "sample/Other", "sample/Caller");

        ClassVerdict verdict = gateOver(work, original, rebuilt);

        assertEquals(Verdict.DAMAGED, verdict.verdict(),
                "an unrelated class with a same-shaped method is a different method");
    }

    @Test
    @DisplayName("a covariant return type is the same method")
    void covariantReturnTypeIsTheSameMethod(@TempDir Path work) throws Exception {
        byte[] original = callerReturning("sample/Service", "sample/Covariant",
                "sample/Base", "sample/Derived");
        byte[] rebuilt = callerReturning("sample/Covariant", "sample/Covariant",
                "sample/Derived", "sample/Derived");

        ClassVerdict verdict = gateOver(work, original, rebuilt);

        assertEquals(Verdict.EQUIVALENT, verdict.verdict(),
                "Covariant.get() returns Derived, which is a Base; invoking it through the"
                        + " concrete type names the override, not another method");
    }

    // ------------------------------------------------------------------------------------------

    /**
     * A class whose single method calls {@code receiver.target()} where the receiver's declared
     * type is given, so only the owner of the call changes between the two versions.
     */
    private static byte[] callerOn(String declaredType, String targetOwner, String name) {
        return TestBytecode.clazz(name, cw -> {
            TestBytecode.method(cw, "call", "()V", mv -> {
                mv.visitTypeInsn(Opcodes.NEW, declaredType);
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, declaredType, "<init>", "()V", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, targetOwner,
                        "target", "()V", declaredType.startsWith("sample/impl"));
                mv.visitInsn(Opcodes.RETURN);
            });
        });
    }

    /**
     * A class whose method calls {@code owner.get()} and whose bytecode records {@code resultType}
     * as the call's return type — the covariant case, where the override returns a subtype.
     */
    private static byte[] callerReturning(String declaredType, String owner,
                                          String resultType, String name) {
        String descriptor = "()L" + resultType + ";";
        return TestBytecode.clazz(name, cw -> {
            TestBytecode.method(cw, "call", "()V", mv -> {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "get", descriptor, false);
                mv.visitInsn(Opcodes.POP);
                mv.visitInsn(Opcodes.RETURN);
            });
        });
    }

    /** Build a gate whose hierarchy index can see the fixture's own classes. */
    private ClassVerdict gateOver(Path work, byte[] originalBytes, byte[] rebuiltBytes)
            throws Exception {
        Path classes = work.resolve("classes");
        writeHierarchy(classes);
        Path originalFile = classes.resolve("original/Caller.class");
        Path rebuiltFile = classes.resolve("rebuilt/Caller.class");
        Files.createDirectories(originalFile.getParent());
        Files.createDirectories(rebuiltFile.getParent());
        Files.write(originalFile, originalBytes);
        Files.write(rebuiltFile, rebuiltBytes);

        ClassGate gate = new ClassGate(new HierarchyIndex(List.of(classes)));
        return gate.compare(ClassModelReader.read(originalFile), ClassModelReader.read(rebuiltFile));
    }

    /**
     * The hierarchy the fixtures refer to: an interface, a concrete implementation of it, an
     * unrelated class with a similarly shaped method, and a base/derived pair for the covariant
     * case.
     */
    private static void writeHierarchy(Path classes) throws Exception {
        write(classes, "sample/Service.class", cw -> {
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                    "sample/Service", null, "java/lang/Object", null);
            cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                    "target", "()V", null, null).visitEnd();
        });
        write(classes, "sample/impl/Concrete.class", cw -> {
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/impl/Concrete",
                    null, "java/lang/Object", new String[]{"sample/Service"});
            TestBytecode.method(cw, "<init>", "()V", mv -> {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                mv.visitInsn(Opcodes.RETURN);
            });
            TestBytecode.method(cw, "target", "()V", mv -> mv.visitInsn(Opcodes.RETURN));
        });
        write(classes, "sample/Other.class", cw -> {
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/Other",
                    null, "java/lang/Object", null);
            TestBytecode.method(cw, "<init>", "()V", mv -> {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                mv.visitInsn(Opcodes.RETURN);
            });
            TestBytecode.method(cw, "target", "()V", mv -> mv.visitInsn(Opcodes.RETURN));
        });
        write(classes, "sample/Base.class", cw -> cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
                "sample/Base", null, "java/lang/Object", null));
        write(classes, "sample/Covariant.class", cw -> {
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/Covariant",
                    null, "java/lang/Object", null);
            TestBytecode.method(cw, "get", "()Lsample/Base;", mv -> {
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);
            });
        });
        write(classes, "sample/Derived.class", cw -> cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
                "sample/Derived", null, "sample/Base", null));
    }

    private static void write(Path root, String relative, java.util.function.Consumer<ClassWriter> body)
            throws Exception {
        ClassWriter cw = new ClassWriter(0);
        body.accept(cw);
        cw.visitEnd();
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, cw.toByteArray());
    }
}
