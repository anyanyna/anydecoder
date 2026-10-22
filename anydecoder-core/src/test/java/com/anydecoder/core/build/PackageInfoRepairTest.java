package com.anydecoder.core.build;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * javac emits no class for an annotation-free {@code package-info.java}, so those classes stay
 * permanently out of the gate's reach. These tests pin both the repair and the conditions under
 * which it must refuse to run.
 */
class PackageInfoRepairTest {

    @Test
    @DisplayName("an empty package-info gets a source-retention annotation and compiles to a class")
    void repairsAnEmptyPackageInfo(@TempDir Path work) throws Exception {
        Path originals = work.resolve("classes");
        Path sources = work.resolve("src");
        writeEmptyPackageInfo(originals, "sample", false, false);
        Path source = writeSource(sources, "sample/package-info.java", "package sample;\n");

        int repaired = PackageInfoRepair.repair(originals, sources, "8", null);

        assertEquals(1, repaired);
        assertEquals("@javax.annotation.Generated(\"anydecoder\")"
                        + "\npackage sample;\n",
                Files.readString(source, StandardCharsets.UTF_8));

        SourceTreeCompiler.Result result = new SourceTreeCompiler().compile(
                List.of(source), work.resolve("rebuilt"), List.of(), "8");
        assertTrue(result.succeeded());
        assertTrue(Files.exists(work.resolve("rebuilt/sample/package-info.class")),
                "the annotation is what makes javac emit the class");
    }

    @Test
    @DisplayName("release 9 and later use the processing package")
    void usesTheAnnotationThatStillExists(@TempDir Path work) throws Exception {
        Path originals = work.resolve("classes");
        Path sources = work.resolve("src");
        writeEmptyPackageInfo(originals, "sample", false, false);
        Path source = writeSource(sources, "sample/package-info.java", "package sample;\n");

        assertEquals(1, PackageInfoRepair.repair(originals, sources, "17", null));

        assertTrue(Files.readString(source, StandardCharsets.UTF_8)
                .startsWith("@javax.annotation.processing.Generated(\"anydecoder\")"));
    }

    @Test
    @DisplayName("a package-info that already has an annotation is left alone")
    void leavesAnAnnotatedPackageInfoAlone(@TempDir Path work) throws Exception {
        Path originals = work.resolve("classes");
        Path sources = work.resolve("src");
        writeEmptyPackageInfo(originals, "sample", true, false);
        Path source = writeSource(sources, "sample/package-info.java", "package sample;\n");

        assertEquals(0, PackageInfoRepair.repair(originals, sources, "8", null));
        assertEquals("package sample;\n", Files.readString(source, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a package-info with a member is not the empty shape this repair assumes")
    void leavesANonEmptyPackageInfoAlone(@TempDir Path work) throws Exception {
        Path originals = work.resolve("classes");
        Path sources = work.resolve("src");
        writeEmptyPackageInfo(originals, "sample", false, true);
        Path source = writeSource(sources, "sample/package-info.java", "package sample;\n");

        assertEquals(0, PackageInfoRepair.repair(originals, sources, "8", null));
        assertEquals("package sample;\n", Files.readString(source, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a decompiled source that already carries an annotation is not annotated twice")
    void isIdempotent(@TempDir Path work) throws Exception {
        Path originals = work.resolve("classes");
        Path sources = work.resolve("src");
        writeEmptyPackageInfo(originals, "sample", false, false);
        Path source = writeSource(sources, "sample/package-info.java",
                "@Deprecated\npackage sample;\n");

        assertEquals(0, PackageInfoRepair.repair(originals, sources, "8", null));
        assertEquals("@Deprecated\npackage sample;\n", Files.readString(source, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("an unknown release has no annotation this tool can add safely")
    void skipsAnUnknownRelease(@TempDir Path work) throws Exception {
        Path originals = work.resolve("classes");
        Path sources = work.resolve("src");
        writeEmptyPackageInfo(originals, "sample", false, false);
        Path source = writeSource(sources, "sample/package-info.java", "package sample;\n");

        assertEquals(0, PackageInfoRepair.repair(originals, sources, null, null));
        assertEquals("package sample;\n", Files.readString(source, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a missing source is skipped rather than invented")
    void skipsAMissingSource(@TempDir Path work) throws Exception {
        Path originals = work.resolve("classes");
        writeEmptyPackageInfo(originals, "sample", false, false);

        assertEquals(0, PackageInfoRepair.repair(originals, work.resolve("src"), "8", null));
        assertFalse(Files.exists(work.resolve("src/sample/package-info.java")));
    }

    @Test
    @DisplayName("multi-release copies are not source for the main tree")
    void skipsVersionedClasses(@TempDir Path work) throws Exception {
        Path originals = work.resolve("classes");
        Path sources = work.resolve("src");
        writeEmptyPackageInfo(originals.resolve("META-INF/versions/9"), "sample", false, false);
        Path source = writeSource(sources, "sample/package-info.java", "package sample;\n");

        assertEquals(0, PackageInfoRepair.repair(originals, sources, "9", null));
        assertEquals("package sample;\n", Files.readString(source, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("comments before the package declaration do not count as annotations")
    void ignoresAnnotationsInsideComments() {
        assertFalse(PackageInfoRepair.hasPackageAnnotation(
                "/* @author someone */\npackage sample;\n"));
        assertFalse(PackageInfoRepair.hasPackageAnnotation(
                "// @deprecated\npackage sample;\n"));
        assertTrue(PackageInfoRepair.hasPackageAnnotation(
                "@Deprecated\npackage sample;\n"));
    }

    private static void writeEmptyPackageInfo(Path root, String packageName, boolean annotated,
            boolean withField) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8,
                Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNTHETIC,
                packageName.replace('.', '/') + "/package-info", null, "java/lang/Object", null);
        if (annotated) {
            AnnotationVisitor annotation = writer.visitAnnotation("Ljava/lang/Deprecated;", false);
            annotation.visitEnd();
        }
        if (withField) {
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                    "VALUE", "I", null, 1).visitEnd();
        }
        writer.visitEnd();

        Path file = root.resolve(packageName.replace('.', '/') + "/package-info.class");
        Files.createDirectories(file.getParent());
        Files.write(file, writer.toByteArray());
    }

    private static Path writeSource(Path root, String relative, String text) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }
}
