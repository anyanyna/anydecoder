package com.anydecoder.core.ingest;

import java.nio.file.Path;
import java.util.List;

/**
 * What was found, sorted into the two categories that matter.
 *
 * <p>Project classes get decompiled. Dependency jars do not -- they are third-party code whose
 * source is usually published anyway, and decompiling them would multiply the work while
 * answering a question nobody asked. Their only job here is to sit on the compile classpath so
 * the recompiled project resolves the same types the original did.
 *
 * @param projectClasses directory holding the project's own class files, laid out by package
 * @param dependencyJars jars referenced by the project, in discovery order
 * @param layout         the shape the input turned out to have
 */
public record ArtifactSet(
        Path projectClasses,
        List<Path> dependencyJars,
        Layout layout) {

    public enum Layout {
        /** Class files at the root of the given directory, packages as subdirectories. */
        LOOSE_CLASSES,
        /** {@code WEB-INF/classes} beside {@code WEB-INF/lib}, as a war holds them. */
        WEB_INF,
        /** A jar or war that was unpacked into the working directory first. */
        ARCHIVE
    }

    public int dependencyCount() {
        return dependencyJars.size();
    }
}
