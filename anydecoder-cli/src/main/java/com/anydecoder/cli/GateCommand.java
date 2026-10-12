package com.anydecoder.cli;

import com.anydecoder.core.gate.ClassGate;
import com.anydecoder.core.gate.ClassModel;
import com.anydecoder.core.gate.ClassModelReader;
import com.anydecoder.core.gate.ClassVerdict;
import com.anydecoder.core.gate.Divergence;
import com.anydecoder.core.gate.GateReport;
import com.anydecoder.core.gate.HierarchyIndex;
import com.anydecoder.core.ingest.ArtifactScanner;
import com.anydecoder.core.report.Json;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Re-run the gate without decompiling again.
 *
 * <p>This is the loop a repair runs in: edit a source file, recompile, gate, look at what moved.
 * Doing the whole pipeline again would work but would also re-run the decompiler, which is the
 * slow part and the part whose output a repair is deliberately not changing.
 */
@CommandLine.Command(
        name = "gate",
        mixinStandardHelpOptions = true,
        description = "Compare rebuilt classes against the originals, without decompiling.")
public final class GateCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", paramLabel = "ORIGINAL",
            description = "Directory containing the original class files.")
    private Path originalClasses;

    @CommandLine.Parameters(index = "1", paramLabel = "REBUILT",
            description = "Directory containing the rebuilt class files.")
    private Path rebuiltClasses;

    @CommandLine.Parameters(index = "2", arity = "0..*", paramLabel = "CLASS",
            description = "Limit to these class names (internal form, e.g. com/example/Foo)."
                    + " Omit to gate everything.")
    private List<String> classNames;

    @CommandLine.Option(names = {"--format"},
            description = "Output format: markdown, json, or tsv. Default: markdown")
    private String format = "markdown";

    @CommandLine.Option(names = {"--verbose"},
            description = "List every divergence, not just the unexplained ones.")
    private boolean verbose;

    @CommandLine.Option(names = {"--release"},
            description = "Java release used when the rebuild was compiled. Informational.")
    private String release;

    @CommandLine.Option(names = {"-d", "--deps"}, split = ",",
            description = "Dependency jars, so that a receiver narrowed from an interface to its"
                    + " implementation is recognised as the same member. Without them such calls"
                    + " are reported as different, which is noisy but not wrong.")
    private List<Path> dependencies;

    @Override
    public Integer call() throws Exception {
        List<Path> originals = selectOriginals();
        if (originals.isEmpty()) {
            System.err.println("no class files matched under " + originalClasses);
            return 2;
        }

        List<Path> hierarchyClasspath = new ArrayList<>();
        hierarchyClasspath.add(originalClasses);
        if (dependencies != null) {
            // A directory means "the jars in it", which is what a person means by a lib folder.
            hierarchyClasspath.addAll(ArtifactScanner.expandDependencies(dependencies));
        }
        ClassGate gate = new ClassGate(new HierarchyIndex(hierarchyClasspath));
        List<ClassVerdict> verdicts = originals.parallelStream()
                .map(original -> gateOne(gate, original))
                .sorted(Comparator.comparing(ClassVerdict::className))
                .toList();
        GateReport report = new GateReport(verdicts);

        switch (format.toLowerCase()) {
            case "json" -> System.out.println(renderJson(report));
            case "tsv" -> System.out.println(renderTsv(report));
            default -> System.out.println(renderMarkdown(report));
        }
        return report.needingReview().isEmpty() ? 0 : 1;
    }

    private List<Path> selectOriginals() throws Exception {
        try (Stream<Path> stream = Files.walk(originalClasses)) {
            return stream.filter(p -> p.toString().endsWith(".class"))
                    .filter(this::selected)
                    .sorted()
                    .toList();
        }
    }

    private boolean selected(Path classFile) {
        if (classNames == null || classNames.isEmpty()) {
            return true;
        }
        String relative = originalClasses.relativize(classFile).toString();
        String internal = relative.substring(0, relative.length() - ".class".length())
                .replace('\\', '/');
        return classNames.stream().anyMatch(wanted ->
                internal.equals(wanted) || internal.startsWith(wanted + "$"));
    }

    private ClassVerdict gateOne(ClassGate gate, Path original) {
        String relative = originalClasses.relativize(original).toString();
        String className = relative.substring(0, relative.length() - ".class".length())
                .replace('\\', '/');
        Path rebuilt = rebuiltClasses.resolve(relative);
        if (!Files.isReadable(rebuilt)) {
            return ClassVerdict.harnessError(className, "no rebuilt class at " + rebuilt);
        }
        try {
            ClassModel from = ClassModelReader.read(original);
            ClassModel to = ClassModelReader.read(rebuilt);
            return gate.compare(from, to);
        } catch (Exception e) {
            return ClassVerdict.harnessError(className, "could not read a class file: " + e.getMessage());
        }
    }

    private String renderTsv(GateReport report) {
        StringBuilder sb = new StringBuilder("verdict\tclass\tfindings\n");
        for (ClassVerdict verdict : report.verdicts()) {
            String findings = verdict.unexplained().stream()
                    .map(d -> d.signalId() + ":" + d.member())
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
            sb.append(verdict.verdict()).append('\t')
                    .append(verdict.className()).append('\t')
                    .append(findings).append('\n');
        }
        return sb.toString();
    }

    private String renderJson(GateReport report) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> counts = new LinkedHashMap<>();
        report.counts().forEach((verdict, count) -> counts.put(verdict.name(), count));
        root.put("counts", counts);
        root.put("classesFound", report.total());
        root.put("classesGated", report.gated());
        root.put("coverage", Math.round(report.coverage() * 10000.0) / 10000.0);

        List<Object> classes = new ArrayList<>();
        for (ClassVerdict verdict : report.verdicts()) {
            if (verdict.verdict() == com.anydecoder.core.gate.Verdict.EQUIVALENT) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("class", verdict.className());
            entry.put("verdict", verdict.verdict().name());
            if (verdict.note() != null) {
                entry.put("note", verdict.note());
            }
            List<Object> findings = new ArrayList<>();
            for (Divergence d : verbose ? verdict.divergences() : verdict.unexplained()) {
                Map<String, Object> finding = new LinkedHashMap<>();
                finding.put("signal", d.signalId());
                finding.put("severity", d.severity().name());
                finding.put("member", d.member());
                finding.put("detail", d.detail());
                // The original and rebuilt values side by side. Without them a reader has to
                // re-derive the comparison by hand to judge whether a finding is real, which
                // defeats the point of reporting evidence at all.
                if (!d.evidence().isEmpty()) {
                    finding.put("evidence", new LinkedHashMap<String, Object>(d.evidence()));
                }
                findings.add(finding);
            }
            entry.put("findings", findings);
            if (!verdict.explanations().isEmpty()) {
                entry.put("explanations", verdict.explanations().stream()
                        .map(e -> Map.of("category", e.category(), "reason", e.reason()))
                        .toList());
            }
            classes.add(entry);
        }
        root.put("classes", classes);
        return Json.write(root);
    }

    /**
     * A review list, ordered so a reader can work down it.
     *
     * <p>Evidence is included for every finding. A report that says only "the call targets differ"
     * leaves the reader to re-derive the comparison before they can even judge the claim, which
     * is most of the work; showing both sides makes the judgement cheap and the finding
     * falsifiable.
     *
     * <p>Ordering is by cause rather than alphabetically: a class whose exception table changed is
     * more likely to be a real defect than one where a field is written a different number of
     * times, and burying the first among the second wastes the reader's attention.
     */
    private String renderMarkdown(GateReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Gate report\n\n");

        sb.append("| verdict | count |\n|---|---:|\n");
        report.counts().forEach((verdict, count) ->
                sb.append("| ").append(verdict).append(" | ").append(count).append(" |\n"));
        sb.append('\n');
        sb.append("- classes gated: ").append(report.gated()).append(" of ").append(report.total())
                .append('\n');
        if (report.gated() < report.total()) {
            sb.append("- **").append(report.total() - report.gated())
                    .append(" class(es) were never reached and are not passes**\n");
        }
        if (release != null) {
            sb.append("- rebuilt at release: ").append(release).append('\n');
        }
        sb.append('\n');

        List<ClassVerdict> review = report.needingReview();
        if (review.isEmpty()) {
            sb.append("Nothing needs review.\n");
            return sb.toString();
        }

        Map<String, List<ClassVerdict>> byCause = new java.util.TreeMap<>();
        for (ClassVerdict verdict : review) {
            for (Divergence divergence : verdict.unexplained()) {
                byCause.computeIfAbsent(divergence.signalId(), k -> new ArrayList<>()).add(verdict);
            }
        }

        sb.append("## By cause\n\n");
        sb.append("| signal | classes | what it means |\n|---|---:|---|\n");
        byCause.forEach((signal, classes) -> sb.append("| `").append(signal).append("` | ")
                .append(classes.stream().distinct().count()).append(" | ")
                .append(CAUSE.getOrDefault(signal, "")).append(" |\n"));
        sb.append('\n');

        for (ClassVerdict verdict : review) {
            sb.append("### ").append(verdict.verdict()).append(" `")
                    .append(verdict.className().replace('/', '.')).append("`\n\n");
            if (verdict.note() != null) {
                sb.append("> ").append(verdict.note()).append("\n\n");
            }
            for (Divergence divergence : verdict.unexplained()) {
                sb.append("- **").append(divergence.signalId()).append("** (")
                        .append(divergence.severity()).append(") in `")
                        .append(divergence.member()).append("`\n");
                sb.append("  - ").append(divergence.detail()).append('\n');
                evidenceLines(divergence, sb);
            }
            sb.append('\n');
        }
        return sb.toString();
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

    private static void evidenceLines(Divergence divergence, StringBuilder sb) {
        for (Map.Entry<String, String> entry : divergence.evidence().entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            sb.append("    - ").append(entry.getKey()).append(": `")
                    .append(entry.getValue().replace("`", "'")).append("`\n");
        }
    }
}
