package com.anydecoder.core.report;

import com.anydecoder.core.build.CompileOutcome;
import com.anydecoder.core.build.SourceTreeCompiler;
import com.anydecoder.core.gate.BenignDivergenceClassifier;
import com.anydecoder.core.gate.ClassVerdict;
import com.anydecoder.core.gate.Divergence;
import com.anydecoder.core.gate.GateReport;
import com.anydecoder.core.gate.Verdict;
import com.anydecoder.core.gate.Waiver;
import com.anydecoder.core.gate.WaiverRegistry;
import com.anydecoder.core.pipeline.EngineSubstitution;
import com.anydecoder.core.pipeline.PipelineOutcome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
            sb.append("Ordered by cause, so the findings most likely to be real defects come"
                    + " first. Evidence is shown for each: both sides of the comparison, so the"
                    + " claim can be checked rather than taken on trust.\n\n");

            Map<String, List<ClassVerdict>> byCause = new TreeMap<>();
            review.forEach(v -> v.unexplained().forEach(d ->
                    byCause.computeIfAbsent(d.signalId(), k -> new java.util.ArrayList<>()).add(v)));

            sb.append("| signal | classes | what it means |\n|---|---:|---|\n");
            byCause.forEach((signal, classes) -> sb.append("| `").append(signal).append("` | ")
                    .append(classes.stream().distinct().count()).append(" | ")
                    .append(CAUSE.getOrDefault(signal, "")).append(" |\n"));
            sb.append('\n');

            for (String signal : byCause.keySet()) {
                sb.append("### ").append(signal).append("\n\n");
                byCause.get(signal).stream().distinct().forEach(verdict ->
                        appendClassFindings(sb, verdict, signal));
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

        appendEngineSubstitutions(sb, outcome);
        appendWaivers(sb, outcome);
        appendThrowsAdvisory(sb, report);

        sb.append("## Build\n\n");
        CompileOutcome compile = outcome.compile();
        sb.append("- source files compiled: ").append(outcome.compiledSourceCount()).append('\n');
        sb.append("- sources removed as uncompilable: ")
                .append(outcome.unbuildableSources().size()).append('\n');
        sb.append("- compiler errors in the final round: ").append(compile.errorCount()).append('\n');
        if (compile.errorCount() > 0) {
            sb.append('\n').append("```\n");
            compile.errors().stream().limit(20).forEach(d -> sb.append(d.render()).append('\n'));
            if (compile.errorCount() > 20) {
                sb.append("... and ").append(compile.errorCount() - 20).append(" more\n");
            }
            sb.append("```\n");
        }
        sb.append('\n');

        if (!outcome.substitutedSources().isEmpty()) {
            sb.append("### Files rescued by a second engine\n\n");
            sb.append("The primary engine's rendering would not compile; another engine's was"
                    + " substituted and then compiled and gated like anything else.\n\n");
            outcome.substitutedSources().stream().limit(30).forEach(s ->
                    sb.append("- `").append(s).append("`\n"));
            if (outcome.substitutedSources().size() > 30) {
                sb.append("- ... and ").append(outcome.substitutedSources().size() - 30)
                        .append(" more\n");
            }
            sb.append('\n');
        }

        appendUnbuildable(sb, outcome);

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

    /**
     * Classes the gate was unhappy with that another engine's rendering fixed.
     *
     * <p>Worth reporting because the tree is no longer one engine's output. The gate says whether
     * a class is faithful, not which tool wrote it, and a reader who later regenerates the tree
     * from one engine would get different results for these.
     */
    private static void appendEngineSubstitutions(StringBuilder sb, PipelineOutcome outcome) {
        List<EngineSubstitution.Attempt> kept = outcome.substitutions().stream()
                .filter(EngineSubstitution.Attempt::kept).toList();
        if (kept.isEmpty()) {
            return;
        }
        sb.append("## Repaired by using another engine's rendering\n\n");
        sb.append("The gate was unhappy with these; the other decompiler's version of the same")
                .append(" class was compiled and gated, and kept because it came out safe. Every")
                .append(" one is recorded so the tree's provenance is not a mystery.\n\n");
        sb.append("| class | was | now | from |\n|---|---|---|---|\n");
        for (EngineSubstitution.Attempt attempt : kept.stream().limit(60).toList()) {
            sb.append("| `").append(attempt.className().replace('/', '.')).append("` | ")
                    .append(attempt.before()).append(" | ").append(attempt.after()).append(" | ")
                    .append(attempt.engine()).append(" |\n");
        }
        if (kept.size() > 60) {
            sb.append("\n... and ").append(kept.size() - 60).append(" more\n");
        }
        sb.append('\n');
    }

    /**
     * Classes that are faithful but lost a declared exception.
     *
     * <p>Separate from the review list because it is not a behavioural difference — the clause is
     * compile-time only — and putting it there would train a reader to ignore the list. Separate
     * from silence because it can leave recovered source unusable as a base class: a subclass may
     * not declare a checked exception its superclass does not, so losing one on a base class
     * constructor breaks every subclass that declares it.
     */
    private static void appendThrowsAdvisory(StringBuilder sb, GateReport report) {
        List<ClassVerdict> affected = report.verdicts().stream()
                .filter(v -> v.divergences().stream().anyMatch(d -> d.signalId().equals("throws")))
                .toList();
        if (affected.isEmpty()) {
            return;
        }
        sb.append("## Declared exceptions the rebuild does not have\n\n");
        sb.append("Not a behavioural difference: a `throws` clause is compile-time only and")
                .append(" appears nowhere in the executed bytecode. Listed because it can still")
                .append(" make the source unusable as a base class, since a subclass may not")
                .append(" declare a checked exception its superclass does not.\n\n");
        for (ClassVerdict verdict : affected.stream().limit(40).toList()) {
            sb.append("- `").append(verdict.className().replace('/', '.')).append("`\n");
            verdict.divergences().stream()
                    .filter(d -> d.signalId().equals("throws"))
                    .forEach(d -> sb.append("  - `").append(d.member()).append("`: ")
                            .append(d.detail()).append('\n'));
        }
        if (affected.size() > 40) {
            sb.append("- ... and ").append(affected.size() - 40).append(" more\n");
        }
        sb.append('\n');
    }

    /**
     * Divergences accepted on the record, and waivers that no longer apply.
     *
     * <p>Separate from the compiler-artefact section on purpose. {@code BENIGN} means code
     * generation explains the difference; a waiver means a person decided it does not matter.
     * Both are safe, and conflating them would hide which of the two a reader is looking at —
     * one is a fact about compilers, the other a decision that can be argued with.
     */
    private static void appendWaivers(StringBuilder sb, PipelineOutcome outcome) {
        WaiverRegistry waivers = outcome.waivers();
        if (waivers == null || (waivers.size() == 0 && waivers.rejected().isEmpty())) {
            return;
        }

        List<ClassVerdict> accepted = outcome.gate().waived();
        if (!accepted.isEmpty()) {
            sb.append("## Accepted by a recorded waiver\n\n");
            sb.append("Investigated, and the difference accepted on the record. **Not the same as")
                    .append(" `BENIGN`**: that means a compiler artefact explains a difference,")
                    .append(" this means somebody decided it does not matter. The reason and the")
                    .append(" evidence are shown so a decision can be challenged without redoing")
                    .append(" the investigation.\n\n");
            for (ClassVerdict verdict : accepted.stream().limit(60).toList()) {
                sb.append("- `").append(verdict.className().replace('/', '.')).append("`\n");
                verdict.explanations().stream()
                        .filter(e -> e.category().equals("WAIVED_BY_RECORD"))
                        .forEach(e -> sb.append("  - ").append(e.reason()).append('\n'));
            }
            if (accepted.size() > 60) {
                sb.append("- ... and ").append(accepted.size() - 60).append(" more\n");
            }
            sb.append('\n');
        }

        List<Waiver> stale = waivers.stale();
        if (!stale.isEmpty()) {
            sb.append("## Waivers that now match nothing\n\n");
            sb.append("The difference each covered is gone, so the entry can be deleted.\n\n");
            stale.forEach(w -> sb.append("- `").append(w.className().replace('/', '.'))
                    .append("` (").append(String.join(", ", w.signalList())).append(")\n"));
            sb.append('\n');
        }

        List<WaiverRegistry.Rejected> refused = waivers.rejected();
        if (!refused.isEmpty()) {
            sb.append("## Waivers refused\n\n");
            sb.append("An entry has to say why and cite evidence; these did not, so they were")
                    .append(" **not** applied.\n\n");
            refused.forEach(r -> sb.append("- `").append(r.className().replace('/', '.'))
                    .append("` — ").append(r.reason()).append(" (").append(r.source()).append(")\n"));
            sb.append('\n');
        }
    }

    private static final Map<String, String> CAUSE = Map.of(
            "excp", "the exception table changed: a dropped try, a swallowed handler, or a finally rendered as try-with-resources",
            "athrow", "a rethrow became a jump; the exception may be swallowed",
            "invokes", "a call was added, removed, or moved to another member",
            "fields", "a field is touched on one side only, as a different opcode",
            "consts", "a constant loaded from the pool changed",
            "smallConsts", "a small constant pushed in the instruction changed -- commonly a compiler difference, see docs/verification.md",
            "api", "the declared members or class flags changed",
            "members", "a method is missing from one side",
            "instrCount", "the body changed size beyond what a compiler difference explains",
            "lvt", "a local variable's declared type changed");

    private static void appendClassFindings(StringBuilder sb, ClassVerdict verdict, String signal) {
        sb.append("- `").append(verdict.className().replace('/', '.')).append("`\n");
        for (Divergence divergence : verdict.unexplained()) {
            if (!divergence.signalId().equals(signal)) {
                continue;
            }
            sb.append("  - ").append(divergence.severity()).append(" in `")
                    .append(divergence.member()).append("`: ").append(divergence.detail())
                    .append('\n');
            divergence.evidence().forEach((key, value) -> {
                if (value != null && !value.isEmpty()) {
                    sb.append("    - ").append(key).append(": `")
                            .append(value.replace("`", "'")).append("`\n");
                }
            });
        }
    }

    /**
     * Files the rebuild could not use, split by cause.
     *
     * <p>The split matters more than the list. A file that calls {@code sun.misc.BASE64Encoder}
     * cannot be rebuilt on a modern JDK at all — the class was removed in Java 9 and no
     * {@code --release} brings it back — and presenting that next to a decompiler's structure
     * collapse would send a reader to repair source that is fine.
     */
    private static void appendUnbuildable(StringBuilder sb, PipelineOutcome outcome) {
        List<SourceTreeCompiler.ExcludedSource> sourceDefects = outcome.sourceDefects();
        List<SourceTreeCompiler.ExcludedSource> jdkApis = outcome.missingJdkApis();

        if (!sourceDefects.isEmpty()) {
            sb.append("### Sources that would not compile\n\n");
            sb.append("Set aside so that the rest could be checked. Their classes are absent, so"
                    + " the gate did not reach them and they are **not** passes. This is the"
                    + " repair worklist.\n\n");
            sourceDefects.stream().limit(60).forEach(e ->
                    sb.append("- `").append(relativeTo(outcome.primarySource(), e.path()))
                            .append("` — ").append(oneLine(e.reason())).append('\n'));
            if (sourceDefects.size() > 60) {
                sb.append("- ... and ").append(sourceDefects.size() - 60).append(" more\n");
            }
            sb.append('\n');
        }

        if (!jdkApis.isEmpty()) {
            sb.append("### Not rebuildable on this JDK, not a decompilation defect\n\n");
            sb.append("These use an API the JDK no longer ships, so they cannot be recompiled"
                    + " here and the gate could not reach them. Nothing is claimed about their"
                    + " fidelity either way.\n\n");
            jdkApis.forEach(e ->
                    sb.append("- `").append(relativeTo(outcome.primarySource(), e.path()))
                            .append("` — ").append(oneLine(e.reason())).append('\n'));
            sb.append('\n');
        }
    }

    private static String oneLine(String text) {
        if (text == null) {
            return "(no message)";
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        return collapsed.length() > 120 ? collapsed.substring(0, 117) + "..." : collapsed;
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
        build.put("sourcesCompiled", outcome.compiledSourceCount());
        build.put("sourcesSetAside", outcome.unbuildableSources().size());
        build.put("sourcesSubstitutedFromAnotherEngine", outcome.substitutedSources().size());
        root.put("build", build);

        root.put("substituted", outcome.substitutedSources());

        // Two separate worklists, because they call for different work. A source defect needs
        // repairing; an API the JDK removed needs a different JDK or a decision, and no amount
        // of editing the decompiled source will help.
        root.put("uncompilableSource", outcome.sourceDefects().stream()
                .map(e -> Map.of(
                        "path", relativeTo(outcome.primarySource(), e.path()),
                        "reason", e.reason() == null ? "" : e.reason()))
                .toList());
        root.put("notRebuildableOnThisJdk", outcome.missingJdkApis().stream()
                .map(e -> Map.of(
                        "path", relativeTo(outcome.primarySource(), e.path()),
                        "reason", e.reason() == null ? "" : e.reason()))
                .toList());

        root.put("cleanupPasses", new LinkedHashMap<String, Object>(outcome.normalization()));

        if (outcome.waivers() != null && (outcome.waivers().size() > 0
                || !outcome.waivers().rejected().isEmpty())) {
            root.put("waivers", Map.of(
                    "loaded", outcome.waivers().size(),
                    "accepted", outcome.gate().waived().stream()
                            .map(ClassVerdict::className).toList(),
                    "matchedNothing", outcome.waivers().stale().stream()
                            .map(w -> Map.of("class", w.className(), "signals", w.signalList()))
                            .toList(),
                    "refused", outcome.waivers().rejected().stream()
                            .map(r -> Map.of("class", r.className(),
                                    "problem", r.reason(), "where", r.source()))
                            .toList()));
        }

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
        if (report.gated() == 0) {
            return "the gate reached no classes: nothing was rebuilt";
        }
        int review = report.needingReview().size();
        String unreached = report.gated() == report.total()
                ? ""
                : ", " + (report.total() - report.gated()) + " not reached";
        if (review == 0) {
            return report.safeCount() + " of " + report.gated() + " classes shown equivalent"
                    + unreached;
        }
        return review + " of " + report.gated() + " classes need review" + unreached;
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static String relativeTo(java.nio.file.Path root, java.nio.file.Path file) {
        try {
            return root.relativize(file).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }
}
