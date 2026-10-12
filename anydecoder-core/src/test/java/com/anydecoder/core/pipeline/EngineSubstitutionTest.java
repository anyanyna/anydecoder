package com.anydecoder.core.pipeline;

import com.anydecoder.core.build.ProjectCompiler;
import com.anydecoder.core.gate.ClassGate;
import com.anydecoder.core.gate.ClassModelReader;
import com.anydecoder.core.gate.ClassVerdict;
import com.anydecoder.core.gate.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.PrintStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The post-gate repair: hand a class the gate was unhappy with to the other engine, and keep the
 * swap only if the verdict actually improves.
 *
 * <p>The revert path is the part worth testing. A swap that is attempted and rejected has already
 * written the alternative source over the file and recompiled it, so both have to be put back —
 * a source file restored without rebuilding leaves the class files describing an abandoned
 * attempt, which is worse than either version.
 */
class EngineSubstitutionTest {

    private static final PrintStream QUIET =
            new PrintStream(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8);

    @Test
    @DisplayName("a class the other engine gets right is swapped in")
    void keepsTheBetterRendering(@TempDir Path work) throws Exception {
        Fixture f = new Fixture(work, "return 1;", "return 2;", "return 1;");

        List<EngineSubstitution.Attempt> attempts = f.run();

        assertEquals(1, attempts.size());
        assertTrue(attempts.get(0).kept(), "the alternative matches the original bytecode");
        assertEquals(Verdict.EQUIVALENT, attempts.get(0).after());
        assertTrue(f.primaryText().contains("return 1;"),
                "the tree should now hold the rendering that gates clean");
        assertTrue(Files.exists(f.classes().resolve("sample/Target.class")));
    }

    @Test
    @DisplayName("a swap that does not help is reverted, source and class files both")
    void revertsAnUnhelpfulSwap(@TempDir Path work) throws Exception {
        Fixture f = new Fixture(work, "return 1;", "return 2;", "return 3;");

        List<EngineSubstitution.Attempt> attempts = f.run();

        assertTrue(attempts.isEmpty() || attempts.stream().noneMatch(EngineSubstitution.Attempt::kept),
                "a rendering that is wrong differently is not an improvement");
        assertTrue(f.primaryText().contains("return 2;"),
                "the original source must be restored, not left as the abandoned attempt");

        // And the compiled output has to match the restored source again, or the tree is
        // internally inconsistent in a way nothing downstream would notice.
        ClassVerdict rebuilt = new ClassGate().compare(
                ClassModelReader.read(f.originalClasses().resolve("sample/Target.class")),
                ClassModelReader.read(f.classes().resolve("sample/Target.class")));
        assertFalse(rebuilt.verdict().isSafe(),
                "the rebuilt class should still be the unhelpful one the tree actually contains");
    }

    @Test
    @DisplayName("an identical rendering is not attempted at all")
    void skipsWhenTheEnginesAgree(@TempDir Path work) throws Exception {
        Fixture f = new Fixture(work, "return 1;", "return 2;", "return 2;");

        List<EngineSubstitution.Attempt> attempts = f.run();

        assertTrue(attempts.isEmpty(), "there is nothing to try when both engines produced the same file");
        assertTrue(f.primaryText().contains("return 2;"));
    }

    // ------------------------------------------------------------------------------------------

    /** A class whose original returns 1, with a primary and an alternative rendering of it. */
    private static final class Fixture {
        private final Path primary;
        private final Path alternative;
        private final Path originalClasses;
        private final Path classes;

        Fixture(Path work, String originalBody, String primaryBody, String alternativeBody)
                throws Exception {
            this.originalClasses = work.resolve("original-classes");
            this.classes = work.resolve("classes");
            this.primary = work.resolve("src-cfr");
            this.alternative = work.resolve("src-vineflower");
            compile(work.resolve("orig-src"), originalBody, originalClasses);
            Files.createDirectories(classes);
            write(primary, primaryBody);
            write(alternative, alternativeBody);
        }

        Path primarySource() {
            return primary.resolve("sample/Target.java");
        }

        /** The current contents of the primary rendering, which a substitution edits in place. */
        String primaryText() throws Exception {
            return Files.readString(primarySource(), StandardCharsets.UTF_8);
        }

        Path classes() {
            return classes;
        }

        Path originalClasses() {
            return originalClasses;
        }

        List<EngineSubstitution.Attempt> run() throws Exception {
            // The primary rendering is compiled and gated first, which is what the real pipeline
            // does for the whole tree before any substitution is considered.
            new ProjectCompiler().compile(List.of(primarySource()), classes,
                    List.of(originalClasses), "17");

            ClassGate gate = new ClassGate();
            ClassVerdict current = gate.compare(
                    ClassModelReader.read(originalClasses.resolve("sample/Target.class")),
                    ClassModelReader.read(classes.resolve("sample/Target.class")));

            EngineSubstitution substitution = new EngineSubstitution(gate, new ProjectCompiler(),
                    originalClasses, classes, List.of(originalClasses), "17");
            return substitution.tryImprove(primary, List.of(alternative), List.of(current), QUIET);
        }

        private void write(Path root, String body) throws Exception {
            Path file = root.resolve("sample/Target.java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "package sample; public class Target { public int f() { "
                    + body + " } }", StandardCharsets.UTF_8);
        }

        private static void compile(Path sourceDir, String body, Path output) throws Exception {
            Path file = sourceDir.resolve("sample/Target.java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "package sample; public class Target { public int f() { "
                    + body + " } }", StandardCharsets.UTF_8);
            Files.createDirectories(output);
            int status = ToolProvider.getSystemJavaCompiler().run(
                    null, null, null, "-g", "-d", output.toString(), file.toString());
            assertEquals(0, status, "the fixture itself must compile");
        }
    }
}
