package com.anydecoder.core.engine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CFR, the default engine.
 *
 * <p>Pinned at 0.152, the version whose failure modes are documented in
 * {@code docs/pitfalls.md}. It is the engine the gate's benign-divergence categories were
 * measured against, so changing the version is a change to what anydecoder validates, not just
 * a dependency bump.
 *
 * <p>CFR is given no classpath by default. Handing it one improves the types it infers, but it
 * can only resolve types that are on the classpath, and getting that wrong produces confident
 * nonsense rather than an honest gap. The pipeline records which mode was used, because the two
 * produce materially different output.
 */
public final class CfrDecompiler extends AbstractJarDecompiler {

    public static final ToolArtifact ARTIFACT = new ToolArtifact(
            "cfr",
            "0.152",
            "cfr-0.152.jar",
            "org.benf.cfr.reader.Main",
            "org/benf/cfr/0.152/cfr-0.152.jar",
            "cfr-0.152.jar");

    public CfrDecompiler(ToolCache cache) {
        super(ARTIFACT, cache);
    }

    @Override
    protected List<String> arguments(Path jar, Path input, Path outputDir, List<Path> classpath) {
        List<String> args = new ArrayList<>();
        args.add(input.toString());
        args.add("--outputdir");
        args.add(outputDir.toString());
        // The originals were compiled on a case-insensitive filesystem; without this CFR can
        // refuse to write a class whose name differs only in case from another.
        args.add("--caseinsensitivefs");
        args.add("true");
        args.add("--silent");
        args.add("true");
        for (Path entry : classpath) {
            args.add("--extraclasspath");
            args.add(entry.toString());
        }
        return args;
    }
}
