package com.anydecoder.core.gate;

/** How much a divergence matters. */
public enum Severity {

    /**
     * The divergence changes behaviour, or would do so if the benign classifier cannot explain
     * it. Blocks an EQUIVALENT verdict.
     */
    HARD,

    /** Possible silent damage. Worth investigating, but a method can still come out equivalent. */
    SUSPECT,

    /** Recorded for the reader. Never affects a verdict. */
    ADVISORY
}
