package com.anydecoder.core.build;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
 * production web application produced 100 compiler errors, every one of them in the source rather
 * than in the tooling. Waiting for all 100 to be repaired before checking <em>any</em> class is
 * the wrong shape for the work, which is iterative by nature.
 *
 * <h2>How it works</h2>
 *
 * <p>Compile everything; if it fails, remove the files the compiler complained about and compile
 * again. Repeat until it succeeds or stops making progress. References from the remaining files
 * to the removed ones resolve against the original class files on the classpath, so removing a
 * broken file does not cascade.
 *
 * <p>This costs a handful of whole-tree compiles rather than the thousands an isolated
 * per-file compile would, and it produces the thing actually needed: the list of files that
 * require repair, separated from the ones that are already fine.
 */
public final class SourceTreeCompiler {

    /** Enough rounds to peel any realistic number of defective files without looping. */
    private static final int MAX_ROUNDS = 40;

    private final ProjectCompiler compiler;

    public SourceTreeCompiler() {
        this(new ProjectCompiler());
    }

    public SourceTreeCompiler(ProjectCompiler compiler) {
        this.compiler = compiler;
    }

    /**
     * @param compile   the final compilation, which succeeded unless {@code exhausted}
     * @param excluded  sources removed because they would not compile
     * @param exhausted true when progress stopped while errors remained, so the rebuild covers
     *                  neither the excluded files nor whatever else the compiler was still
     *                  complaining about
     * @param rounds    how many compilation attempts were needed
     */
    public record Result(CompileOutcome compile, List<Path> excluded, boolean exhausted, int rounds) {

        /**
         * Whether the final compilation produced class files.
         *
         * <p>True after a successful peel, which is the normal case on raw decompiler output:
         * the tree compiles once the files that cannot compile are set aside. Those files are
         * not covered, and {@link #excluded()} is the list of them -- a caller that treats
         * this as "everything was checked" would be wrong, which is why coverage is reported
         * from the gate rather than from here.
         */
        public boolean succeeded() {
            return !exhausted;
        }
    }

    public Result compile(List<Path> sources, Path outputDir, List<Path> classpath, String release)
            throws IOException {
        return compile(sources, outputDir, classpath, release, null);
    }

    /**
     * @param progress optional sink for a one-line note each round, so a long peel is not silent
     */
    public Result compile(List<Path> sources, Path outputDir, List<Path> classpath, String release,
                          java.util.function.Consumer<String> progress) throws IOException {
        List<Path> remaining = new ArrayList<>(sources);
        Set<Path> excluded = new LinkedHashSet<>();
        CompileOutcome outcome = null;

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            outcome = compiler.compile(remaining, outputDir, classpath, release);
            if (outcome.success()) {
                return new Result(outcome, List.copyOf(excluded), false, round);
            }

            Set<Path> failing = new LinkedHashSet<>();
            for (CompileOutcome.Diagnostic diagnostic : outcome.errors()) {
                if (diagnostic.file() != null) {
                    failing.add(diagnostic.file().toAbsolutePath().normalize());
                }
            }
            // Errors with no file attached are a bad option or a missing symbol at the
            // invocation level; removing sources will not help, so stop rather than spin.
            if (failing.isEmpty()) {
                return new Result(outcome, List.copyOf(excluded), true, round);
            }

            List<Path> next = new ArrayList<>();
            for (Path source : remaining) {
                if (!failing.contains(source.toAbsolutePath().normalize())) {
                    next.add(source);
                }
            }
            if (next.size() == remaining.size()) {
                // The compiler complained about files that are not in the input. Nothing to do.
                return new Result(outcome, List.copyOf(excluded), true, round);
            }

            excluded.addAll(failing);
            remaining = next;
            if (progress != null) {
                progress.accept("removed " + failing.size() + " file(s) that would not compile; "
                        + remaining.size() + " remain (round " + round + ")");
            }
        }

        return new Result(outcome, List.copyOf(excluded), true, MAX_ROUNDS);
    }
}
