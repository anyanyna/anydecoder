package com.anydecoder.core.pipeline;

import com.anydecoder.core.engine.Decompiler;
import com.anydecoder.core.engine.DecompilerException;
import com.anydecoder.core.engine.ToolArtifact;
import com.anydecoder.core.engine.ToolCache;
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
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What happens when a decompiler fails.
 *
 * <p>Running several engines is worth doing precisely because they fail on different inputs, and
 * a heap too small for a large project is the common case. Aborting the run on the first failure
 * throws away a decompilation that had already succeeded — measured on a real project, Vineflower
 * exhausted its heap on 2984 classes and the run died with CFR's 2451 files sitting on disk.
 *
 * <p>This cannot be provoked with the real engines on demand, so the engine is injectable.
 */
class DecoderPipelineEngineFailureTest {

    private static final PrintStream QUIET =
            new PrintStream(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8);

    private static final String SOURCE =
            "package sample; public class Tiny { public int f() { return 1; } }";

    @Test
    @DisplayName("an engine that fails does not abort the run")
    void continuesAfterAFailingEngine(@TempDir Path work) throws Exception {
        Path classes = compileSample(work);

        PipelineOutcome outcome = new DecoderPipeline(null, factory(Map.of(
                "boom", failing("boom"),
                "ok", writing("ok", SOURCE))))
                .run(options(work, classes, List.of("boom", "ok")), QUIET);

        assertEquals(1, outcome.failedEngines().size(),
                "the failure is recorded, not swallowed");
        assertTrue(outcome.failedEngines().containsKey("boom"));
        assertTrue(outcome.sourcesByEngine().containsKey("ok"));
        assertEquals(outcome.sourcesByEngine().get("ok"), outcome.primarySource(),
                "the rebuild has to come from an engine that actually produced source");
    }

    @Test
    @DisplayName("the first engine failing is recorded, and the run says which was used")
    void fallsBackToTheNextEngine(@TempDir Path work) throws Exception {
        Path classes = compileSample(work);

        PipelineOutcome outcome = new DecoderPipeline(null, factory(Map.of(
                "primary", failing("primary"),
                "secondary", writing("secondary", SOURCE))))
                .run(options(work, classes, List.of("primary", "secondary")), QUIET);

        assertTrue(outcome.failedEngines().containsKey("primary"),
                "a tree from the second choice is not the same as one from the first, so it is named");
        assertFalse(outcome.sourcesByEngine().containsKey("primary"));
        assertTrue(outcome.primarySource().toString().contains("secondary"));
    }

    @Test
    @DisplayName("an engine that writes nothing counts as failed")
    void treatsEmptyOutputAsFailure(@TempDir Path work) throws Exception {
        Path classes = compileSample(work);

        PipelineOutcome outcome = new DecoderPipeline(null, factory(Map.of(
                "empty", writing("empty", null),
                "ok", writing("ok", SOURCE))))
                .run(options(work, classes, List.of("empty", "ok")), QUIET);

        assertTrue(outcome.failedEngines().containsKey("empty"),
                "exiting zero without producing source is a failure however the process ended");
        assertEquals("produced no source", outcome.failedEngines().get("empty"),
                "saying which is more use than saying it failed");
    }

    @Test
    @DisplayName("every engine failing is a failure of the run")
    void failsWhenNoEngineProducesSource(@TempDir Path work) throws Exception {
        Path classes = compileSample(work);

        try {
            new DecoderPipeline(null, factory(Map.of(
                    "a", failing("a"),
                    "b", failing("b"))))
                    .run(options(work, classes, List.of("a", "b")), QUIET);
            org.junit.jupiter.api.Assertions.fail("expected the run to fail");
        } catch (DecompilerException e) {
            assertTrue(e.getMessage().contains("a") && e.getMessage().contains("b"),
                    "the message has to say which engines were tried: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------

    private static Function<String, Decompiler> factory(Map<String, Decompiler> engines) {
        return id -> {
            Decompiler engine = engines.get(id);
            if (engine == null) {
                throw new IllegalArgumentException("no engine " + id);
            }
            return engine;
        };
    }

    /** An engine that fails the way a decompiler does when it runs out of memory. */
    private static Decompiler failing(String id) {
        return new Stub(id) {
            @Override
            public void decompile(Path input, Path outputDir, List<Path> classpath, PrintStream log)
                    throws DecompilerException {
                throw new DecompilerException(id + " exhausted its heap");
            }
        };
    }

    /** An engine that writes a source file, or nothing when the source is null. */
    private static Decompiler writing(String id, String source) {
        return new Stub(id) {
            @Override
            public void decompile(Path input, Path outputDir, List<Path> classpath, PrintStream log)
                    throws DecompilerException {
                if (source == null) {
                    return;
                }
                try {
                    Path file = outputDir.resolve("sample/Tiny.java");
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, source, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new DecompilerException("could not write the stub's output", e);
                }
            }
        };
    }

    private abstract static class Stub implements Decompiler {
        private final String id;

        Stub(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String version() {
            return "stub";
        }

        @Override
        public ToolArtifact artifact() {
            return new ToolArtifact(id, "stub", id + ".jar", null, null, null);
        }
    }

    private static PipelineOptions options(Path work, Path classes, List<String> engines) {
        return new PipelineOptions(classes, work.resolve("scratch"), work.resolve("out"),
                engines, true, false, List.of(), null)
                .withWaivers(null);
    }

    private static Path compileSample(Path work) throws Exception {
        Path source = work.resolve("src/sample/Tiny.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, SOURCE, StandardCharsets.UTF_8);
        Path classes = work.resolve("classes");
        Files.createDirectories(classes);
        int status = ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "-g", "-d", classes.toString(), source.toString());
        assertEquals(0, status, "the fixture itself must compile");
        return classes;
    }
}