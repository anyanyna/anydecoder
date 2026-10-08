package com.anydecoder.core.pipeline;

import com.anydecoder.core.build.CompileOutcome;
import com.anydecoder.core.gate.GateReport;
import com.anydecoder.core.ingest.ArtifactSet;

import java.nio.file.Path;
import java.util.Map;
import java.util.List;

/**
 * What a run produced.
 *
 * @param artifacts          how the input was classified
 * @param sourcesByEngine    decompiled tree per engine, keyed by engine id
 * @param primarySource      the tree the rebuild came from
 * @param compile            the rebuild's result
 * @param unbuildableSources sources removed because they would not compile. Their classes are
 *                           absent from the rebuild, so the gate scores them as not reached --
 *                           which is the honest outcome, and the worklist for a repair pass.
 * @param gate               the verdicts, and the coverage behind them
 * @param rebuiltClasses     where the rebuilt class files landed
 * @param normalization      how many files each cleanup pass touched
 */
public record PipelineOutcome(
        ArtifactSet artifacts,
        Map<String, Path> sourcesByEngine,
        Path primarySource,
        CompileOutcome compile,
        List<Path> unbuildableSources,
        GateReport gate,
        Path rebuiltClasses,
        Map<String, Integer> normalization) {

    /** Sources that made it into the rebuild. */
    public int compiledSourceCount() {
        try {
            return DecoderPipeline.javaFiles(primarySource).size() - unbuildableSources.size();
        } catch (Exception e) {
            return -1;
        }
    }
}
