package com.anydecoder.cli;

import com.anydecoder.core.engine.ToolCache;
import com.anydecoder.core.pipeline.DecoderPipeline;
import com.anydecoder.core.pipeline.PipelineOptions;
import com.anydecoder.core.pipeline.PipelineOutcome;
import com.anydecoder.core.report.GateReportWriter;
import picocli.CommandLine;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Decompile, rebuild, and gate, in one command.
 *
 * <p>Exit status is part of the interface, because a CI job needs to know whether to fail:
 * {@code 0} when every class the gate reached came out safe, {@code 1} when something needs
 * review. Classes the gate could not reach do not fail the run on their own -- a class file with
 * no matching source is a coverage gap, and it is reported as one rather than dressed up as a
 * defect.
 */
@CommandLine.Command(
        name = "run",
        mixinStandardHelpOptions = true,
        description = "Recover source from a class directory, jar or war, then prove it.")
public final class RunCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", paramLabel = "INPUT",
            description = "A class directory, a .jar or a .war.")
    private Path input;

    @CommandLine.Option(names = {"-o", "--output"},
            description = "Where the report and the recovered source go. Default: ./anydecoder-out")
    private Path output;

    @CommandLine.Option(names = {"--work"},
            description = "Scratch space. Default: <output>/work")
    private Path work;

    @CommandLine.Option(names = {"-e", "--engines"}, split = ",",
            description = "Engines to run, in order; the first supplies the source that gets"
                    + " rebuilt. Default: cfr,vineflower")
    private List<String> engines;

    @CommandLine.Option(names = {"--no-normalize"},
            description = "Skip the source cleanup passes.")
    private boolean noNormalize;

    @CommandLine.Option(names = {"--no-substitute"},
            description = "Do not offer classes the gate was unhappy with to the other engines.")
    private boolean noSubstitute;

    @CommandLine.Option(names = {"--format"},
            description = "Report format: markdown, json, or both. Default: both")
    private String format = "both";

    @CommandLine.Option(names = {"--tools"},
            description = "Directory holding engine jars, if not the bundled ones.")
    private Path tools;

    @CommandLine.Option(names = {"-d", "--deps"}, split = ",",
            description = "Extra jars or directories of jars for the compile classpath."
                    + " Discovery already finds a lib/ directory beside the classes.")
    private List<Path> dependencies;

    @CommandLine.Option(names = {"--waivers"},
            description = "A file of investigated-and-accepted divergences. Defaults to"
                    + " anydecoder-waivers.md beside the classes, if one is there.")
    private Path waivers;

    /**
     * Where the engine jars are extracted to.
     *
     * <p>Beside the run's scratch space rather than in the user's home directory. The engines ship
     * inside anydecoder's own artifact, so the first run copies about five megabytes out of it;
     * putting that under {@code ~/.anydecoder/tools} means a project kept entirely on a second
     * drive still causes something to accumulate on the system drive. Keeping it here makes the
     * whole invocation self-contained in the directories the caller chose.
     *
     * <p>Set {@code -Danydecoder.tools} to put it anywhere else and share one copy between runs.
     */
    private static Path toolsDirectory(Path workDir) {
        String configured = System.getProperty("anydecoder.tools");
        return configured != null && !configured.isBlank()
                ? Path.of(configured)
                : workDir.resolve(".tools");
    }

    @Override
    public Integer call() throws Exception {
        if (!java.nio.file.Files.exists(input)) {
            // A stack trace for a mistyped path is noise, and it buries the one line that says
            // what is wrong. Reported here rather than left to surface from three layers down.
            System.err.println("anydecoder: no such input: " + input);
            return 2;
        }

        Path resolvedOutput = output != null ? output : Path.of("anydecoder-out");
        Path resolvedWork = work != null ? work : resolvedOutput.resolve("work");

        PipelineOptions options = new PipelineOptions(
                input.toAbsolutePath(),
                resolvedWork.toAbsolutePath(),
                resolvedOutput.toAbsolutePath(),
                engines != null && !engines.isEmpty() ? engines : List.of("cfr", "vineflower"),
                !noNormalize,
                !noSubstitute,
                dependencies == null ? List.of() : dependencies.stream()
                        .map(Path::toAbsolutePath).toList(),
                waivers == null ? null : waivers.toAbsolutePath());

        ToolCache cache = new ToolCache(toolsDirectory(resolvedWork),
                java.util.Optional.ofNullable(tools));

        PrintStream log = System.out;
        PipelineOutcome outcome;
        try {
            outcome = new DecoderPipeline(cache).run(options, log);
        } catch (Exception e) {
            // The message, not the trace. Someone who pointed the tool at the wrong directory
            // needs to know that, and three layers of frames do not tell them. Set
            // -Danydecoder.debug to get the trace back when the fault is the tool's.
            System.err.println("anydecoder: " + e.getMessage());
            if (Boolean.getBoolean("anydecoder.debug")) {
                e.printStackTrace();
            }
            return 2;
        }

        Files.createDirectories(resolvedOutput);
        String markdown = GateReportWriter.toMarkdown(outcome);
        String json = GateReportWriter.toJson(outcome);

        if (format.equals("markdown") || format.equals("both")) {
            Path report = resolvedOutput.resolve("report.md");
            Files.writeString(report, markdown, StandardCharsets.UTF_8);
            log.println("report: " + report);
        }
        if (format.equals("json") || format.equals("both")) {
            Path report = resolvedOutput.resolve("report.json");
            Files.writeString(report, json, StandardCharsets.UTF_8);
            log.println("report: " + report);
        }
        log.println("recovered source: " + outcome.primarySource());
        if (format.equals("markdown") || format.equals("both")) {
            log.println();
            log.print(markdown);
        }

        return outcome.gate().needingReview().isEmpty() ? 0 : 1;
    }
}
