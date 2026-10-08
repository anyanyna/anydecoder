package com.anydecoder.core.gate;

/**
 * One row of a method's exception table.
 *
 * @param start   first covered instruction offset
 * @param end     one past the last covered offset
 * @param handler handler entry offset
 * @param type    internal name of the caught class, or {@link #ANY} for a finally handler
 */
public record ExceptionEntry(int start, int end, int handler, String type) {

    /**
     * The type ASM reports for a finally handler (a catch-all). The Eclipse compiler writes
     * this for every {@code finally}; javac can write either this or an explicit
     * {@code catch (Throwable)}. They mean the same thing, so both normalise here.
     */
    public static final String ANY = "*any*";

    /** javac emits a self-protection row for finally handlers; ecj does not. */
    public boolean isSelfProtection() {
        return start == handler;
    }

    public int handlerOffset() {
        return handler;
    }
}
