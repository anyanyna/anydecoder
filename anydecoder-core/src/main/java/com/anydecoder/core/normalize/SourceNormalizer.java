package com.anydecoder.core.normalize;

import java.util.List;

/**
 * A source-level cleanup applied to decompiler output.
 *
 * <p>Two rules hold for every implementation:
 *
 * <ol>
 *   <li><b>A normalizer never has to be trusted.</b> The run recompiles and re-gates after
 *       every pass; a pass that changes bytecode is caught and the affected files are reverted.
 *       This is what makes aggressive cleanups safe to attempt.</li>
 *   <li><b>A normalizer reports what it did.</b> Changes are counted and attributed, so the
 *       report can show that 543 files had their CJK escapes decoded rather than quietly
 *       rewriting a quarter of the tree.</li>
 * </ol>
 */
public interface SourceNormalizer {

    String id();

    /** One line explaining what this pass is for, shown in reports. */
    String description();

    Result normalize(String source);

    /**
     * @param source  the rewritten source, identical to the input when nothing applied
     * @param changes human-readable notes, empty when nothing applied
     * @param metadata facts worth carrying into the report even when the source is unchanged,
     *                 such as which classes a decompiler could not resolve
     */
    record Result(String source, List<String> changes, java.util.Map<String, String> metadata) {

        public static Result unchanged(String source) {
            return new Result(source, List.of(), java.util.Map.of());
        }

        public boolean changed() {
            return !changes.isEmpty();
        }
    }
}
