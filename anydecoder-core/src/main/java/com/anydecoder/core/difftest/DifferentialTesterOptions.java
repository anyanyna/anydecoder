package com.anydecoder.core.difftest;

/**
 * Bounds on a differential run.
 *
 * <p>The seed is explicit so that a divergence found in CI can be reproduced exactly. A fuzz
 * that cannot be replayed is a rumour, not a finding.
 *
 * @param seed             fixes the generated argument sequence
 * @param maxParameters    methods with more parameters than this are skipped; the number of
 *                         combinations grows exponentially and the later ones stop being
 *                         meaningful long before they stop being slow
 */
public record DifferentialTesterOptions(long seed, int maxParameters) {

    public static DifferentialTesterOptions defaults() {
        return new DifferentialTesterOptions(20261008L, 4);
    }
}
