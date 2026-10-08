package com.anydecoder.core.gate;

import java.util.Map;

/**
 * One difference a signal found between the original and the recompiled method.
 *
 * <p>{@code evidence} is not decoration. A divergence may only be downgraded to benign when the
 * evidence names a specific, checkable structural pattern -- see
 * {@link BenignDivergenceClassifier}. An unexplained difference stays a difference.
 *
 * @param signalId which signal reported this
 * @param member   the method or field it is about
 * @param severity as reported by the signal, before classification
 * @param detail   one line for a human
 * @param evidence structured facts the classifier is allowed to reason over
 */
public record Divergence(
        String signalId,
        String member,
        Severity severity,
        String detail,
        Map<String, String> evidence) {

    public static Divergence of(String signalId, String member, Severity severity, String detail) {
        return new Divergence(signalId, member, severity, detail, Map.of());
    }

    public Divergence withEvidence(String key, String value) {
        Map<String, String> merged = new java.util.LinkedHashMap<>(evidence);
        merged.put(key, value);
        return new Divergence(signalId, member, severity, detail, Map.copyOf(merged));
    }
}
