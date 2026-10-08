package com.anydecoder.core.gate.signals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Counting helpers shared by the signals that compare collections rather than scalars. */
public final class Multisets {

    private Multisets() {
    }

    public static <T> Map<String, Integer> counts(List<T> items, Function<T, String> key) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (T item : items) {
            counts.merge(key.apply(item), 1, Integer::sum);
        }
        return counts;
    }

    public static <T> Map<String, Integer> countsOf(List<T> items) {
        return counts(items, String::valueOf);
    }

    /**
     * Render the difference between two count maps in a form a human can act on, listing at
     * most {@code limit} entries per side.
     */
    public static String describeDiff(Map<String, Integer> original, Map<String, Integer> rebuilt, int limit) {
        List<String> onlyOriginal = new ArrayList<>();
        List<String> onlyRebuilt = new ArrayList<>();
        List<String> differing = new ArrayList<>();

        for (Map.Entry<String, Integer> e : original.entrySet()) {
            int other = rebuilt.getOrDefault(e.getKey(), 0);
            if (other == 0) {
                onlyOriginal.add(e.getKey() + (e.getValue() > 1 ? " x" + e.getValue() : ""));
            } else if (other != e.getValue()) {
                differing.add(e.getKey() + " " + e.getValue() + "->" + other);
            }
        }
        for (Map.Entry<String, Integer> e : rebuilt.entrySet()) {
            if (!original.containsKey(e.getKey())) {
                onlyRebuilt.add(e.getKey() + (e.getValue() > 1 ? " x" + e.getValue() : ""));
            }
        }

        StringBuilder sb = new StringBuilder();
        appendLimited(sb, "only in original", onlyOriginal, limit);
        appendLimited(sb, "only in rebuild", onlyRebuilt, limit);
        appendLimited(sb, "count differs", differing, limit);
        return sb.length() == 0 ? "no difference" : sb.toString();
    }

    private static void appendLimited(StringBuilder sb, String label, List<String> items, int limit) {
        if (items.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("; ");
        }
        sb.append(label).append(": ");
        sb.append(String.join(", ", items.subList(0, Math.min(limit, items.size()))));
        if (items.size() > limit) {
            sb.append(" (+").append(items.size() - limit).append(" more)");
        }
    }
}
