package com.anydecoder.core.normalize;

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
 * Runs the cleanup passes over a decompiled tree.
 *
 * <p>The passes are applied optimistically. None of them has to be right, because the run
 * recompiles and re-gates afterwards: a pass that changed behaviour shows up as a class whose
 * verdict got worse, and those files can be reverted. That is the reason it is safe to attempt
 * an edit like removing a label whose target is not obviously local -- the alternative, refusing
 * to touch anything unless it can be proved safe by inspection alone, is how a tool ends up
 * shipping a tree nobody wants to read.
 */
public final class SourceNormalizerChain {

    private final List<SourceNormalizer> normalizers;

    public SourceNormalizerChain(List<SourceNormalizer> normalizers) {
        this.normalizers = List.copyOf(normalizers);
    }

    /** The passes anydecoder applies by default, in the order they must run. */
    public static SourceNormalizerChain standard() {
        return new SourceNormalizerChain(List.of(
                new CfrHeaderNormalizer(),
                // Imports before labels: removing an unused import can make a label unused.
                new UnusedImportNormalizer(),
                new BlockLabelNormalizer(),
                // Last, so that any position recorded in metadata refers to the final text.
                new CjkEscapeNormalizer()));
    }

    public record TreeResult(Map<String, Integer> filesPerPass, Map<String, String> metadata) {
        public static TreeResult empty() {
            return new TreeResult(Map.of(), Map.of());
        }
    }

    public TreeResult applyTo(Path sourceRoot, PrintStream log) throws IOException {
        List<Path> files;
        try (var stream = Files.walk(sourceRoot)) {
            files = stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }

        // Counted per pass so the report can show what each rule actually did, which is how a
        // rule that has stopped being useful gets noticed.
        Map<String, Integer> filesPerPass = new LinkedHashMap<>();
        Map<String, String> metadata = new LinkedHashMap<>();

        for (SourceNormalizer normalizer : normalizers) {
            int touched = 0;
            for (Path file : files) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                SourceNormalizer.Result result = normalizer.normalize(source);
                if (result.changed()) {
                    Files.writeString(file, result.source(), StandardCharsets.UTF_8);
                    touched++;
                }
                if (!result.metadata().isEmpty()) {
                    metadata.putAll(prefix(file, sourceRoot, result.metadata()));
                }
            }
            filesPerPass.put(normalizer.id(), touched);
            log.printf("[normalize] %-20s %d file(s)%n", normalizer.id(), touched);
        }
        return new TreeResult(filesPerPass, metadata);
    }

    private static Map<String, String> prefix(Path file, Path root, Map<String, String> values) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        Map<String, String> prefixed = new LinkedHashMap<>();
        values.forEach((k, v) -> prefixed.put(relative + ":" + k, v));
        return prefixed;
    }

    public List<SourceNormalizer> normalizers() {
        return new ArrayList<>(normalizers);
    }
}
