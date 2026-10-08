package com.anydecoder.core.engine;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Process plumbing shared by the engines, none of which needs anything else in common. */
abstract class AbstractJarDecompiler implements Decompiler {

    /** Long enough for a large war, short enough that a hung engine does not hang the run. */
    private static final long TIMEOUT_MINUTES = 30;

    private final ToolArtifact artifact;
    private final ToolCache cache;

    protected AbstractJarDecompiler(ToolArtifact artifact, ToolCache cache) {
        this.artifact = artifact;
        this.cache = cache;
    }

    @Override
    public String id() {
        return artifact.id();
    }

    @Override
    public String version() {
        return artifact.version();
    }

    @Override
    public ToolArtifact artifact() {
        return artifact;
    }

    /** Build the argument list, given a resolved jar. */
    protected abstract List<String> arguments(Path jar, Path input, Path outputDir, List<Path> classpath);

    /**
     * Heap for the engine's process.
     *
     * <p>Engines disagree about how much they need. Vineflower builds a whole-project view before
     * emitting anything and will abandon individual methods with an {@code OutOfMemoryError}
     * rather than failing outright, which is easy to miss: the run continues and those methods
     * are simply absent from its output. Where that matters, the comparison against a second
     * engine is what surfaces it.
     *
     * <p>Override with {@code -Danydecoder.engine.heap} for a project larger than any this was
     * calibrated on.
     */
    protected String heap() {
        return System.getProperty("anydecoder.engine.heap", defaultHeap());
    }

    protected String defaultHeap() {
        return "1g";
    }

    @Override
    public void decompile(Path input, Path outputDir, List<Path> classpath, PrintStream log)
            throws DecompilerException {
        Path jar = cache.resolve(artifact);
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new DecompilerException("could not create " + outputDir, e);
        }

        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-Duser.language=en");
        command.add("-Duser.country=US");
        command.add("-Xmx" + heap());
        command.addAll(invocationPrefix(jar));
        command.addAll(arguments(jar, input, outputDir, classpath));

        log.println("[" + id() + "] " + String.join(" ", command));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        boolean verbose = Boolean.getBoolean("anydecoder.engine.verbose");
        try {
            Process process = builder.start();
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Engines are extremely chatty about classes they are loading, which buries
                    // the run's own progress and the failures that matter.
                    if (verbose || isWorthShowing(line)) {
                        log.println(line);
                    }
                }
            }
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new DecompilerException(id() + " did not finish within "
                        + TIMEOUT_MINUTES + " minutes");
            }
            int exit = process.exitValue();
            // Engines are inconsistent about exit codes -- CFR exits non-zero for a class it
            // merely found uninteresting. What matters is whether source came out, so a non-zero
            // exit is only fatal when the output directory is still empty.
            if (exit != 0 && !producedAnySource(outputDir)) {
                throw new DecompilerException(id() + " exited with " + exit
                        + " and produced no source in " + outputDir);
            }
        } catch (IOException e) {
            throw new DecompilerException("could not run " + id(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DecompilerException("interrupted while running " + id(), e);
        }
    }

    /**
     * Whether an engine output line is worth putting in the run log.
     *
     * <p>Engines announce every class they load. On a few thousand classes that is megabytes of
     * output that buries the run's own progress and, worse, the warnings that indicate a method
     * failed. Anything that is not routine progress is kept.
     */
    private static boolean isWorthShowing(String line) {
        String trimmed = line.stripLeading();
        if (trimmed.startsWith("INFO:")) {
            return false;
        }
        if (trimmed.startsWith("Loading Class:") || trimmed.startsWith("... done")) {
            return false;
        }
        return !trimmed.isEmpty();
    }

    /** How to get the engine started: {@code -jar foo.jar} by default. */
    protected List<String> invocationPrefix(Path jar) {
        return List.of("-jar", jar.toString());
    }

    private static boolean producedAnySource(Path outputDir) throws IOException {
        try (var stream = Files.walk(outputDir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".java"));
        }
    }

    private static String javaExecutable() {
        String home = System.getProperty("java.home");
        Path candidate = Path.of(home, "bin", isWindows() ? "java.exe" : "java");
        return Files.isExecutable(candidate) ? candidate.toString() : "java";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** Joins paths with the platform's separator, for engines that take one classpath string. */
    protected static String joinClasspath(List<Path> entries) {
        if (entries.isEmpty()) {
            return "";
        }
        return String.join(java.io.File.pathSeparator,
                entries.stream().map(Path::toString).toList());
    }
}
