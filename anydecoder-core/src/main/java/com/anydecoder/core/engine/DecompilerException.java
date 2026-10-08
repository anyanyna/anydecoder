package com.anydecoder.core.engine;

/** An engine could not run, or ran and failed. */
public class DecompilerException extends Exception {

    public DecompilerException(String message) {
        super(message);
    }

    public DecompilerException(String message, Throwable cause) {
        super(message, cause);
    }
}
