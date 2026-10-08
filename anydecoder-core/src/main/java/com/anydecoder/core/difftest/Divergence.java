package com.anydecoder.core.difftest;

import java.util.List;

/**
 * A case where the two versions disagreed at runtime.
 *
 * <p>This is the evidence the static gate cannot produce. Every signal it compares is a
 * property of the instruction stream, so two statesments of the same shape swapped in order
 * look identical to it while doing different things to a caller.
 *
 * @param className      internal name
 * @param method         name and descriptor
 * @param arguments      the arguments the two were called with
 * @param originalResult what the original produced, value or thrown type
 * @param rebuiltResult  what the rebuild produced
 */
public record Divergence(
        String className,
        String method,
        List<String> arguments,
        String originalResult,
        String rebuiltResult) {

    public String render() {
        return className.replace('/', '.') + "." + method
                + "(" + String.join(", ", arguments) + ")"
                + " original=" + originalResult
                + " rebuilt=" + rebuiltResult;
    }
}
