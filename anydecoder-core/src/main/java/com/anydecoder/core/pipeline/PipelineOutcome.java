package com.anydecoder.core.pipeline;

import com.anydecoder.core.build.CompileOutcome;
import com.anydecoder.core.gate.GateReport;
import com.anydecoder.core.ingest.ArtifactSet;

import java.nio.file.Path;
import java.util.Map;

/**
 * What a run produced.
 *
 * @param artifacts        how the input was classified
 * @param sourcesByEngine  decompiled tree per engine, keyed by engine id
 * @param primarySource    the tree the rebuild came from
 * @param compile          the rebuild's result
 * @param gate             the verdicts, and the coverage behind them
 * @param rebuiltClasses   where the rebuilt class files landed
 * @param normalization    how many files each cleanup pass touched
 */
public record PipelineOutcome(
        ArtifactSet artifacts,
        Map<String, Path> sourcesByEngine,
        Path primarySource,
        CompileOutcome compile,
        GateReport gate,
        Path rebuiltClasses,
        Map<String, Integer> normalization) {
}
