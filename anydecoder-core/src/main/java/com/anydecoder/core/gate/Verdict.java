package com.anydecoder.core.gate;

/**
 * What the gate concluded about one class.
 *
 * <p>The order matters and is not a ranking of confidence in the usual sense: a class that
 * reaches {@link #BENIGN} has been examined <em>more</em> than one that reaches
 * {@link #EQUIVALENT}, because something in it differed and had to be accounted for. Both are
 * safe. Only the last two need a person.
 */
public enum Verdict {

    /** Every signal matched. The recompiled class and the original are interchangeable. */
    EQUIVALENT,

    /**
     * Something differed, and every difference was matched to a known compiler artefact with
     * its structural evidence recorded. Safe, and auditable.
     */
    BENIGN,

    /** A difference nothing could account for, at a severity that may or may not matter. */
    SUSPECT,

    /**
     * A difference nothing could account for, at a severity that changes behaviour.
     */
    DAMAGED,

    /**
     * A difference that was investigated and accepted, with the reason and the evidence recorded.
     *
     * <p>Distinct from {@link #BENIGN}, which means the <em>compiler</em> explains the difference.
     * This means a person does: the source was deliberately changed after recovery, or the
     * difference was looked at and judged not to matter. Both are safe; keeping them apart is the
     * point, because one is a fact about code generation and the other is a decision somebody can
     * be asked about.
     *
     * <p>Requires the waiver to state why and cite evidence. A waiver that does not is rejected
     * before it can produce this verdict.
     */
    WAIVED,

    /**
     * The gate could not run -- a class file is missing, or a recompile failed. Not a statement
     * about the code, and never counted as a pass.
     */
    HARNESS_ERROR;

    public boolean isSafe() {
        return this == EQUIVALENT || this == BENIGN || this == WAIVED;
    }
}
