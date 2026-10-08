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
        assertEquals(List.of(broken.toAbsolutePath().normalize()), excludedPaths(result));
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
        List<Path> paths = excludedPaths(result);
        assertEquals(2, paths.size());
        assertTrue(paths.contains(first.toAbsolutePath().normalize()));
        assertTrue(paths.contains(second.toAbsolutePath().normalize()));
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
        assertEquals(List.of(brokenHelper.toAbsolutePath().normalize()), excludedPaths(result));
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

    @Test
    @DisplayName("a second engine's rendering rescues a file the first could not structure")
    void fallbackEngineRescuesAFile(@TempDir Path work) throws Exception {
        Path primary = work.resolve("src-cfr");
        Path alternative = work.resolve("src-vineflower");
        Path broken = writeUnder(primary, "sample/Broken.java",
                "package sample; public class Broken { this is not java }");
        writeUnder(alternative, "sample/Broken.java",
                "package sample; public class Broken { }");
        writeUnder(primary, "sample/Fine.java", "package sample; public class Fine { }");

        SourceTreeCompiler.Result result = new SourceTreeCompiler().compile(
                List.of(broken, primary.resolve("sample/Fine.java")), primary,
                work.resolve("classes"), List.of(), "17",
                new OtherEngineFallback(List.of(alternative)), null);

        assertTrue(result.succeeded());
        assertEquals(1, result.substituted(), "the broken file should have been replaced, not dropped");
        assertTrue(result.excluded().isEmpty(),
                "nothing should have been set aside once a working rendering was available");
        assertTrue(Files.exists(work.resolve("classes/sample/Broken.class")),
                "the substituted file must actually have been compiled");
    }

    @Test
    @DisplayName("a file the fallback also cannot compile is set aside, not retried forever")
    void fallbackThatAlsoFailsIsSetAside(@TempDir Path work) throws Exception {
        Path primary = work.resolve("src-cfr");
        Path alternative = work.resolve("src-vineflower");
        Path broken = writeUnder(primary, "sample/Broken.java",
                "package sample; public class Broken { ??? }");
        // Both engines failed on this file, which is what the fallback has to cope with.
        writeUnder(alternative, "sample/Broken.java",
                "package sample; public class Broken { !!! }");
        writeUnder(primary, "sample/Fine.java", "package sample; public class Fine { }");

        SourceTreeCompiler.Result result = new SourceTreeCompiler().compile(
                List.of(broken, primary.resolve("sample/Fine.java")), primary,
                work.resolve("classes"), List.of(), "17",
                new OtherEngineFallback(List.of(alternative)), null);

        assertTrue(result.succeeded());
        assertEquals(List.of(broken.toAbsolutePath().normalize()), excludedPaths(result));
        assertTrue(result.substituted() <= 1, "the fallback must be tried once per file");
    }

    @Test
    @DisplayName("an API the JDK removed is not reported as a source defect")
    void missingJdkApiIsClassifiedSeparately(@TempDir Path work) throws Exception {
        Path broken = write(work, "sample/UsesRemovedApi.java",
                "package sample;\nimport sun.misc.BASE64Encoder;\n"
                        + "public class UsesRemovedApi { BASE64Encoder e; }");

        SourceTreeCompiler.Result result = new SourceTreeCompiler().compile(
                List.of(broken), work.resolve("classes"), List.of(), "17");

        assertTrue(result.succeeded());
        assertEquals(1, result.excluded().size());
        assertEquals(1, result.missingJdkApi().size(),
                "BASE64Encoder was removed in Java 9; no --release brings it back, so this is"
                        + " the environment rather than the source");
        assertTrue(result.sourceDefects().isEmpty(),
                "sending a reader to repair this source would waste their time");
    }

    @Test
    @DisplayName("a genuine syntax error is reported as a source defect")
    void syntaxErrorIsASourceDefect(@TempDir Path work) throws Exception {
        Path broken = write(work, "sample/Broken.java",
                "package sample; public class Broken { this is not java }");

        SourceTreeCompiler.Result result = new SourceTreeCompiler().compile(
                List.of(broken), work.resolve("classes"), List.of(), "17");

        assertEquals(1, result.sourceDefects().size());
        assertTrue(result.missingJdkApi().isEmpty());
    }

    @Test
    @DisplayName("compiler diagnostics come back in English whatever the machine's locale is")
    void diagnosticsAreEnglishRegardlessOfDefaultLocale(@TempDir Path work) throws Exception {
        Path broken = write(work, "sample/Bad.java",
                "package sample; public class Bad { int x = \"not an int\"; }");

        // The build sets -Duser.language=en, which is exactly why nothing caught this: javac
        // builds its message text from the JVM default locale, not from the locale handed to the
        // file manager, so in production the diagnostics came back in Chinese while every test
        // saw English. Reproducing the machine's condition is the only way to test the fix.
        java.util.Locale previous = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE);
        SourceTreeCompiler.Result result;
        try {
            result = new SourceTreeCompiler().compile(
                    List.of(broken), work.resolve("classes"), List.of(), "17");
        } finally {
            java.util.Locale.setDefault(previous);
        }

        String reason = result.excluded().get(0).reason();
        assertTrue(reason.codePoints().noneMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF),
                "the message is quoted into reports and matched by the classifier, so a"
                        + " localised one is unreliable for both: got " + reason);
    }

    private static Path writeUnder(Path root, String relative, String source) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return file;
    }

    /** The paths of the sources the rebuild set aside, sorted by the compiler. */
    private static List<Path> excludedPaths(SourceTreeCompiler.Result result) {
        return result.excluded().stream()
                .map(SourceTreeCompiler.ExcludedSource::path)
                .toList();
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
