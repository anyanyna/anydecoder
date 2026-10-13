package com.anydecoder.core.pipeline;

import com.anydecoder.core.build.CompileOutcome;
import com.anydecoder.core.build.OtherEngineFallback;
import com.anydecoder.core.build.ProjectCompiler;
import com.anydecoder.core.build.SourceFallback;
import com.anydecoder.core.build.SourceTreeCompiler;
import com.anydecoder.core.engine.CfrDecompiler;
import com.anydecoder.core.engine.Decompiler;
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

    public DecoderPipeline(ToolCache tools) {
        this.tools = tools;
    }

    public PipelineOutcome run(PipelineOptions options, PrintStream log) throws Exception {
        Files.createDirectories(options.workDir());
        Files.createDirectories(options.outputDir());

        ArtifactSet scanned = ArtifactScanner.scan(options.input(), options.workDir());
        ArtifactSet artifacts = withExtraDependencies(scanned, options.dependencyJars());
        long classCount = countClassFiles(artifacts.projectClasses());
        log.printf("[ingest] %d project class file(s) in %s, %d dependency jar(s)%n",
                classCount, artifacts.projectClasses(), artifacts.dependencyCount());

        Map<String, Path> trees = decompileAll(options, artifacts, log);
        Path primary = trees.get(options.engines().get(0));

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
                normalizationCounts, waivers);
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

    private Map<String, Path> decompileAll(PipelineOptions options, ArtifactSet artifacts,
                                           PrintStream log) throws Exception {
        // Decompilers are more reliable given a jar than a directory, and CFR accepts nothing
        // else, so the class set is packed once and every engine is pointed at the same file.
        Path jar = ClassArchiver.pack(artifacts.projectClasses(),
                options.workDir().resolve("project-classes.jar"));
        log.printf("[ingest] packed classes into %s%n", jar);

        Map<String, Path> trees = new LinkedHashMap<>();
        for (String id : options.engines()) {
            Decompiler engine = engineFor(id);
            Path output = options.workDir().resolve("src-" + id);
            log.printf("[decompile] %s %s%n", id, engine.version());
            engine.decompile(jar, output, artifacts.dependencyJars(), log);
            trees.put(id, output);
        }
        return trees;
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
        } catch (Exception e) {
            return ClassVerdict.harnessError(className,
                    "could not read a class file: " + e.getMessage());
        }
    }

    private Decompiler engineFor(String id) {
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
