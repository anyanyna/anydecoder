package com.anydecoder.core.report;

import com.anydecoder.core.build.CompileOutcome;
import com.anydecoder.core.build.SourceTreeCompiler;
import com.anydecoder.core.gate.ClassGate;
import com.anydecoder.core.gate.ClassVerdict;
import com.anydecoder.core.gate.Divergence;
import com.anydecoder.core.gate.GateReport;
import com.anydecoder.core.gate.Severity;
import com.anydecoder.core.gate.Verdict;
import com.anydecoder.core.ingest.ArtifactSet;
import com.anydecoder.core.pipeline.PipelineOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the report says, and what it refuses to leave out.
 *
 * <p>A report is the only thing most readers see. A class the gate never reached is not a pass,
 * and a number saying how many there were leaves the reader nothing to do; the list is the point.
 * The same goes for the signals: they are taken from the gate rather than written out here,
 * because a hand-written list had already drifted two additions behind.
 */
class GateReportWriterTest {

    @Test
    @DisplayName("the classes the gate never reached are listed, not just counted")
    void listsUnreachedClasses() {
        PipelineOutcome outcome = outcome(List.of(
                ClassVerdict.harnessError("com/example/Missing",
                        "the rebuild produced no class for this file")));

        String markdown = GateReportWriter.toMarkdown(outcome);

        assertTrue(markdown.contains("## Not reached"), "there is a section for them");
        assertTrue(markdown.contains("com.example.Missing"), "and it names them");
        assertTrue(markdown.contains("They are not passes"),
                "and says plainly that they are not a pass");
    }

    @Test
    @DisplayName("the signals compared are taken from the gate, so the list cannot drift")
    void takesTheSignalListFromTheGate() {
        String markdown = GateReportWriter.toMarkdown(outcome(List.of()));

        for (String signal : new ClassGate().signalIds()) {
            assertTrue(markdown.contains("`" + signal + "`"),
                    "the report should account for the '" + signal + "' signal it ran");
        }
    }

    @Test
    @DisplayName("coverage is reported next to the verdicts")
    void reportsCoverageAlongsideVerdicts() {
        PipelineOutcome outcome = outcome(List.of(
                ClassVerdict.harnessError("com/example/Missing", "no class in the rebuild")));

        String markdown = GateReportWriter.toMarkdown(outcome);

        assertTrue(markdown.contains("classes found"), "a count without its coverage misleads");
        assertTrue(markdown.contains("not reached by the gate"),
                "and the gap between them is stated rather than left to arithmetic");
    }

    @Test
    @DisplayName("in a clean run there is no section for classes that were not reached")
    void omitsTheSectionWhenEverythingWasReached() {
        String markdown = GateReportWriter.toMarkdown(outcome(List.of()));

        assertFalse(markdown.contains("## Not reached"),
                "an empty section is noise, and its absence is itself the information");
    }

    @Test
    @DisplayName("the JSON report carries the review list and escapes what it quotes")
    void jsonCarriesTheReviewListAndEscapesDiagnostics() {
        ClassVerdict review = new ClassVerdict(
                "com/example/Review",
                Verdict.DAMAGED,
                List.of(),
                List.of(),
                List.of(new Divergence("invokes", "run()", Severity.HARD,
                        "expected \"a\" but got b\\c\nnext line",
                        Map.of("original", "a"))),
                null);
        PipelineOutcome outcome = outcome(List.of(review,
                ClassVerdict.harnessError("com/example/Missing", "no class in the rebuild")));

        String json = GateReportWriter.toJson(outcome);

        assertTrue(json.contains("\"tool\": \"anydecoder\""), json);
        assertTrue(json.contains("\"class\": \"com/example/Review\""), json);
        assertTrue(json.contains("\"verdict\": \"DAMAGED\""), json);
        assertTrue(json.contains("\"signal\": \"invokes\""), json);
        assertTrue(json.contains("expected \\\"a\\\" but got b\\\\c\\nnext line"),
                "a diagnostic is data, and its quotes and newlines have to survive as escapes");
        assertTrue(json.contains("\"notReached\""), "unreached classes are listed in JSON too");
        assertTrue(json.contains("com/example/Missing"), json);
        assertTrue(json.contains("\"coverage\""), "coverage travels with the verdicts");
    }

    @Test
    @DisplayName("a missing diagnostic becomes an empty string, not a failed report")
    void writesMissingTextAsEmpty() {
        PipelineOutcome outcome = outcome(
                List.of(ClassVerdict.harnessError("com/example/Missing", null)),
                List.of(new SourceTreeCompiler.ExcludedSource(
                        Path.of("Broken.java"), null, false)));

        String json = GateReportWriter.toJson(outcome);

        assertTrue(json.contains("\"note\": \"\""), json);
        assertTrue(json.contains("\"reason\": \"\""), json);
    }

    @Test
    @DisplayName("JSON fields keep the order the writer chose")
    void keepsJsonFieldOrderStable() {
        PipelineOutcome outcome = outcome(List.of(
                ClassVerdict.harnessError("com/example/Missing", "no class in the rebuild")));

        String json = GateReportWriter.toJson(outcome);

        int classField = json.indexOf("\"class\": \"com/example/Missing\"");
        int noteField = json.indexOf("\"note\": \"no class in the rebuild\"");
        assertTrue(classField >= 0, json);
        assertTrue(noteField > classField, "class should precede note: " + json);
        assertEquals(json, GateReportWriter.toJson(outcome), "two identical runs must agree");
    }

    // ------------------------------------------------------------------------------------------

    private static PipelineOutcome outcome(List<ClassVerdict> verdicts) {
        return outcome(verdicts, List.of());
    }

    private static PipelineOutcome outcome(List<ClassVerdict> verdicts,
                                           List<SourceTreeCompiler.ExcludedSource> unbuildable) {
        ArtifactSet artifacts = new ArtifactSet(Path.of("classes"), List.of(),
                ArtifactSet.Layout.LOOSE_CLASSES);
        return new PipelineOutcome(artifacts, Map.of("cfr", Path.of("src-cfr")),
                Path.of("src-cfr"),
                new CompileOutcome(true, List.of(), Path.of("classes")),
                unbuildable,
                List.of(),
                List.of(),
                new GateReport(verdicts),
                Path.of("classes"),
                Map.of(),
                null,
                Map.of());
    }
}