package com.anydecoder.core.pipeline;

import java.nio.file.Path;
import java.util.List;

/**
 * Everything a run needs to know.
 *
 * @param input     a class directory, a jar, or a war
 * @param workDir   scratch space for the decompiled trees and the rebuild
 * @param outputDir where the report and the recovered source are written
 * @param engines   engine ids to run, in order; the first is the primary source tree
 * @param enableNormalization whether to run the source cleanup passes
 * @param dependencyJars extra jars to put on the compile classpath and offer to the engines.
 *                  Discovery finds jars sitting in a {@code lib/} beside the classes, which
 *                  covers an exploded web application; a project that keeps its jars
 *                  elsewhere -- behind a different directory name, or resolved by a build tool
 *                  -- needs to say so, and without them the rebuild fails on symbols that were
 *                  never missing.
 */
public record PipelineOptions(
        Path input,
        Path workDir,
        Path outputDir,
        List<String> engines,
        boolean enableNormalization,
        List<Path> dependencyJars) {

    public PipelineOptions {
        dependencyJars = List.copyOf(dependencyJars);
    }

    public static PipelineOptions defaults(Path input, Path workDir, Path outputDir) {
        return new PipelineOptions(input, workDir, outputDir,
                List.of("cfr", "vineflower"), true, List.of());
    }

    public PipelineOptions withEngines(String... engineIds) {
        return new PipelineOptions(input, workDir, outputDir, List.of(engineIds),
                enableNormalization, dependencyJars);
    }

    public PipelineOptions withNormalization(boolean enabled) {
        return new PipelineOptions(input, workDir, outputDir, engines, enabled, dependencyJars);
    }

    public PipelineOptions withDependencies(List<Path> jars) {
        return new PipelineOptions(input, workDir, outputDir, engines, enableNormalization, jars);
    }
}
