package com.anydecoder.core.build;

import java.io.IOException;
import java.nio.file.Path;

/**
 * An alternative rendering of a source file that would not compile.
 *
 * <p>Decompilers fail on different inputs, and where one produces source that will not build
 * another often produces source that will. Running two engines and throwing away the second is
 * therefore leaving the most useful thing it produces on the floor: the ~350 files of a real
 * project that the first engine could not structure are, by and large, files the second engine
 * handled.
 *
 * <p>The replacement is only a candidate. Whatever ends up in the tree is compiled and gated like
 * anything else, so a worse rendering is caught by the comparison rather than preferred by
 * assumption.
 */
public interface SourceFallback {

    /**
     * Replace {@code sourceFile} with an alternative rendering, if one exists.
     *
     * @param sourceFile the file that would not compile, in the tree being built
     * @param sourceRoot the root of that tree, for computing the relative path to look up
     * @return true when the file was replaced and should be retried
     */
    boolean replace(Path sourceFile, Path sourceRoot) throws IOException;

    /** Named in the report so a reader knows which engine supplied a replacement. */
    String describe();

    /** An engine with nothing to offer. */
    static SourceFallback none() {
        return new SourceFallback() {
            @Override
            public boolean replace(Path sourceFile, Path sourceRoot) {
                return false;
            }

            @Override
            public String describe() {
                return "none";
            }
        };
    }
}
