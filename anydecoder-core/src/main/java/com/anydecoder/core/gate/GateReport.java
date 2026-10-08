package com.anydecoder.core.gate;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The result of running the gate over many classes, with the coverage to back it up.
 *
 * <p>Coverage is reported as prominently as the verdict counts on purpose. A gate that ran on
 * 20% of a codebase and found nothing is not evidence of anything, and a summary that shows
 * only the verdicts makes that look like success.
 */
public record GateReport(List<ClassVerdict> verdicts) {

    public Map<Verdict, Integer> counts() {
        Map<Verdict, Integer> counts = new EnumMap<>(Verdict.class);
        for (ClassVerdict v : verdicts) {
            counts.merge(v.verdict(), 1, Integer::sum);
        }
        return counts;
    }

    public int total() {
        return verdicts.size();
    }

    public int gated() {
        return (int) verdicts.stream().filter(v -> v.verdict() != Verdict.HARNESS_ERROR).count();
    }

    /** Fraction of classes the gate actually reached. */
    public double coverage() {
        return total() == 0 ? 0.0 : (double) gated() / total();
    }

    /** Fraction of reached classes that came out safe. */
    public double safeRate() {
        return gated() == 0 ? 0.0 : (double) safeCount() / gated();
    }

    public int safeCount() {
        return (int) verdicts.stream().filter(v -> v.verdict().isSafe()).count();
    }

    public List<ClassVerdict> needingReview() {
        return verdicts.stream()
                .filter(v -> v.verdict() == Verdict.SUSPECT || v.verdict() == Verdict.DAMAGED)
                .toList();
    }

    public List<ClassVerdict> harnessErrors() {
        return verdicts.stream().filter(v -> v.verdict() == Verdict.HARNESS_ERROR).toList();
    }

    /** Explanations actually used, by category, so a reader can see what was excused and how often. */
    public Map<String, Integer> explanationCensus() {
        Map<String, Integer> census = new TreeMap<>();
        for (ClassVerdict v : verdicts) {
            for (BenignDivergenceClassifier.Explanation e : v.explanations()) {
                census.merge(e.category(), 1, Integer::sum);
            }
        }
        return census;
    }

    /** Signals that produced an unexplained divergence, by signal, most frequent first. */
    public Map<String, Integer> unexplainedCensus() {
        Map<String, Integer> census = new TreeMap<>();
        for (ClassVerdict v : verdicts) {
            for (Divergence d : v.unexplained()) {
                census.merge(d.signalId(), 1, Integer::sum);
            }
        }
        return census;
    }
}
