package com.anydecoder.core.engine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Procyon, consulted on individual classes rather than run across a whole project.
 *
 * <p>It is a third opinion on shape, not a source of truth: its try/catch/finally rendering is
 * unreliable, and a repair driven by its output will produce bytecode that fails the gate. Use
 * it to see an alternative reading of a method, then check the answer against the exception
 * table, which is what actually encodes the original structure.
 *
 * <p>{@code procyon-compilertools} has no usable manifest entry point, so it is started by class
 * name rather than with {@code -jar}.
 */
public final class ProcyonDecompiler extends AbstractJarDecompiler {

    public static final ToolArtifact ARTIFACT = new ToolArtifact(
            "procyon",
            "0.6.0",
            "procyon-compilertools-0.6.0.jar",
            "com.strobel.decompiler.DecompilerDriver",
            "org/bitbucket/mstrobel/procyon-compilertools/0.6.0/procyon-compilertools-0.6.0.jar",
            "procyon-compilertools-0.6.0.jar");

    public ProcyonDecompiler(ToolCache cache) {
        super(ARTIFACT, cache);
    }

    @Override
    protected List<String> invocationPrefix(Path jar) {
        return List.of("-cp", jar.toString(), ARTIFACT.mainClass());
    }

    @Override
    protected List<String> arguments(Path jar, Path input, Path outputDir, List<Path> classpath) {
        List<String> args = new ArrayList<>();
        args.add("-o");
        args.add(outputDir.toString());
        args.add("-jrt");
        args.add("1");
        for (Path entry : classpath) {
            args.add("-cp");
            args.add(entry.toString());
        }
        args.add(input.toString());
        return args;
    }
}
