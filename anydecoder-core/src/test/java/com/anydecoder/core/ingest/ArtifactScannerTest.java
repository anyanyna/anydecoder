package com.anydecoder.core.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unpacking an archive, and the check that stops it writing outside its scratch directory.
 *
 * <p>The two tests are a pair, and each is worthless without the other. One says an ordinary entry
 * is accepted; the other says an entry that climbs out is still refused. A "fix" that deleted the
 * containment check would pass the first and fail the second, which is the whole reason the second
 * is here.
 */
class ArtifactScannerTest {

    /**
     * Reproduces the state a new user reaches on their first command.
     *
     * <p>The destination a caller passes is relative — {@code -o ./out}, {@code -o .} — and
     * {@code unpack} resolved each entry against it and then normalised <em>the entry only</em>.
     * Normalising strips the leading {@code ./} from the entry but not from the destination, so
     * {@code out/unpacked/META-INF/MANIFEST.MF} was tested for a prefix of {@code ./out/unpacked}
     * and failed. Every jar, however ordinary, came back as:
     *
     * <pre>anydecoder: archive entry escapes the working directory: META-INF/MANIFEST.MF</pre>
     *
     * <p>Normalising the destination too does not repair {@code -o .}, which normalises to a
     * single-name path that no entry starts with. Both sides are resolved against an absolute,
     * normalised root instead, and that is what this pins: no amount of dot-slash in the
     * destination may be read as an escape.
     */
    @Test
    @DisplayName("a relative destination is not mistaken for an escaping entry")
    void relativeDestinationIsNotMistakenForAnEscape() throws IOException {
        Path work = Paths.get("./target/artifact-scanner-relative");
        try {
            Path archive = jar(work.resolve("input.jar"));
            assertDoesNotThrow(() -> ArtifactScanner.scan(archive, work));
        } finally {
            deleteRecursively(work);
        }
    }

    @Test
    @DisplayName("an entry that climbs out of the work directory is still refused")
    void entryThatEscapesTheWorkDirectoryIsRefused() throws IOException {
        Path work = Files.createTempDirectory("anydecoder-scanner");
        try {
            Path archive = jar(work.resolve("input.jar"), "../escaped.txt");
            Path scratch = work.resolve("scratch");

            IOException refused = assertThrows(IOException.class,
                    () -> ArtifactScanner.scan(archive, scratch));

            assertTrue(refused.getMessage().contains("escapes the working directory"),
                    "something other than the containment check refused it: " + refused.getMessage());
            assertTrue(Files.notExists(scratch.resolve("escaped.txt")),
                    "the escaping entry was written before the check refused it");
        } finally {
            deleteRecursively(work);
        }
    }

    /** Enough of a jar for the scanner to treat what it unpacks as a project rather than refuse it. */
    private static Path jar(Path where, String... extraEntries) throws IOException {
        Files.createDirectories(where.getParent());
        try (OutputStream out = Files.newOutputStream(where);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            write(zip, "META-INF/MANIFEST.MF");
            write(zip, "demo/Sample.class");
            for (String name : extraEntries) {
                write(zip, name);
            }
        }
        return where;
    }

    private static void write(ZipOutputStream zip, String name) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(new byte[]{0});
        zip.closeEntry();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
