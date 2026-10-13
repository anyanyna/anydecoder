package com.anydecoder.core.report;

import com.anydecoder.core.build.CompileOutcome;
import com.anydecoder.core.gate.ClassGate;
import com.anydecoder.core.gate.ClassVerdict;
import com.anydecoder.core.gate.GateReport;
import com.anydecoder.core.ingest.ArtifactSet;
import com.anydecoder.core.pipeline.PipelineOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

    // ------------------------------------------------------------------------------------------

    private static PipelineOutcome outcome(List<ClassVerdict> verdicts) {
        ArtifactSet artifacts = new ArtifactSet(Path.of("classes"), List.of(),
                ArtifactSet.Layout.LOOSE_CLASSES);
        return new PipelineOutcome(artifacts, Map.of("cfr", Path.of("src-cfr")),
                Path.of("src-cfr"),
                new CompileOutcome(true, List.of(), Path.of("classes")),
                List.of(),
                List.of(),
                List.of(),
                new GateReport(verdicts),
                Path.of("classes"),
                Map.of(),
                null,
                Map.of());
    }
}