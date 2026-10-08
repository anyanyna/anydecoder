package com.anydecoder.core.build;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compiles a decompiled source tree, reporting structured diagnostics.
 *
 * <p>This uses the compiler API in-process rather than shelling out to {@code javac}. The
 * original toolchain parsed javac's stderr, and its error counter never worked: the build forced
 * {@code -Duser.language=en} for stability, while the counter matched the Chinese form of the
 * word "error", so it reported {@code (0 errors)} on every failing build. Structured
 * diagnostics remove the entire class of problem, along with the encoding handling.
 *
 * <h2>Why the release is pinned to the original's</h2>
 *
 * <p>A recompile must be comparable to the class it replaces. Compiling Java 8 bytecode with a
 * Java 9+ target is not: from Java 9, string concatenation compiles to an {@code invokedynamic}
 * call to {@code makeConcatWithConstants} instead of a chain of {@code StringBuilder.append}
 * calls. The result is correct code that no longer resembles the original, so the gate would
 * report differences on every method that concatenates a string -- differences that say nothing
 * about the decompiled source. Compiling at the original's release keeps the comparison
 * meaningful.
 */
public final class ProjectCompiler {

    private final List<String> extraOptions = new ArrayList<>();

    /** Add a raw javac option, e.g. {@code -Xlint:-sunapi} to silence the sun.misc warnings. */
    public ProjectCompiler withOption(String option) {
        extraOptions.add(option);
        return this;
    }

    /**
     * @param sources      the .java files to compile
     * @param outputDir    where class files go
     * @param classpath    dependency jars, plus the original classes so that references to
     *                     types outside the decompiled set still resolve
     * @param release      the Java release to target, e.g. {@code "8"}; null for the JDK default
     */
    public CompileOutcome compile(List<Path> sources, Path outputDir, List<Path> classpath,
                                  String release) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "no java compiler available; anydecoder needs to run on a JDK, not a JRE");
        }
        Files.createDirectories(outputDir);

        DiagnosticCollector<JavaFileObject> collected = new DiagnosticCollector<>();
        StringWriter compilerOutput = new StringWriter();

        try (StandardJavaFileManager files =
                     compiler.getStandardFileManager(collected, Locale.ENGLISH, StandardCharsets.UTF_8)) {

            Iterable<? extends JavaFileObject> units = files.getJavaFileObjectsFromPaths(sources);

            List<String> options = new ArrayList<>();
            options.add("-nowarn");
            options.add("-g");
            options.add("-encoding");
            options.add("UTF-8");
            options.add("-d");
            options.add(outputDir.toString());
            if (!classpath.isEmpty()) {
                options.add("-cp");
                options.add(String.join(java.io.File.pathSeparator,
                        classpath.stream().map(Path::toString).toList()));
            }
            if (release != null && !release.isBlank()) {
                options.add("--release");
                options.add(release);
            }
            // Header-only warnings from the originals -- a "source value 8 is obsolete" style
            // message, or a use of sun.misc -- never indicate a decompilation defect. javac
            // has no -Xlint category for the sun.misc notice; -nowarn already covers it.
            options.add("-Xlint:-options");
            // javac stops after 100 errors by default. That cap is not a detail here: it makes
            // a tree with 4800 errors look like one with 100, and SourceTreeCompiler peels
            // against the list it is given, so it would remove a hundred files per round
            // instead of all of them at once. Measured on the validation corpus, raising it
            // took the peel from more than twenty rounds to two.
            options.add("-Xmaxerrs");
            options.add("100000");
            options.addAll(extraOptions);

            boolean ok;
            try {
                JavaCompiler.CompilationTask task = compiler.getTask(
                        new PrintWriter(compilerOutput), files, collected, options, null, units);
                ok = Boolean.TRUE.equals(task.call());
            } catch (RuntimeException e) {
                // The compiler rejects bad options by throwing rather than reporting. That is a
                // bug in how anydecoder invoked it, not a property of the source under test, so
                // it comes back as a failed outcome the report can show rather than escaping as
                // a stack trace in the middle of a run over thousands of classes.
                return new CompileOutcome(false,
                        List.of(new CompileOutcome.Diagnostic(null, -1, -1, "ERROR", null,
                                "javac rejected the invocation: " + e.getMessage())),
                        outputDir);
            }

            List<CompileOutcome.Diagnostic> diagnostics = new ArrayList<>();
            for (javax.tools.Diagnostic<? extends JavaFileObject> d : collected.getDiagnostics()) {
                diagnostics.add(new CompileOutcome.Diagnostic(
                        d.getSource() == null ? null : Path.of(d.getSource().toUri()),
                        d.getLineNumber(),
                        d.getColumnNumber(),
                        d.getKind().name(),
                        d.getCode(),
                        d.getMessage(Locale.ENGLISH)));
            }
            return new CompileOutcome(ok, List.copyOf(diagnostics), outputDir);
        }
    }
}
