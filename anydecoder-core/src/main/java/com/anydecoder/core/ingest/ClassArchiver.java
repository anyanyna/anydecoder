package com.anydecoder.core.ingest;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs a directory of class files into a jar.
 *
 * <p>Decompilers accept a jar far more reliably than a directory. CFR 0.152 in particular does
 * not recognise a plain class directory at all: it falls back to treating the argument as a
 * class name, then fails to open the directory as a file. Every engine handles the jar case, so
 * the pipeline normalises to a jar before invoking any of them.
 *
 * <p>Entries are written in a fixed order and with no timestamps, so the same input always
 * produces byte-identical archives -- which matters when a jar is part of an incremental
 * workflow and its hash would otherwise change on every run.
 */
public final class ClassArchiver {

    private ClassArchiver() {
    }

    public static Path pack(Path classesDirectory, Path jarFile) throws IOException {
        Files.createDirectories(jarFile.toAbsolutePath().getParent());

        try (OutputStream fileStream = Files.newOutputStream(jarFile);
             ZipOutputStream zip = new ZipOutputStream(fileStream)) {
            var entries = new java.util.ArrayList<Path>();
            try (var stream = Files.walk(classesDirectory)) {
                stream.filter(p -> p.toString().endsWith(".class"))
                        .sorted()
                        .forEach(entries::add);
            }
            for (Path entry : entries) {
                String name = classesDirectory.relativize(entry).toString().replace('\\', '/');
                ZipEntry zipEntry = new ZipEntry(name);
                zipEntry.setTime(0L);
                zip.putNextEntry(zipEntry);
                Files.copy(entry, zip);
                zip.closeEntry();
            }
        }
        return jarFile;
    }
}
