package com.anydecoder.core.engine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Vineflower, used as the second opinion.
 *
 * <p>Vineflower and CFR fail on different inputs. That is the whole reason to run two: where
 * they agree the source is very likely right, and where they disagree the difference points at
 * exactly the methods worth looking at. The gate decides; Vineflower only supplies a candidate.
 *
 * <p>Vineflower is given the classpath when one is available, because unlike CFR it uses it
 * aggressively and produces noticeably better generics when it can see the referenced types.
 */
public final class VineflowerDecompiler extends AbstractJarDecompiler {

    public static final ToolArtifact ARTIFACT = new ToolArtifact(
            "vineflower",
            "1.11.1",
            "vineflower-1.11.1.jar",
            "org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler",
            "org/vineflower/vineflower/1.11.1/vineflower-1.11.1.jar",
            "vineflower-1.11.1.jar");

    public VineflowerDecompiler(ToolCache cache) {
        super(ARTIFACT, cache);
    }

    /**
     * Vineflower builds a whole-project view before emitting anything, so its peak memory is a
     * property of the input rather than of the class being written. At the 1 GB the other engines
     * manage with, it abandons individual methods on {@code OutOfMemoryError} and carries on --
     * producing a tree with silent holes rather than an error.
     */
    @Override
    protected String defaultHeap() {
        return "2g";
    }

    @Override
    protected List<String> arguments(Path jar, Path input, Path outputDir, List<Path> classpath) {
        List<String> args = new ArrayList<>();
        // Keep the source as close to what the compiler saw as the defaults allow: no
        // inlining of constants, no invented variable names beyond what is needed.
        args.add("-dgs=1");
        args.add("-asc=1");
        for (Path entry : classpath) {
            args.add("-e=" + entry);
        }
        args.add(input.toString());
        args.add(outputDir.toString());
        return args;
    }
}
