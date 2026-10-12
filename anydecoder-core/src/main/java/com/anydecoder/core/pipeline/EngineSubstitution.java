package com.anydecoder.core.pipeline;

import com.anydecoder.core.build.CompileOutcome;
import com.anydecoder.core.build.ProjectCompiler;
import com.anydecoder.core.gate.ClassGate;
import com.anydecoder.core.gate.ClassVerdict;
import com.anydecoder.core.gate.Verdict;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tries another engine's rendering of a class the gate was unhappy with.
 *
 * <p>The fallback that runs during the rebuild rescues files that will not <em>compile</em>. This
 * rescues files that compile but do not <em>match</em> — a decompiler can lose a `try` block or
 * drop a cast and still produce something javac accepts.
 *
 * <p>It is worth attempting because the engines fail differently. Where one misplaced a block, the
 * other often did not, and the cheapest possible repair is to use the one that got it right. The
 * substitution is not accepted on the strength of that hope: the file is recompiled and the class
 * re-gated, and the swap is kept only if the verdict actually improves. Anything else is reverted,
 * including the compiled output, so a failed attempt leaves nothing behind.
 *
 * <p>The engine that repaired a class is recorded. A tree assembled from two engines is worth
 * knowing about when reading the verdicts — the gate says whether the result is faithful, not
 * which tool wrote it.
 */
public final class EngineSubstitution {

    /** What happened to one class that was attempted. */
    public record Attempt(String className, Verdict before, Verdict after, String engine, boolean kept) {
    }

    private final ClassGate gate;
    private final ProjectCompiler compiler;
    private final Path originalClasses;
    private final Path classesDir;
    private final List<Path> classpath;
    private final String release;

    public EngineSubstitution(ClassGate gate, ProjectCompiler compiler, Path originalClasses,
                              Path classesDir, List<Path> classpath, String release) {
        this.gate = gate;
        this.compiler = compiler;
        this.originalClasses = originalClasses;
        this.classesDir = classesDir;
        this.classpath = List.copyOf(classpath);
        this.release = release;
    }

    /**
     * @param primary      the tree being built, which a successful substitution edits in place
     * @param alternatives other engines' trees, tried in order
     * @param verdicts     the classes the gate was unhappy with
     */
    public List<Attempt> tryImprove(Path primary, List<Path> alternatives,
                                    List<ClassVerdict> verdicts, PrintStream log) throws IOException {
        if (alternatives.isEmpty()) {
            return List.of();
        }

        List<Attempt> attempts = new ArrayList<>();
        Map<String, Path> tried = new LinkedHashMap<>();

        for (ClassVerdict verdict : verdicts) {
            Path source = sourceFileFor(primary, verdict.className());
            if (source == null || tried.containsKey(source.toString())) {
                continue;
            }
            tried.put(source.toString(), source);
            attemptOne(primary, alternatives, source, verdict, attempts, log);
        }
        return List.copyOf(attempts);
    }

    private void attemptOne(Path primary, List<Path> alternatives, Path source,
                            ClassVerdict before, List<Attempt> attempts, PrintStream log)
            throws IOException {
        String originalText = Files.readString(source, StandardCharsets.UTF_8);
        Path relative = primary.relativize(source);

        for (Path alternative : alternatives) {
            Path candidate = alternative.resolve(relative);
            if (!Files.isReadable(candidate)) {
                continue;
            }
            String alternativeText = Files.readString(candidate, StandardCharsets.UTF_8);
            if (alternativeText.equals(originalText)) {
                continue;
            }

            Files.writeString(source, alternativeText, StandardCharsets.UTF_8);
            CompileOutcome compiled = compiler.compile(List.of(source), classesDir, classpath, release);
            if (!compiled.success()) {
                restore(source, originalText);
                rebuild(primary, source);
                continue;
            }

            ClassVerdict after = regate(before.className());
            if (after.verdict().isSafe() && !before.verdict().isSafe()) {
                attempts.add(new Attempt(before.className(), before.verdict(), after.verdict(),
                        alternative.getFileName().toString(), true));
                log.printf("[substitute] %s: %s -> %s from %s%n",
                        before.className(), before.verdict(), after.verdict(),
                        alternative.getFileName());
                return;
            }

            // Not better. Put the source back and rebuild it, so the compiled output matches the
            // tree rather than the attempt that was abandoned.
            restore(source, originalText);
            rebuild(primary, source);
        }
    }

    private void restore(Path source, String text) throws IOException {
        Files.writeString(source, text, StandardCharsets.UTF_8);
    }

    /** Recompile a file after reverting it, so the class files match the source tree again. */
    private void rebuild(Path primary, Path source) {
        try {
            compiler.compile(List.of(source), classesDir, classpath, release);
        } catch (IOException e) {
            // The tree already compiled once, so a failure here means the revert did not take.
            // Leaving it unreported would hide a corrupted build, but there is nothing useful to
            // do about it at this point either; the final gate will report the class.
        }
    }

    private ClassVerdict regate(String className) {
        try {
            Path original = originalClasses.resolve(className + ".class");
            Path rebuilt = classesDir.resolve(className + ".class");
            if (!Files.isReadable(original) || !Files.isReadable(rebuilt)) {
                return ClassVerdict.harnessError(className, "class file missing during re-gate");
            }
            return gate.compare(com.anydecoder.core.gate.ClassModelReader.read(original),
                    com.anydecoder.core.gate.ClassModelReader.read(rebuilt));
        } catch (Exception e) {
            return ClassVerdict.harnessError(className, "could not read a class file: " + e.getMessage());
        }
    }

    /**
     * The source file a class is compiled from. Inner classes share their outer class's file, so
     * {@code Foo$Bar} resolves to {@code Foo.java}.
     */
    static Path sourceFileFor(Path primary, String internalName) {
        int dollar = internalName.indexOf('$');
        String outer = dollar < 0 ? internalName : internalName.substring(0, dollar);
        Path file = primary.resolve(outer + ".java");
        return Files.isReadable(file) ? file : null;
    }
}
