package com.anydecoder.core.gate;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.function.Consumer;

/**
 * Builds class files in memory so a test can state exactly which instructions, exception table
 * rows and locals it means.
 *
 * <p>Maxs are computed but stack map frames are not: the gate parses with
 * {@code SKIP_FRAMES}, so frames would be written and immediately discarded, and computing them
 * would drag a classloader into every test.
 */
final class TestBytecode {

    interface Body {
        void emit(MethodVisitor mv);
    }

    private TestBytecode() {
    }

    static byte[] clazz(String name, Consumer<ClassWriter> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        body.accept(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    static byte[] clazz(String name, Body method, String methodName, String methodDescriptor) {
        return clazz(name, cw -> method(cw, methodName, methodDescriptor, method));
    }

    static void method(ClassWriter cw, String name, String desc, Body body) {
        method(cw, Opcodes.ACC_PUBLIC, name, desc, body);
    }

    static void method(ClassWriter cw, int access, String name, String desc, Body body) {
        MethodVisitor mv = cw.visitMethod(access, name, desc, null, null);
        mv.visitCode();
        body.emit(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** A method that calls {@code Object.hashCode()} twice with a non-throwing gap between. */
    static void twoThrowingCalls(MethodVisitor mv, Runnable gap, Runnable handlerTail) {
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label afterFirst = new org.objectweb.asm.Label();
        org.objectweb.asm.Label secondStart = new org.objectweb.asm.Label();
        org.objectweb.asm.Label secondEnd = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();

        mv.visitTryCatchBlock(start, afterFirst, handler, "java/io/IOException");
        mv.visitTryCatchBlock(secondStart, secondEnd, handler, "java/io/IOException");

        mv.visitLabel(start);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(afterFirst);
        gap.run();
        mv.visitLabel(secondStart);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(secondEnd);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(handler);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        handlerTail.run();
    }
}
