package com.anydecoder.core.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Substitutes another engine's rendering of a file that would not compile.
 *
 * <p>Searches the other engines' trees in order and takes the first one that has a file at the
 * same relative path. Which engine supplied it is recorded, because a tree assembled from two
 * engines is worth knowing about when reading the verdicts: the gate says whether the result is
 * faithful, not which tool wrote it.
 */
public final class OtherEngineFallback implements SourceFallback {

    private final List<Path> alternativeRoots;
    private final List<String> substitutions = new ArrayList<>();

    public OtherEngineFallback(List<Path> alternativeRoots) {
        this.alternativeRoots = List.copyOf(alternativeRoots);
    }

    @Override
    public boolean replace(Path sourceFile, Path sourceRoot) throws IOException {
        Path relative;
        try {
            relative = sourceRoot.relativize(sourceFile);
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (Path root : alternativeRoots) {
            Path candidate = root.resolve(relative);
            if (!Files.isReadable(candidate)) {
                continue;
            }
            String replacement = Files.readString(candidate, StandardCharsets.UTF_8);
            Files.writeString(sourceFile, replacement, StandardCharsets.UTF_8);
            substitutions.add(root.getFileName() + "/" + relative.toString().replace('\\', '/'));
            return true;
        }
        return false;
    }

    /** Files that were taken from another engine, as {@code src-vineflower/com/example/Foo.java}. */
    public List<String> substitutions() {
        return List.copyOf(substitutions);
    }

    @Override
    public String describe() {
        return alternativeRoots.stream()
                .map(r -> r.getFileName().toString())
                .reduce((a, b) -> a + ", " + b)
                .map(names -> "substituted from " + names)
                .orElse("none");
    }
}
