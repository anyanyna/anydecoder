package com.anydecoder.core.engine;

/**
 * Where a third-party engine comes from.
 *
 * @param id            short name, {@code cfr}
 * @param version       pinned version, {@code 0.152}
 * @param fileName      the jar's name as shipped
 * @param mainClass     entry point when the jar's manifest is not usable
 * @param mavenPath     path under a Maven repository, for the download fallback
 * @param resourcePath  location of the jar inside anydecoder's own artifact, if bundled
 */
public record ToolArtifact(
        String id,
        String version,
        String fileName,
        String mainClass,
        String mavenPath,
        String resourcePath) {

    /** {@code <toolsDir>/<file>}. The caller owns where that directory is. */
    public java.nio.file.Path pathIn(java.nio.file.Path toolsDirectory) {
        return toolsDirectory.resolve(fileName);
    }
}
