package com.anydecoder.core.report;

import com.anydecoder.core.build.CompileOutcome;
import com.anydecoder.core.gate.BenignDivergenceClassifier;
import com.anydecoder.core.gate.ClassVerdict;
import com.anydecoder.core.gate.Divergence;
import com.anydecoder.core.gate.GateReport;
import com.anydecoder.core.gate.Verdict;
import com.anydecoder.core.pipeline.PipelineOutcome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a run's result as Markdown and as JSON.
 *
 * <h2>What the report refuses to do</h2>
 *
 * <p>It never reports a bare pass rate. Coverage is printed next to the verdicts, because "0
 * damaged classes" means something very different when the gate reached 12% of the codebase than
 * when it reached all of it, and a summary that omits the difference invites the reader to
 * assume the good case.
 *
 * <p>It also never hides an exemption. Every class that came out {@code BENIGN} is listed with
 * the categories that excused it, so a reviewer can disagree with a specific category instead of
 * having to re-derive the whole judgement.
 */
public final class GateReportWriter {

    private GateReportWriter() {
    }

    public static String toMarkdown(PipelineOutcome outcome) {
        GateReport report = outcome.gate();
        StringBuilder sb = new StringBuilder();

        sb.append("# anydecoder report\n\n");
        sb.append("**Verdict: ").append(headline(report)).append("**\n\n");

        sb.append("## Coverage\n\n");
        sb.append("| | count |\n|---|---:|\n");
        sb.append("| classes found | ").append(report.total()).append(" |\n");
        sb.append("| reached by the gate | ").append(report.gated()).append(" |\n");
        sb.append("| classified safe | ").append(report.safeCount()).append(" |\n");
        sb.append("| need review | ").append(report.needingReview().size()).append(" |\n\n");

        if (report.harnessErrors().size() > 0) {
            sb.append("> ").append(report.harnessErrors().size())
                    .append(" class(es) were not reached by the gate. They are not passes.\n\n");
        }

        sb.append("## Verdicts\n\n");
        sb.append("| verdict | count |\n|---|---:|\n");
        for (Map.Entry<Verdict, Integer> e : report.counts().entrySet()) {
            sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
        }
        sb.append('\n');

        if (!report.explanationCensus().isEmpty()) {
            sb.append("### Differences accounted for by known compiler artefacts\n\n");
            sb.append("| category | classes |\n|---|---:|\n");
            report.explanationCensus().forEach((category, count) ->
                    sb.append("| ").append(category).append(" | ").append(count).append(" |\n"));
            sb.append('\n');
        }

        List<ClassVerdict> review = report.needingReview();
        if (!review.isEmpty()) {
            sb.append("## Needs review\n\n");
            for (ClassVerdict verdict : review) {
                sb.append("### `").append(verdict.className()).append("` — ")
                        .append(verdict.verdict()).append("\n\n");
                for (Divergence divergence : verdict.unexplained()) {
                    sb.append("- **").append(divergence.signalId()).append("** (")
                            .append(divergence.severity()).append(") in `")
                            .append(divergence.member()).append("`: ")
                            .append(divergence.detail()).append('\n');
                }
                sb.append('\n');
            }
        }

        List<ClassVerdict> benign = report.verdicts().stream()
                .filter(v -> v.verdict() == Verdict.BENIGN).toList();
        if (!benign.isEmpty()) {
            sb.append("## Accepted as compiler artefacts\n\n");
            sb.append("Differs from the original, in a way that has been identified and named."
                    + " Listed so the identification can be challenged.\n\n");
            for (ClassVerdict verdict : benign) {
                sb.append("- `").append(verdict.className()).append("`");
                for (BenignDivergenceClassifier.Explanation e : verdict.explanations()) {
                    sb.append(" — ").append(e.category());
                }
                sb.append('\n');
            }
            sb.append('\n');
        }

        sb.append("## Build\n\n");
        CompileOutcome compile = outcome.compile();
        sb.append("- source files compiled: ").append(countSources(outcome)).append('\n');
        sb.append("- compiler errors: ").append(compile.errorCount()).append('\n');
        if (compile.errorCount() > 0) {
            sb.append('\n').append("```\n");
            compile.errors().stream().limit(20).forEach(d -> sb.append(d.render()).append('\n'));
            if (compile.errorCount() > 20) {
                sb.append("... and ").append(compile.errorCount() - 20).append(" more\n");
            }
            sb.append("```\n");
        }
        sb.append('\n');

        if (!outcome.normalization().isEmpty()) {
            sb.append("## Cleanup passes\n\n");
            sb.append("| pass | files changed |\n|---|---:|\n");
            outcome.normalization().forEach((pass, files) ->
                    sb.append("| ").append(pass).append(" | ").append(files).append(" |\n"));
            sb.append('\n');
        }

        sb.append("## What this does and does not establish\n\n");
        sb.append("The gate recompiled the recovered source and compared the result with the"
                + " original class files: exception-table shape, call targets, field accesses,"
                + " constants, rethrows, and declaration lists. A class reported"
                + " `EQUIVALENT` matched on every one of those.\n\n");
        sb.append("It does not establish that the source is what the author wrote. Variable"
                + " names, comments, formatting, and whether a loop was written as `for` or"
                + " `while` are not recoverable from bytecode and are not claimed.\n\n");
        sb.append("Two known limits: two independent statements of the same kind swapped in"
                + " order produce identical signals, and nothing here observes runtime"
                + " behaviour. Differential testing covers the second where it is enabled.\n");

        return sb.toString();
    }

    public static String toJson(PipelineOutcome outcome) {
        GateReport report = outcome.gate();

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tool", "anydecoder");
        root.put("headline", headline(report));

        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("classesFound", report.total());
        coverage.put("classesGated", report.gated());
        coverage.put("classesSafe", report.safeCount());
        coverage.put("coverage", round(report.coverage()));
        coverage.put("safeRateOfGated", round(report.safeRate()));
        root.put("coverage", coverage);

        Map<String, Object> verdicts = new LinkedHashMap<>();
        for (Verdict verdict : Verdict.values()) {
            verdicts.put(verdict.name(), report.counts().getOrDefault(verdict, 0));
        }
        root.put("verdicts", verdicts);

        root.put("explanations", new LinkedHashMap<String, Object>(report.explanationCensus()));
        root.put("unexplainedBySignal", new LinkedHashMap<String, Object>(report.unexplainedCensus()));

        List<Object> review = new ArrayList<>();
        for (ClassVerdict verdict : report.needingReview()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("class", verdict.className());
            entry.put("verdict", verdict.verdict().name());
            entry.put("source", suggestedSource(verdict.className()));
            List<Object> findings = new ArrayList<>();
            for (Divergence divergence : verdict.unexplained()) {
                Map<String, Object> finding = new LinkedHashMap<>();
                finding.put("signal", divergence.signalId());
                finding.put("severity", divergence.severity().name());
                finding.put("member", divergence.member());
                finding.put("detail", divergence.detail());
                finding.put("evidence", new LinkedHashMap<String, Object>(divergence.evidence()));
                findings.add(finding);
            }
            entry.put("findings", findings);
            review.add(entry);
        }
        root.put("needsReview", review);

        List<Object> unreached = new ArrayList<>();
        for (ClassVerdict verdict : report.harnessErrors()) {
            unreached.add(Map.of("class", verdict.className(), "note",
                    verdict.note() == null ? "" : verdict.note()));
        }
        root.put("notReached", unreached);

        Map<String, Object> build = new LinkedHashMap<>();
        build.put("compilerErrors", outcome.compile().errorCount());
        build.put("sourcesCompiled", countSources(outcome));
        root.put("build", build);

        root.put("cleanupPasses", new LinkedHashMap<String, Object>(outcome.normalization()));

        return Json.write(root);
    }

    /**
     * Where a person should look to fix this class. Inner classes live in their outer class's
     * source file, so {@code Foo$Bar} points at {@code Foo.java}.
     */
    private static String suggestedSource(String className) {
        int dollar = className.indexOf('$');
        String outer = dollar < 0 ? className : className.substring(0, dollar);
        return outer + ".java";
    }

    private static String headline(GateReport report) {
        if (report.total() == 0) {
            return "nothing to gate";
        }
        int review = report.needingReview().size();
        if (review == 0) {
            return report.safeCount() + " of " + report.gated() + " classes shown equivalent";
        }
        return review + " of " + report.gated() + " classes need review";
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static int countSources(PipelineOutcome outcome) {
        try {
            return com.anydecoder.core.pipeline.DecoderPipeline.javaFiles(outcome.primarySource()).size();
        } catch (Exception e) {
            return -1;
        }
    }
}
