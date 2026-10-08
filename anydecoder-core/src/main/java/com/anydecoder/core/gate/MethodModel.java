package com.anydecoder.core.gate;

import java.util.List;

/**
 * A declared method together with everything the gate compares about its body.
 *
 * @param declaredExceptions the {@code throws} clause, as internal names. Not part of the
 *                           descriptor -- {@code void f()} and {@code void f() throws IOException}
 *                           have the same one -- so nothing else here can see it.
 */
public record MethodModel(
        int access,
        String name,
        String descriptor,
        String signature,
        List<String> declaredExceptions,
        List<Insn> instructions,
        List<ExceptionEntry> exceptionTable,
        List<LocalVar> localVariables) {

    public String key() {
        return name + descriptor;
    }

    /** Identity used by the API signal. */
    public String identity() {
        return name + descriptor + " flags=0x" + Integer.toHexString(access);
    }

    public boolean isAbstractOrNative() {
        return (access & 0x0400) != 0 || (access & 0x0100) != 0;
    }

    public boolean hasBody() {
        return !instructions.isEmpty();
    }
}
