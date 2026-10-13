package com.anydecoder.core.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The waiver mechanism: a divergence investigated once and accepted, so it stops being reported
 * every run.
 *
 * <p>The tests that matter are the refusals. A waiver list is only worth having if an entry means
 * somebody checked, and the way to guarantee that is to reject an entry that does not say why and
 * cite evidence. Without these, the mechanism would be a way to silence the gate by listing class
 * names.
 */
class WaiverRegistryTest {

    @Test
    @DisplayName("an entry with a reason and evidence downgrades the class")
    void appliesAWellFormedWaiver() {
        WaiverRegistry registry = WaiverRegistry.parse(List.of(
                "| class | signal | reason | evidence |",
                "|---|---|---|---|",
                "| com/example/Foo | excp | local auth added after recovery | commit 03433271 |"));

        ClassVerdict result = registry.apply(damaged("com/example/Foo", "excp"));

        assertEquals(Verdict.WAIVED, result.verdict());
        assertTrue(result.verdict().isSafe(), "an accepted difference should not need review");
        assertTrue(result.unexplained().isEmpty());
        assertTrue(result.explanations().stream()
                        .anyMatch(e -> e.category().equals("WAIVED_BY_RECORD")),
                "the decision has to be visible in the report, not silent");
    }

    @Test
    @DisplayName("an entry with no evidence is refused, not applied")
    void refusesAnEntryWithNoEvidence() {
        WaiverRegistry registry = WaiverRegistry.parse(List.of(
                "| com/example/Foo | excp | local auth added |  |"));

        ClassVerdict result = registry.apply(damaged("com/example/Foo", "excp"));

        assertEquals(Verdict.DAMAGED, result.verdict(),
                "an entry that does not say what was checked is indistinguishable from giving up");
        assertEquals(1, registry.rejected().size());
        assertEquals("no evidence given", registry.rejected().get(0).reason());
    }

    @Test
    @DisplayName("an entry with no reason is refused")
    void refusesAnEntryWithNoReason() {
        WaiverRegistry registry = WaiverRegistry.parse(List.of(
                "| com/example/Foo | excp |  | commit 03433271 |"));

        assertEquals(Verdict.DAMAGED, registry.apply(damaged("com/example/Foo", "excp")).verdict());
        assertEquals("no reason given", registry.rejected().get(0).reason());
    }

    @Test
    @DisplayName("an entry naming no signal is refused")
    void refusesAnEntryWithNoSignal() {
        WaiverRegistry registry = WaiverRegistry.parse(List.of(
                "| com/example/Foo |  | looked fine | commit 03433271 |"));

        assertEquals(Verdict.DAMAGED, registry.apply(damaged("com/example/Foo", "excp")).verdict());
        assertEquals("no signal named", registry.rejected().get(0).reason());
    }

    @Test
    @DisplayName("waiving one signal does not hide a different one")
    void doesNotHideUnwaivedSignals() {
        WaiverRegistry registry = WaiverRegistry.parse(List.of(
                "| com/example/Foo | fields | store hoisted into each arm | measured on 40 classes |"));

        ClassVerdict verdict = new ClassVerdict("com/example/Foo", Verdict.DAMAGED,
                List.of(), List.of(),
                List.of(divergence("fields"), divergence("api")), null);

        ClassVerdict result = registry.apply(verdict);

        assertEquals(Verdict.DAMAGED, result.verdict(),
                "the unwaived difference is still a difference");
        assertEquals(List.of("api"), result.unexplained().stream()
                .map(Divergence::signalId).toList());
    }

    @Test
    @DisplayName("a waiver that matched nothing is reported so it can be deleted")
    void reportsUnmatchedWaivers() {
        WaiverRegistry registry = WaiverRegistry.parse(List.of(
                "| com/example/Foo | excp | was a difference once | commit 1234567 |",
                "| com/example/Bar | excp | still is | commit 1234567 |"));

        registry.apply(damaged("com/example/Bar", "excp"));

        assertEquals(List.of("com/example/Foo"), registry.stale().stream()
                .map(Waiver::className).toList(),
                "a list that only grows stops being read");
    }

    @Test
    @DisplayName("a class may be written with dots or slashes")
    void acceptsEitherNotation() {
        WaiverRegistry registry = WaiverRegistry.parse(List.of(
                "| com.example.Foo | excp | reason | evidence |"));

        assertEquals(Verdict.WAIVED, registry.apply(damaged("com/example/Foo", "excp")).verdict());
    }

    @Test
    @DisplayName("a wildcard covers every signal, and is explicit")
    void wildcardCoversEverything() {
        WaiverRegistry registry = WaiverRegistry.parse(List.of(
                "| com/example/Foo | * | whole class reviewed by hand | analysis/gate-coverage.tsv |"));

        ClassVerdict verdict = new ClassVerdict("com/example/Foo", Verdict.DAMAGED,
                List.of(), List.of(),
                List.of(divergence("fields"), divergence("api")), null);

        assertEquals(Verdict.WAIVED, registry.apply(verdict).verdict());
    }

    @Test
    @DisplayName("several signals can be listed in one entry")
    void acceptsASignalList() {
        WaiverRegistry registry = WaiverRegistry.parse(List.of(
                "| com/example/Foo | invokes, consts | folding differs | measured |"));

        ClassVerdict verdict = new ClassVerdict("com/example/Foo", Verdict.DAMAGED,
                List.of(), List.of(),
                List.of(divergence("consts"), divergence("invokes")), null);

        assertEquals(Verdict.WAIVED, registry.apply(verdict).verdict());
    }

    @Test
    @DisplayName("a class that was not flagged is left alone")
    void leavesOtherVerdictsAlone() {
        WaiverRegistry registry = WaiverRegistry.parse(List.of(
                "| com/example/Foo | excp | reason | evidence |"));

        ClassVerdict equivalent = new ClassVerdict("com/example/Foo", Verdict.EQUIVALENT,
                List.of(), List.of(), List.of(), null);

        assertEquals(Verdict.EQUIVALENT, registry.apply(equivalent).verdict(),
                "a waiver must not turn a pass into something else, or the counts stop meaning anything");
        assertFalse(registry.apply(equivalent).verdict() == Verdict.WAIVED);
    }

    // ------------------------------------------------------------------------------------------

    private static ClassVerdict damaged(String className, String signal) {
        Divergence divergence = divergence(signal);
        return new ClassVerdict(className, Verdict.DAMAGED, List.of(divergence), List.of(),
                List.of(divergence), null);
    }

    private static Divergence divergence(String signal) {
        return new Divergence(signal, "someMethod()V", Severity.HARD, "a difference",
                Map.of("original", "a", "rebuilt", "b"));
    }
}