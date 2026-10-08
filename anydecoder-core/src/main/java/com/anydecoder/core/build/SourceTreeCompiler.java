package com.anydecoder.core.build;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Compiles a source tree, working around files that will not compile.
 *
 * <h2>Why this exists</h2>
 *
 * <p>javac emits no class files at all when the compilation reports an error. One broken file out
 * of two thousand five hundred therefore leaves the gate with nothing to compare, and a run
 * reports zero coverage — not because the decompiled source is bad, but because the harness gave
 * up.
 *
 * <p>Raw decompiler output on any real project contains such files. Decompiling 2451 files of a
 * production web application produced compiler errors in several hundred of them, every one in
 * the source rather than in the tooling. Waiting for all of them to be repaired before checking
 * <em>any</em> class is the wrong shape for the work, which is iterative by nature.
 *
 * <h2>How it works</h2>
 *
 * <p>Compile everything; if that fails, take the files the compiler named and either substitute
 * another engine's rendering of them or set them aside, then compile again. Repeat until it
 * succeeds or stops making progress. References from the remaining files to the ones set aside
 * resolve against the original class files on the classpath, so setting one aside does not
 * cascade.
 *
 * <p>javac's phases explain why this takes more than two rounds: nothing is attributed while any
 * file fails to parse, so the errors a round can see depend on what the previous round removed.
 */
public final class SourceTreeCompiler {

    /**
     * Enough rounds to converge without looping.
     *
     * <p>Measured at 21 on a corpus of 2451 files with no second engine available: 53 had parse
     * errors, 280 more failed attribution once the parse errors were gone, and the rest surfaced
     * one at a time. With a fallback engine most of those are replaced rather than dropped, which
     * takes far fewer rounds.
     */
    private static final int MAX_ROUNDS = 60;

    private final ProjectCompiler compiler;

    public SourceTreeCompiler() {
        this(new ProjectCompiler());
    }

    public SourceTreeCompiler(ProjectCompiler compiler) {
        this.compiler = compiler;
    }

    /**
     * A source file the rebuild could not use.
     *
     * @param path   the file
     * @param reason the compiler's first complaint about it
     */
    public record ExcludedSource(Path path, String reason) {

        /**
         * Whether the failure is the JDK's doing rather than the source's.
         *
         * <p>Source that calls {@code sun.misc.BASE64Encoder} cannot be rebuilt on a modern JDK
         * at all: the class was removed in Java 9, and neither {@code --release 8} nor
         * {@code -source 8} brings it back, because it is not part of that API. That is a
         * property of the environment, not evidence of a bad decompilation, and reporting the
         * two together would mislead a reader into repairing source that is fine.
         */
        public boolean isMissingJdkApi() {
            String lower = reason == null ? "" : reason.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("package sun.")
                    || lower.contains("package com.sun.")
                    || lower.contains("package jdk.")
                    || lower.contains("package org.omg.")
                    || lower.contains("package org.ietf.jgss");
        }
    }

    /**
     * @param compile      the final compilation, which succeeded unless {@code exhausted}
     * @param excluded     sources the rebuild could not use, with the compiler's reason
     * @param substituted  how many files were rescued by the fallback engine
     * @param exhausted    true when progress stopped while errors remained
     * @param rounds       how many compilation attempts were needed
     */
    public record Result(CompileOutcome compile, List<ExcludedSource> excluded,
                         int substituted, boolean exhausted, int rounds) {

        /** Whether the final compilation produced class files. */
        public boolean succeeded() {
            return !exhausted;
        }

        public List<ExcludedSource> missingJdkApi() {
            return excluded.stream().filter(ExcludedSource::isMissingJdkApi).toList();
        }

        /** Failures that are about the source rather than about the JDK it was built against. */
        public List<ExcludedSource> sourceDefects() {
            return excluded.stream().filter(e -> !e.isMissingJdkApi()).toList();
        }
    }

    public Result compile(List<Path> sources, Path outputDir, List<Path> classpath, String release)
            throws IOException {
        return compile(sources, null, outputDir, classpath, release, SourceFallback.none(), null);
    }

    /**
     * @param sourceRoot the root the sources live under, needed to locate a fallback rendering
     * @param fallback   consulted before a file is given up on
     * @param progress   optional sink for a one-line note each round
     */
    public Result compile(List<Path> sources, Path sourceRoot, Path outputDir, List<Path> classpath,
                          String release, SourceFallback fallback, Consumer<String> progress)
            throws IOException {
        List<Path> remaining = new ArrayList<>(sources);
        Map<Path, String> excluded = new LinkedHashMap<>();
        Set<Path> alreadyTriedFallback = new LinkedHashSet<>();
        int substituted = 0;
        CompileOutcome outcome = null;

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            if (remaining.isEmpty()) {
                // Everything was set aside. That is the peel converging, not failing: there is
                // simply nothing left to compile, and asking javac to compile an empty list
                // would report an error about the invocation rather than about any source.
                return new Result(new CompileOutcome(true, List.of(), outputDir),
                        toExcluded(excluded), substituted, false, round);
            }
            outcome = compiler.compile(remaining, outputDir, classpath, release);
            if (outcome.success()) {
                return new Result(outcome, toExcluded(excluded), substituted, false, round);
            }

            Map<Path, String> failing = firstErrorPerFile(outcome);
            if (failing.isEmpty()) {
                // Errors with no file attached are about the invocation, not the source.
                return new Result(outcome, toExcluded(excluded), substituted, true, round);
            }

            List<Path> next = new ArrayList<>();
            boolean progressed = false;
            int replacedThisRound = 0;

            for (Path source : remaining) {
                String reason = failing.get(source.toAbsolutePath().normalize());
                if (reason == null) {
                    next.add(source);
                    continue;
                }
                if (sourceRoot != null && !alreadyTriedFallback.contains(source)
                        && fallback.replace(source, sourceRoot)) {
                    alreadyTriedFallback.add(source);
                    next.add(source);
                    substituted++;
                    replacedThisRound++;
                    progressed = true;
                    continue;
                }
                excluded.put(source, reason);
                progressed = true;
            }

            remaining = next;
            if (progress != null) {
                progress.accept("round " + round + ": replaced " + replacedThisRound
                        + ", set aside " + excluded.size() + ", " + remaining.size() + " remain");
            }
            if (!progressed) {
                return new Result(outcome, toExcluded(excluded), substituted, true, round);
            }
        }

        return new Result(outcome, toExcluded(excluded), substituted, true, MAX_ROUNDS);
    }

    private static Map<Path, String> firstErrorPerFile(CompileOutcome outcome) {
        Map<Path, String> firstError = new LinkedHashMap<>();
        for (CompileOutcome.Diagnostic diagnostic : outcome.errors()) {
            if (diagnostic.file() == null) {
                continue;
            }
            firstError.putIfAbsent(diagnostic.file().toAbsolutePath().normalize(),
                    diagnostic.message());
        }
        return firstError;
    }

    private static List<ExcludedSource> toExcluded(Map<Path, String> excluded) {
        List<ExcludedSource> result = new ArrayList<>();
        excluded.forEach((path, reason) -> result.add(new ExcludedSource(path, reason)));
        result.sort(java.util.Comparator.comparing(e -> e.path().toString()));
        return List.copyOf(result);
    }
}
