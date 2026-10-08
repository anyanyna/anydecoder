package com.anydecoder.core.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Works out which class files belong to the project and which belong to its dependencies.
 *
 * <p>The distinction is the difference between a two-hour job and a two-week one, and it cannot
 * be made from the class files alone -- {@code WEB-INF/classes} versus {@code WEB-INF/lib} is the
 * usual signal, and a bare directory of classes is taken at face value as the project's own.
 */
public final class ArtifactScanner {

    private static final String WEB_INF_CLASSES = "WEB-INF/classes";
    private static final String WEB_INF_LIB = "WEB-INF/lib";

    private ArtifactScanner() {
    }

    /**
     * @param input   a class directory, a war, or a jar
     * @param workDir scratch space; an archive is unpacked here
     */
    public static ArtifactSet scan(Path input, Path workDir) throws IOException {
        if (!Files.exists(input)) {
            throw new IOException("input does not exist: " + input);
        }
        if (Files.isRegularFile(input)) {
            if (!isArchive(input)) {
                throw new IOException("expected a directory, .jar or .war but got " + input);
            }
            Path unpacked = unpack(input, workDir.resolve("unpacked"));
            return scanDirectory(unpacked, ArtifactSet.Layout.ARCHIVE);
        }
        return scanDirectory(input, null);
    }

    private static ArtifactSet scanDirectory(Path root, ArtifactSet.Layout forcedLayout) throws IOException {
        Path webInfClasses = root.resolve(WEB_INF_CLASSES);
        if (Files.isDirectory(webInfClasses)) {
            Path lib = root.resolve(WEB_INF_LIB);
            return new ArtifactSet(webInfClasses, jarsIn(lib), ArtifactSet.Layout.WEB_INF);
        }

        Path nested = root.resolve("classes");
        if (Files.isDirectory(nested) && containsClassFiles(nested)) {
            return new ArtifactSet(nested, jarsIn(root.resolve("lib")),
                    ArtifactSet.Layout.LOOSE_CLASSES);
        }

        if (containsClassFiles(root)) {
            return new ArtifactSet(root, jarsIn(root.resolve("lib")),
                    forcedLayout == null ? ArtifactSet.Layout.LOOSE_CLASSES : forcedLayout);
        }

        throw new IOException("no class files found under " + root
                + " (looked for classes directly, in classes/, and in " + WEB_INF_CLASSES + ")");
    }

    private static List<Path> jarsIn(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var stream = Files.list(directory)) {
            return stream
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static boolean containsClassFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (var stream = Files.walk(directory, 6)) {
            return stream.anyMatch(p -> p.toString().endsWith(".class"));
        }
    }

    private static boolean isArchive(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".jar") || name.endsWith(".war") || name.endsWith(".zip");
    }

    private static Path unpack(Path archive, Path destination) throws IOException {
        Files.createDirectories(destination);
        List<Path> extracted = new ArrayList<>();
        try (InputStream in = Files.newInputStream(archive);
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = destination.resolve(entry.getName()).normalize();
                // Refuse entries that would escape the working directory.
                if (!target.startsWith(destination)) {
                    throw new IOException("archive entry escapes the working directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                extracted.add(target);
            }
        }
        return destination;
    }
}
