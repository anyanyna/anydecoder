package com.anydecoder.core.gate;

import java.util.Optional;

/**
 * One dimension along which a recompiled method is compared with its original.
 *
 * <p>Every signal must be insensitive to a compiler's arbitrary choices -- constant-pool
 * numbering, branch offsets, local variable slots, member order -- and sensitive to a change in
 * behaviour. A signal that fires on an arbitrary choice costs a human an investigation; a
 * signal that stays quiet on a behaviour change costs correctness. Both are bugs.
 */
public interface Signal {

    String id();

    /** The severity this signal reports at, before benign classification. */
    Severity severity();

    /** Empty when this signal sees nothing to report. */
    Optional<Divergence> compare(MethodModel original, MethodModel rebuilt);
}
