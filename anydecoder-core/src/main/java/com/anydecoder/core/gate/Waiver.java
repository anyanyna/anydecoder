package com.anydecoder.core.gate;

import java.util.List;
import java.util.Set;

/**
 * A divergence that has been investigated and accepted.
 *
 * <p>A waiver is the tool's memory. Without one, every run re-reports the same differences and
 * every investigation has to be paid for again; with one, a verdict is reached once and the class
 * stops appearing in the review list.
 *
 * <p><b>A waiver must state why and cite its evidence.</b> {@link WaiverRegistry} refuses an entry
 * missing either and reports it as rejected rather than applying it. That rule is the point: a
 * list of class names saying "we looked at this" reads exactly like a list of class names saying
 * "we gave up", and only one of those is worth keeping.
 *
 * <p>Matching is per class and per signal, never per class alone. A class can carry a waived
 * difference in one signal and an unwaived difference in another, and a waiver that matched
 * loosely would hide the second — the exemption-without-evidence failure this project exists to
 * avoid.
 *
 * @param className internal name, {@code com/example/Foo}
 * @param signals   the signals this covers; {@link #ALL_SIGNALS} covers every one for the class
 * @param reason    one line: why this difference is accepted
 * @param evidence  what was checked, concretely — a commit, an instruction, a measurement
 * @param source    the entry's line number in the file, for reporting an error against it
 */
public record Waiver(String className, Set<String> signals, String reason, String evidence,
                     String source) {

    /** Covers every signal for the class. Explicit, because it hides the most. */
    public static final String ALL_SIGNALS = "*";

    public boolean covers(String signalId) {
        return signals.contains(ALL_SIGNALS) || signals.contains(signalId);
    }

    /** True when this waiver says nothing at all — no signals named. */
    public boolean isVacuous() {
        return signals.isEmpty();
    }

    /** Rendered for the report so a reader can check the claim rather than trust it. */
    public String describe() {
        return "waived: " + reason + " (evidence: " + evidence + ")";
    }

    public List<String> signalList() {
        return signals.stream().sorted().toList();
    }
}