package com.anydecoder.core.difftest;

import com.anydecoder.core.gate.ClassGate;
import com.anydecoder.core.gate.ClassModelReader;
import com.anydecoder.core.gate.ClassVerdict;
import com.anydecoder.core.gate.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shows the two oracles are complementary: the case below defeats every static signal, and the
 * differential run catches it.
 *
 * <p>If this test ever starts failing because the gate flags these classes, that is good news --
 * it would mean a signal became sensitive to operand order. It should then be rewritten to
 * document the new capability rather than deleted.
 */
class DifferentialTesterTest {

    @Test
    @DisplayName("operand order swapped in a subtraction: invisible to the gate, caught at runtime")
    void catchesAnOperandSwapTheStaticGateCannotSee(@TempDir Path work) throws Exception {
        Path original = compile(work.resolve("original"),
                "package sample;\n"
                        + "public class Differ {\n"
                        + "    public int diff(int a, int b) { return a - b; }\n"
                        + "}\n");
        Path rebuilt = compile(work.resolve("rebuilt"),
                "package sample;\n"
                        + "public class Differ {\n"
                        + "    public int diff(int a, int b) { return b - a; }\n"
                        + "}\n");

        ClassVerdict verdict = new ClassGate().compare(
                ClassModelReader.read(original.resolve("sample/Differ.class")),
                ClassModelReader.read(rebuilt.resolve("sample/Differ.class")));

        assertEquals(Verdict.EQUIVALENT, verdict.verdict(),
                "every signal the gate has is a property of the instruction multiset, and"
                        + " iload slots are deliberately not compared (they differ between"
                        + " compilers), so both bodies look the same to it");

        DifferentialTester.Result result = new DifferentialTester(
                DifferentialTesterOptions.defaults())
                .run(original, rebuilt, List.of());

        assertFalse(result.clean(), "running both versions must expose the swap");
        assertTrue(result.divergences().stream().anyMatch(d -> d.method().startsWith("diff")),
                "the divergence should name diff, got " + result.divergences());
        assertTrue(result.casesRun() > 0);
    }

    @Test
    @DisplayName("equivalent classes produce no divergence")
    void agreesWhenThereIsNothingToFind(@TempDir Path work) throws Exception {
        String source = "package sample;\n"
                + "public class Same {\n"
                + "    public int add(int a, int b) { return a + b; }\n"
                + "    public String shout(String s) { return s.toUpperCase(); }\n"
                + "}\n";
        Path original = compile(work.resolve("original"), source);
        Path rebuilt = compile(work.resolve("rebuilt"), source);

        DifferentialTester.Result result = new DifferentialTester(
                DifferentialTesterOptions.defaults())
                .run(original, rebuilt, List.of());

        assertTrue(result.clean(), "unexpected divergences: " + result.divergences());
        assertTrue(result.methodsExercised() >= 2,
                "both methods should have been driven, got " + result.methodsExercised());
    }

    @Test
    @DisplayName("a rebound exception type is caught")
    void catchesAChangedExceptionType(@TempDir Path work) throws Exception {
        Path original = compile(work.resolve("original"),
                "package sample;\n"
                        + "public class Thrower {\n"
                        + "    public int f(int a) { if (a < 0) throw new IllegalArgumentException(); return a; }\n"
                        + "}\n");
        Path rebuilt = compile(work.resolve("rebuilt"),
                "package sample;\n"
                        + "public class Thrower {\n"
                        + "    public int f(int a) { if (a < 0) throw new IllegalStateException(); return a; }\n"
                        + "}\n");

        DifferentialTester.Result result = new DifferentialTester(
                DifferentialTesterOptions.defaults())
                .run(original, rebuilt, List.of());

        assertFalse(result.clean());
        assertTrue(result.divergences().get(0).originalResult().contains("IllegalArgumentException"));
        assertTrue(result.divergences().get(0).rebuiltResult().contains("IllegalStateException"));
    }

    /** Compile one source file and return the directory holding its class files. */
    private static Path compile(Path output, String source) throws Exception {
        Path sourceDir = output.resolve("src");
        Files.createDirectories(sourceDir.resolve("sample"));
        // A public class has to live in a file named after it.
        String className = source.lines()
                .filter(l -> l.contains("class "))
                .findFirst()
                .map(l -> l.replaceAll(".*class\\s+(\\w+).*", "$1"))
                .orElseThrow(() -> new IllegalArgumentException("no class in fixture: " + source));
        Path file = sourceDir.resolve("sample/" + className + ".java");
        Files.writeString(file, source, StandardCharsets.UTF_8);

        Path classes = output.resolve("classes");
        Files.createDirectories(classes);

        var compiler = ToolProvider.getSystemJavaCompiler();
        int status = compiler.run(null, null, null,
                "-g", "-d", classes.toString(), file.toString());
        assertEquals(0, status, "the test fixture itself must compile");
        return classes;
    }
}
