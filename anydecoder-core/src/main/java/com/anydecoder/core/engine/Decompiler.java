package com.anydecoder.core.engine;

import java.nio.file.Path;
import java.util.List;

/**
 * One third-party decompiler.
 *
 * <p>Engines are launched as separate processes rather than called in-process. Each ships its
 * own copies of shared libraries, its own idea of how much heap it needs, and some of them call
 * {@code System.exit}; running them on a subprocess keeps those concerns out of anydecoder and
 * lets one engine fail without taking the run down.
 */
public interface Decompiler {

    /** Short stable identifier, used in directory names and reports. */
    String id();

    String version();

    /** The jar to launch. */
    ToolArtifact artifact();

    /**
     * Decompile everything under {@code input} into {@code outputDir}.
     *
     * @param input      a directory of class files, or a jar
     * @param outputDir  where the .java files go; created if absent
     * @param classpath  extra entries the engine should use to resolve referenced types. An
     *                   engine given a good classpath produces better types and fewer
     *                   {@code Could not load} stubs, but must still work without one.
     * @param log        receives the engine's stdout and stderr
     */
    void decompile(Path input, Path outputDir, List<Path> classpath, java.io.PrintStream log)
            throws DecompilerException;
}
