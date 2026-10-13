package com.anydecoder.cli;

import com.anydecoder.core.difftest.DifferentialTester;
import com.anydecoder.core.difftest.DifferentialTesterOptions;
import com.anydecoder.core.difftest.Divergence;
import com.anydecoder.core.ingest.ArtifactScanner;
import com.anydecoder.core.report.Json;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Calls both versions of a method and compares what they do.
 *
 * <p>The gate compares structure, and there is one thing structure cannot see: two statements of
 * the same kind, swapped in order, produce an identical instruction multiset. An operand swap in
 * {@code a - b} is invisible to it. This observes behaviour instead, which is the only way to
 * close that gap.
 *
 * <p>It runs real code, which is why it is not something the pipeline does by itself. Only public
 * methods whose parameters are primitives, boxed primitives or strings are driven — nothing that
 * would need an arbitrary object constructed for it — and each call gets a fresh receiver. A
 * method that writes files or opens sockets will still write files or open sockets.
 */
@CommandLine.Command(
        name = "difftest",
        mixinStandardHelpOptions = true,
        description = "Run both versions of a method and compare what they do. Covers the one"
                + " difference a bytecode comparison cannot see.")
public final class DiffTestCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", paramLabel = "ORIGINAL",
            description = "Directory containing the original class files.")
    private Path originalClasses;

    @CommandLine.Parameters(index = "1", paramLabel = "REBUILT",
            description = "Directory containing the rebuilt class files.")
    private Path rebuiltClasses;

    @CommandLine.Option(names = {"-d", "--deps"}, split = ",",
            description = "Dependency jars, needed to load the classes at all.")
    private List<Path> dependencies;

    @CommandLine.Option(names = {"--seed"},
            description = "Fixes the generated argument sequence, so a divergence found in CI can"
                    + " be reproduced exactly. A fuzz that cannot be replayed is a rumour.")
    private Long seed;

    @CommandLine.Option(names = {"--max-parameters"},
            description = "Skip methods with more parameters than this. The number of"
                    + " combinations grows exponentially and the later ones stop being"
                    + " meaningful long before they stop being slow. Default: 4")
    private Integer maxParameters;

    @CommandLine.Option(names = {"--format"},
            description = "Output format: markdown, json, or tsv. Default: markdown")
    private String format = "markdown";

    @Override
    public Integer call() throws Exception {
        if (!java.nio.file.Files.isDirectory(originalClasses)
                || !java.nio.file.Files.isDirectory(rebuiltClasses)) {
            System.err.println("anydecoder: both arguments must be directories of class files");
            return 2;
        }
        List<Path> jars = dependencies == null
                ? List.of()
                : ArtifactScanner.expandDependencies(dependencies);

        DifferentialTesterOptions options = new DifferentialTesterOptions(
                seed != null ? seed : DifferentialTesterOptions.defaults().seed(),
                maxParameters != null ? maxParameters : DifferentialTesterOptions.defaults().maxParameters());

        DifferentialTester.Result result =
                new DifferentialTester(options).run(originalClasses, rebuiltClasses, jars);

        switch (format.toLowerCase()) {
            case "json" -> System.out.println(renderJson(result, options));
            case "tsv" -> System.out.println(renderTsv(result));
            default -> System.out.println(renderMarkdown(result, options));
        }
        return result.clean() ? 0 : 1;
    }

    private String renderTsv(DifferentialTester.Result result) {
        StringBuilder sb = new StringBuilder("class\tmethod\toriginal\trebuilt\n");
        for (Divergence divergence : result.divergences()) {
            sb.append(divergence.className()).append('\t')
                    .append(divergence.method()).append('\t')
                    .append(divergence.originalResult()).append('\t')
                    .append(divergence.rebuiltResult()).append('\n');
        }
        return sb.toString();
    }

    private String renderJson(DifferentialTester.Result result, DifferentialTesterOptions options) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("seed", options.seed());
        root.put("classesExamined", result.classesExamined());
        root.put("methodsExercised", result.methodsExercised());
        root.put("casesRun", result.casesRun());
        root.put("divergences", result.divergences().stream()
                .map(d -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("class", d.className());
                    entry.put("method", d.method());
                    entry.put("arguments", d.arguments());
                    entry.put("original", d.originalResult());
                    entry.put("rebuilt", d.rebuiltResult());
                    return (Object) entry;
                })
                .toList());
        // Why a method was skipped matters as much as the result: a run that drove nothing is not
        // evidence of equivalence, and the reasons say how much of the tree was actually reached.
        root.put("skipped", result.skipped());
        return Json.write(root);
    }

    private String renderMarkdown(DifferentialTester.Result result, DifferentialTesterOptions options) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Differential test\n\n");
        sb.append("- seed: `").append(options.seed()).append("`\n");
        sb.append("- classes examined: ").append(result.classesExamined()).append('\n');
        sb.append("- methods exercised: ").append(result.methodsExercised()).append('\n');
        sb.append("- arguments tried: ").append(result.casesRun()).append('\n');
        sb.append('\n');

        if (result.clean()) {
            sb.append("No divergence.\n\n");
            sb.append("**This is not the same as equivalence.** Only methods that can be driven"
                    + " from primitives, boxed primitives and strings are called; everything else"
                    + " is listed below as skipped.\n");
        } else {
            sb.append("## Divergences\n\n");
            for (Divergence divergence : result.divergences()) {
                sb.append("- `").append(divergence.className().replace('/', '.'))
                        .append('.').append(divergence.method()).append("`")
                        .append(" — original `").append(divergence.originalResult())
                        .append("`, rebuild `").append(divergence.rebuiltResult())
                        .append("` with ").append(divergence.arguments()).append('\n');
            }
            sb.append('\n');
        }

        if (!result.skipped().isEmpty()) {
            sb.append("## Not driven\n\n");
            result.skipped().stream().limit(40).forEach(s -> sb.append("- ").append(s).append('\n'));
            if (result.skipped().size() > 40) {
                sb.append("- ... and ").append(result.skipped().size() - 40).append(" more\n");
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}