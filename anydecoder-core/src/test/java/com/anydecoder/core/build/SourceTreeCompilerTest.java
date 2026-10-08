package com.anydecoder.core.build;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The peel, which exists because javac emits nothing at all when any file has an error.
 *
 * <p>Measured on a real corpus: 2451 decompiled files produced 100 compiler errors, and the gate
 * therefore reached zero classes. The decompiled source was not unusually bad; that is simply
 * what raw decompiler output looks like. A check that cannot run until every last file is
 * repaired is the wrong shape for work that is iterative by nature.
 */
class SourceTreeCompilerTest {

    @Test
    @DisplayName("a tree that compiles is left alone")
    void leavesAGoodTreeAlone(@TempDir Path work) throws Exception {
        List<Path> sources = new ArrayList<>();
        sources.add(write(work, "sample/Good.java", "package sample; public class Good { }"));
        sources.add(write(work, "sample/AlsoGood.java", "package sample; public class AlsoGood { }"));

        SourceTreeCompiler.Result result = new SourceTreeCompiler().compile(
                sources, work.resolve("classes"), List.of(), "17");

        assertTrue(result.succeeded());
        assertEquals(1, result.rounds());
        assertTrue(result.excluded().isEmpty());
    }

    @Test
    @DisplayName("a file that will not compile is removed so the rest can be checked")
    void peelsOffTheUncompilableFile(@TempDir Path work) throws Exception {
        List<Path> sources = new ArrayList<>();
        sources.add(write(work, "sample/Good.java", "package sample; public class Good { }"));
        Path broken = write(work, "sample/Broken.java",
                "package sample; public class Broken { this is not java }");
        sources.add(broken);

        SourceTreeCompiler.Result result = new SourceTreeCompiler().compile(
                sources, work.resolve("classes"), List.of(), "17");

        assertTrue(result.succeeded(), "the peel should reach a compiling tree");
        assertEquals(List.of(broken.toAbsolutePath().normalize()), result.excluded());
        assertTrue(Files.exists(work.resolve("classes/sample/Good.class")),
                "the surviving file must actually have been compiled");
    }

    @Test
    @DisplayName("several bad files are peeled across rounds")
    void peelsSeveralFiles(@TempDir Path work) throws Exception {
        List<Path> sources = new ArrayList<>();
        sources.add(write(work, "sample/Good.java", "package sample; public class Good { }"));
        // Two files that each fail, so at least two rounds are needed.
        Path first = write(work, "sample/First.java", "package sample; public class First { ??? }");
        Path second = write(work, "sample/Second.java", "package sample; public class Second { !!! }");
        sources.add(first);
        sources.add(second);

        SourceTreeCompiler.Result result = new SourceTreeCompiler().compile(
                sources, work.resolve("classes"), List.of(), "17");

        assertTrue(result.succeeded());
        assertEquals(2, result.excluded().size());
        assertTrue(result.excluded().contains(first.toAbsolutePath().normalize()));
        assertTrue(result.excluded().contains(second.toAbsolutePath().normalize()));
    }

    @Test
    @DisplayName("a file depending on a broken one still compiles, against the original classes")
    void dependentFileSurvivesViaTheClasspath(@TempDir Path work) throws Exception {
        // The original is on the classpath as a compiled class, simulating the real setup where
        // classes.backup stands in for anything the decompiler could not produce.
        Path originalClasses = work.resolve("original-classes");
        Files.createDirectories(originalClasses.resolve("sample"));
        compileTo(work, "package sample; public class Helper { public static int n() { return 1; } }",
                originalClasses);

        List<Path> sources = new ArrayList<>();
        Path brokenHelper = write(work, "sample/Helper.java",
                "package sample; public class Helper { broken }");
        sources.add(brokenHelper);
        sources.add(write(work, "sample/User.java",
                "package sample; public class User { public int f() { return Helper.n(); } }"));

        SourceTreeCompiler.Result result = new SourceTreeCompiler().compile(
                sources, work.resolve("classes"), List.of(originalClasses), "17");

        assertTrue(result.succeeded(), "the dependent file must not be dragged down with the broken one");
        assertEquals(List.of(brokenHelper.toAbsolutePath().normalize()), result.excluded());
        assertTrue(Files.exists(work.resolve("classes/sample/User.class")),
                "User resolves Helper against the original class on the classpath");
    }

    @Test
    @DisplayName("errors that name no file stop the peel rather than spinning")
    void stopsWhenNothingCanBeRemoved(@TempDir Path work) throws Exception {
        List<Path> sources = List.of(
                write(work, "sample/Good.java", "package sample; public class Good { }"));

        SourceTreeCompiler.Result result = new SourceTreeCompiler()
                .compile(sources, work.resolve("classes"), List.of(), "not-a-release");

        assertFalse(result.succeeded(), "an unusable release is not something peeling can fix");
        assertTrue(result.excluded().isEmpty(), "no source was at fault");
    }

    // ------------------------------------------------------------------------------------------

    private static Path write(Path work, String relative, String source) throws Exception {
        Path file = work.resolve("src").resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return file;
    }

    private static void compileTo(Path work, String source, Path output) throws Exception {
        Path file = work.resolve("orig-src/sample/Helper.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
        Files.createDirectories(output);
        int status = javax.tools.ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, "-d", output.toString(), file.toString());
        assertEquals(0, status, "the fixture itself must compile");
    }
}
