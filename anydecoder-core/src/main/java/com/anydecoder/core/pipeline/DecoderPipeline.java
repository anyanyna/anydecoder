package com.anydecoder.core.pipeline;

import com.anydecoder.core.build.CompileOutcome;
import com.anydecoder.core.build.OtherEngineFallback;
import com.anydecoder.core.build.ProjectCompiler;
import com.anydecoder.core.build.SourceFallback;
import com.anydecoder.core.build.SourceTreeCompiler;
import com.anydecoder.core.engine.CfrDecompiler;
import com.anydecoder.core.engine.Decompiler;
import com.anydecoder.core.engine.DecompilerException;
import com.anydecoder.core.engine.ProcyonDecompiler;
import com.anydecoder.core.engine.ToolCache;
import com.anydecoder.core.engine.VineflowerDecompiler;
import com.anydecoder.core.gate.ClassGate;
import com.anydecoder.core.gate.ClassModel;
import com.anydecoder.core.gate.ClassModelReader;
import com.anydecoder.core.gate.ClassVerdict;
import com.anydecoder.core.gate.GateReport;
import com.anydecoder.core.gate.HierarchyIndex;
import com.anydecoder.core.gate.WaiverRegistry;
import com.anydecoder.core.ingest.ArtifactScanner;
import com.anydecoder.core.ingest.ArtifactSet;
import com.anydecoder.core.ingest.ClassArchiver;
import com.anydecoder.core.normalize.SourceNormalizerChain;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The whole loop: ingest, decompile, clean up, recompile, and prove.
 *
 * <p>The order is not arbitrary. Decompiling comes first because everything downstream needs
 * source. Recompiling comes before the gate because the gate's entire claim is about a
 * <em>rebuild</em>: comparing decompiled source against original bytecode directly would mean
 * comparing a text to a binary and settling it by judgement, which is exactly the guessing this
 * tool exists to replace.
 */
public final class DecoderPipeline {

    private final ToolCache tools;
    private final java.util.function.Function<String, Decompiler> engineFactory;

    public DecoderPipeline(ToolCache tools) {
        this(tools, null);
    }

    /**
     * @param engineFactory how to obtain an engine by id, or null for the built-in three. A seam
     *                      for testing the behaviour around a failing engine, which cannot be
     *                      provoked with the real ones on demand.
     */
    DecoderPipeline(ToolCache tools, java.util.function.Function<String, Decompiler> engineFactory) {
        this.tools = tools;
        this.engineFactory = engineFactory;
    }

    public PipelineOutcome run(PipelineOptions options, PrintStream log) throws Exception {
        Files.createDirectories(options.workDir());
        Files.createDirectories(options.outputDir());

        ArtifactSet scanned = ArtifactScanner.scan(options.input(), options.workDir());
        ArtifactSet artifacts = withExtraDependencies(scanned, options.dependencyJars());
        long classCount = countClassFiles(artifacts.projectClasses());
        log.printf("[ingest] %d project class file(s) in %s, %d dependency jar(s)%n",
                classCount, artifacts.projectClasses(), artifacts.dependencyCount());

        Decompilation decompilation = decompileAll(options, artifacts, log);
        Map<String, Path> trees = decompilation.trees();
        Path primary = choosePrimary(options, trees, decompilation.failures(), log);

        Map<String, Integer> normalizationCounts = Map.of();
        if (options.enableNormalization()) {
            normalizationCounts = SourceNormalizerChain.standard()
                    .applyTo(primary, log).filesPerPass();
        }

        OtherEngineFallback fallback = fallbackFrom(trees, primary);
        SourceTreeCompiler.Result rebuild = rebuild(options, artifacts, primary, fallback, log);
        CompileOutcome compile = rebuild.compile();
        log.printf("[build] %d compiled, %d substituted from another engine, %d set aside,"
                        + " %d round(s)%n",
                countSources(primary) - rebuild.excluded().size(), rebuild.substituted(),
                rebuild.excluded().size(), rebuild.rounds());
        if (!fallback.substitutions().isEmpty()) {
            log.printf("[build] substituted %d file(s) %s%n",
                    fallback.substitutions().size(), fallback.describe());
        }

        // Loaded once and shared, so a second gate pass after a substitution does not lose track of
// which entries matched.
        WaiverRegistry waivers = loadWaivers(options, artifacts, log);
        GateReport report = runGate(artifacts, compile.outputDirectory(), waivers, log);
        List<EngineSubstitution.Attempt> substitutions = List.of();

        if (options.tryAlternativeEngines() && trees.size() > 1) {
            substitutions = substituteFromOtherEngines(options, artifacts, primary, trees,
                    report.needingReview(), log);
            if (substitutions.stream().anyMatch(EngineSubstitution.Attempt::kept)) {
                // Verdicts changed, so re-gate the whole tree. Reporting a mixture of verdicts
                // from before and after a repair would be worse than the few minutes it costs.
                report = runGate(artifacts, compile.outputDirectory(), waivers, log);
            }
        }

        return new PipelineOutcome(artifacts, trees, primary, compile, rebuild.excluded(),
                fallback.substitutions(), substitutions, report, compile.outputDirectory(),
                normalizationCounts, waivers, decompilation.failures());
    }

    /** The waiver file the caller named, or one sitting beside the classes, or none. */
    private static WaiverRegistry loadWaivers(PipelineOptions options, ArtifactSet artifacts,
                                              PrintStream log) throws IOException {
        Path file = options.waivers();
        if (file == null) {
            Path conventional = artifacts.projectClasses().resolve("anydecoder-waivers.md");
            if (!Files.isReadable(conventional)) {
                return WaiverRegistry.empty();
            }
            file = conventional;
        }
        WaiverRegistry registry = WaiverRegistry.load(file);
        log.printf("[gate] %d waiver(s) from %s, %d refused%n",
                registry.size(), file, registry.rejected().size());
        return registry;
    }

    /**
     * Hand each class the gate was unhappy with to the other engines, keeping a swap only when the
     * verdict actually improves.
     */
    private List<EngineSubstitution.Attempt> substituteFromOtherEngines(
            PipelineOptions options, ArtifactSet artifacts, Path primary, Map<String, Path> trees,
            List<ClassVerdict> needingReview, PrintStream log) throws IOException {

        Path classesDir = options.workDir().resolve("classes");
        List<Path> classpath = new ArrayList<>(artifacts.dependencyJars());
        classpath.add(artifacts.projectClasses());
        List<Path> alternatives = new ArrayList<>();
        for (Map.Entry<String, Path> entry : trees.entrySet()) {
            if (!entry.getValue().equals(primary)) {
                alternatives.add(entry.getValue());
            }
        }
        ClassGate gate = gateFor(artifacts);
        EngineSubstitution substitution = new EngineSubstitution(gate, new ProjectCompiler(),
                artifacts.projectClasses(), classesDir, classpath,
                dominantRelease(artifacts.projectClasses()));

        log.printf("[substitute] trying %d engine(s) on %d class(es) the gate was unhappy with%n",
                alternatives.size(), needingReview.size());
        List<EngineSubstitution.Attempt> attempts =
                substitution.tryImprove(primary, alternatives, needingReview, log);
        long kept = attempts.stream().filter(EngineSubstitution.Attempt::kept).count();
        log.printf("[substitute] %d of %d improved%n", kept, attempts.size());
        return attempts;
    }

    /** A gate with its hierarchy index built from the same classpath the rebuild used. */
    private ClassGate gateFor(ArtifactSet artifacts) {
        List<Path> hierarchyClasspath = new ArrayList<>(artifacts.dependencyJars());
        hierarchyClasspath.add(artifacts.projectClasses());
        return new ClassGate(new HierarchyIndex(hierarchyClasspath));
    }

    /**
     * The other engines' trees, to be consulted for any file the primary engine's source cannot
     * compile. Ordered so the second engine is tried before the third.
     */
    private static OtherEngineFallback fallbackFrom(Map<String, Path> trees, Path primary) {
        List<Path> alternatives = new ArrayList<>();
        for (Path tree : trees.values()) {
            if (!tree.equals(primary)) {
                alternatives.add(tree);
            }
        }
        return new OtherEngineFallback(alternatives);
    }

    /** Add jars the caller named to whatever discovery already found, without duplicates. */
    private static ArtifactSet withExtraDependencies(ArtifactSet scanned, List<Path> extra)
            throws IOException {
        if (extra.isEmpty()) {
            return scanned;
        }
        List<Path> jars = new ArrayList<>(scanned.dependencyJars());
        for (Path jar : ArtifactScanner.expandDependencies(extra)) {
            if (!jars.contains(jar)) {
                jars.add(jar);
            }
        }
        jars.sort(Comparator.comparing(Path::toString));
        return new ArtifactSet(scanned.projectClasses(), jars, scanned.layout());
    }

    private Decompilation decompileAll(PipelineOptions options, ArtifactSet artifacts,
                                           PrintStream log) throws Exception {
        // Decompilers are more reliable given a jar than a directory, and CFR accepts nothing
        // else, so the class set is packed once and every engine is pointed at the same file.
        Path jar = ClassArchiver.pack(artifacts.projectClasses(),
                options.workDir().resolve("project-classes.jar"));
        log.printf("[ingest] packed classes into %s%n", jar);

        Map<String, Path> trees = new LinkedHashMap<>();
        Map<String, String> failedEngines = new LinkedHashMap<>();
        for (String id : options.engines()) {
            Decompiler engine = engineFor(id);
            Path output = options.workDir().resolve("src-" + id);
            log.printf("[decompile] %s %s%n", id, engine.version());
            try {
                engine.decompile(jar, output, artifacts.dependencyJars(), log);
            } catch (DecompilerException e) {
                // One engine failing is not the run failing. They fail on different inputs and
                // for different reasons -- a heap that is too small for a large project is the
                // common one -- and the whole point of running several is that the others carry
                // on. Aborting here would throw away a decompilation that had already succeeded.
                failedEngines.put(id, e.getMessage());
                log.printf("[decompile] %s failed: %s%n", id, e.getMessage());
                continue;
            }
            if (javaFiles(output).isEmpty()) {
                failedEngines.put(id, "produced no source");
                log.printf("[decompile] %s produced no source%n", id);
                continue;
            }
            trees.put(id, output);
        }

        if (trees.isEmpty()) {
            throw new DecompilerException(
                    "no engine produced any source: " + failedEngines);
        }
        return new Decompilation(trees, failedEngines);
    }

    /**
     * The engine whose output the rebuild is built from.
     *
     * <p>The first one the caller named, unless it failed — in which case the next that produced
     * source, and the run says so. Falling back rather than aborting is the point of asking for
     * several engines; a substitute is not the same as the first choice, so it is announced.
     */
    private static Path choosePrimary(PipelineOptions options, Map<String, Path> trees,
                                      Map<String, String> failures, PrintStream log) {
        for (String id : options.engines()) {
            Path tree = trees.get(id);
            if (tree != null) {
                if (!id.equals(options.engines().get(0))) {
                    log.printf("[decompile] the first engine failed, building from %s instead;"
                            + " results will differ from a run where it succeeded%n", id);
                }
                return tree;
            }
        }
        throw new IllegalStateException("no engine produced source: " + failures);
    }

    /**
     * The trees that were produced, and the engines that failed.
     *
     * <p>The failures are carried rather than logged and dropped: a run that silently used one
     * engine where two were asked for is a different result from one that used two, and the
     * reader needs to know which they are looking at.
     */
    public record Decompilation(Map<String, Path> trees, Map<String, String> failures) {
    }

    private SourceTreeCompiler.Result rebuild(PipelineOptions options, ArtifactSet artifacts,
                                              Path primarySource, SourceFallback fallback,
                                              PrintStream log) throws IOException {
        List<Path> sources = javaFiles(primarySource);
        List<Path> classpath = new ArrayList<>(artifacts.dependencyJars());
        // The original classes go on the classpath so that any type the decompiler could not
        // reproduce still resolves. Without this, a single missing class cascades into hundreds
        // of "cannot find symbol" errors that say nothing about the source under test -- and
        // with the peel in SourceTreeCompiler, it is also what stops one broken file from
        // taking every file that references it down with it.
        classpath.add(artifacts.projectClasses());

        String release = dominantRelease(artifacts.projectClasses());
        log.printf("[build] compiling %d source file(s) at release %s%n", sources.size(), release);

        return new SourceTreeCompiler().compile(sources, primarySource,
                options.workDir().resolve("classes"), classpath, release, fallback, log::println);
    }

    private GateReport runGate(ArtifactSet artifacts, Path rebuiltClasses, WaiverRegistry waivers,
                              PrintStream log) throws IOException {
        Path originalClasses = artifacts.projectClasses();

        // The hierarchy index needs the same classpath the rebuild used: the dependencies, plus
        // the originals, so that a receiver narrowed from an interface to its implementation is
        // recognised as the same member rather than a different call.
        ClassGate gate = gateFor(artifacts);

        List<Path> originals = classFiles(originalClasses);
        List<ClassVerdict> verdicts = originals.parallelStream()
                .map(original -> gateOne(gate, originalClasses, rebuiltClasses, original))
                .map(waivers::apply)
                .sorted(Comparator.comparing(ClassVerdict::className))
                .toList();
        GateReport report = new GateReport(verdicts);
        log.printf("[gate] %d/%d reached, %d safe, %d need review%n",
                report.gated(), report.total(), report.safeCount(), report.needingReview().size());
        return report;
    }

    private ClassVerdict gateOne(ClassGate gate, Path originalClasses, Path rebuiltClasses,
                                 Path original) {
        String relative = originalClasses.relativize(original).toString();
        String className = relative.substring(0, relative.length() - ".class".length())
                .replace('\\', '/');
        Path rebuilt = rebuiltClasses.resolve(relative);
        if (!Files.isReadable(rebuilt)) {
            return ClassVerdict.harnessError(className, "the rebuild produced no class for this file");
        }
        try {
            ClassModel from = ClassModelReader.read(original);
            ClassModel to = ClassModelReader.read(rebuilt);
            return gate.compare(from, to);
        } catch (Throwable t) {
            // Throwable, not Exception. The realistic failure here is not a malformed class file
            // but the run having exhausted its heap, which arrives as an Error -- and it arrives
            // one class at a time, out of a parallel stream, so the first one to fail takes the
            // whole run down with a message about a class that is not the problem. Measured on a
            // real project: 2394 classes built, then gating died with
            // `NoClassDefFoundError: org/objectweb/asm/Handle` for a class that was in the jar.
            return ClassVerdict.harnessError(className, describeHarnessFailure(t));
        }
    }

    /**
     * What to say about a class the harness could not get through.
     *
     * <p>Memory exhaustion is named explicitly and with the remedy, because the error it produces
     * does not look like one. A failed class load is reported later as a missing class, and a
     * reader chasing that has no way to find the heap.
     */
    private static String describeHarnessFailure(Throwable t) {
        if (hasMemoryPressure(t)) {
            return "the run ran out of heap while reading this class; raise -Xmx on the launcher"
                    + " and try again -- a project of a few thousand classes needs more than 4GB";
        }
        return "could not read a class file: " + t;
    }

    private static boolean hasMemoryPressure(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof OutOfMemoryError) {
                return true;
            }
        }
        return false;
    }

    private Decompiler engineFor(String id) {
        if (engineFactory != null) {
            return engineFactory.apply(id);
        }
        return switch (id) {
            case "cfr" -> new CfrDecompiler(tools);
            case "vineflower" -> new VineflowerDecompiler(tools);
            case "procyon" -> new ProcyonDecompiler(tools);
            default -> throw new IllegalArgumentException(
                    "unknown engine '" + id + "'; available: cfr, vineflower, procyon");
        };
    }

    /**
     * The Java release the originals were built for.
     *
     * <p>Recompiling at a newer release would change code generation in ways that have nothing to
     * do with the source -- string concatenation becoming {@code invokedynamic}, for one -- and
     * every such change would surface as a gate divergence.
     */
    private static String dominantRelease(Path classes) throws IOException {
        Map<String, Integer> counts = new TreeMap<>();
        List<Path> files = classFiles(classes);
        int sample = Math.min(files.size(), 500);
        for (int i = 0; i < sample; i++) {
            try {
                ClassModel model = ClassModelReader.read(files.get(i));
                counts.merge(model.javaVersion(), 1, Integer::sum);
            } catch (Exception ignored) {
                // A class that cannot be read tells us nothing about the release.
            }
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    public static List<Path> javaFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            // An engine that failed before it created anything leaves no directory at all, and
            // walking it would throw rather than report the engine as having produced nothing.
            return List.of();
        }
        try (var stream = Files.walk(root)) {
            return stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    public static List<Path> classFiles(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream.filter(p -> p.toString().endsWith(".class")).sorted().toList();
        }
    }

    private static long countClassFiles(Path root) throws IOException {
        return classFiles(root).size();
    }

    private static int countSources(Path root) throws IOException {
        return javaFiles(root).size();
    }
}
