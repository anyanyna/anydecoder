package com.anydecoder.core.normalize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceNormalizerTest {

    @Nested
    class CfrHeader {

        private final CfrHeaderNormalizer normalizer = new CfrHeaderNormalizer();

        @Test
        @DisplayName("the banner is removed and its unresolved-class list is kept")
        void keepsUnresolvedClassesAsMetadata() {
            String source = """
                    /*
                     * Decompiled with CFR 0.152.
                     *
                     * Could not load the following classes:
                     *  org.zkoss.zul.Window
                     *  com.example.Missing
                     */
                    package a;

                    class A { }
                    """;

            var result = normalizer.normalize(source);

            assertTrue(result.changed());
            assertFalse(result.source().contains("Decompiled with"));
            assertFalse(result.source().contains("Could not load"));
            assertTrue(result.source().startsWith("package a;"));
            assertEquals("org.zkoss.zul.Window,com.example.Missing",
                    result.metadata().get("unresolvedClasses"),
                    "the classes CFR could not resolve are the best quality signal about a run");
        }

        @Test
        @DisplayName("a banner naming no unresolved class still comes off")
        void removesBannerWithoutUnresolvedList() {
            String source = "/* Decompiled with CFR 0.152. */\npackage a;\nclass A { }\n";

            var result = normalizer.normalize(source);

            assertTrue(result.changed());
            assertTrue(result.metadata().isEmpty());
            assertTrue(result.source().startsWith("package a;"));
        }

        @Test
        @DisplayName("a file with no banner is left alone")
        void leavesHandWrittenSourceAlone() {
            String source = "package a;\nclass A { }\n";

            assertFalse(normalizer.normalize(source).changed());
        }
    }

    @Nested
    class CjkEscapes {

        /**
         * A single backslash as it appears in Java source. Written this way because a literal
         * {@code \\u....} in this file would be consumed by Java's own unicode escape pre-pass
         * before javac ever lexed it -- the same hazard the normalizer exists to respect.
         */
        private static final String BS = "\\";

        private final CjkEscapeNormalizer normalizer = new CjkEscapeNormalizer();

        @Test
        @DisplayName("CJK escapes become characters")
        void decodesCjk() {
            String cjk = BS + "u4e2d" + BS + "u6587";

            var result = normalizer.normalize("class A { String s = \"" + cjk + "\"; }");

            assertTrue(result.source().contains("中文"));
            assertFalse(result.source().contains(BS + "u4e2d"));
        }

        @Test
        @DisplayName("an escape outside the CJK range is left alone")
        void leavesOtherEscapesAlone() {
            String newlineEscape = BS + "u000a";
            String eAcuteEscape = BS + "u00e9";
            String source = "class A { String s = \"a" + newlineEscape + "b\";"
                    + " String t = \"" + eAcuteEscape + "\"; }";

            var result = normalizer.normalize(source);

            assertFalse(result.changed());
            assertTrue(result.source().contains(newlineEscape));
            assertTrue(result.source().contains(eAcuteEscape));
        }
    }

    @Nested
    class UnusedImports {

        private final UnusedImportNormalizer normalizer = new UnusedImportNormalizer();

        @Test
        @DisplayName("an import the file never uses is removed")
        void removesUnusedImport() {
            String source = "import java.util.List;\nimport java.io.File;\n\nclass A { File f; }\n";

            var result = normalizer.normalize(source);

            assertTrue(result.changed());
            assertFalse(result.source().contains("java.util.List"));
            assertTrue(result.source().contains("java.io.File"));
        }

        @Test
        @DisplayName("a substring is not a use, so the import still goes")
        void removesImportWhenOnlyASubstringMatches() {
            String source = "import java.util.Date;\n\nclass A { int UpdateDate; }\n";

            var result = normalizer.normalize(source);

            assertTrue(result.changed(),
                    "UpdateDate is a different identifier from Date, so the import is genuinely"
                            + " unused; treating the substring as a use would leave every import"
                            + " alive that shared a fragment with anything in the file");
            assertFalse(result.source().contains("java.util.Date"));
            assertTrue(result.source().contains("int UpdateDate;"));
        }

        @Test
        @DisplayName("an import used in the body is kept")
        void keepsUsedImport() {
            String source = "import java.util.List;\n\nclass A { List<String> l; }\n";

            assertFalse(normalizer.normalize(source).changed());
        }

        @Test
        @DisplayName("a wildcard import is never removed")
        void neverRemovesWildcards() {
            String source = "import java.util.*;\n\nclass A { }\n";

            assertFalse(normalizer.normalize(source).changed(),
                    "a wildcard has no simple name to test, and its reach is invisible to a"
                            + " text scan");
        }

        @Test
        @DisplayName("a static import is never removed")
        void neverRemovesStaticImports() {
            String source = "import static java.util.Collections.emptyList;\n\nclass A { }\n";

            assertFalse(normalizer.normalize(source).changed(),
                    "static imports take part in overload resolution by signature");
        }

        @Test
        @DisplayName("an import is kept when another import binds the same simple name")
        void keepsAmbiguousImports() {
            String source = "import java.sql.Date;\nimport java.util.Date;\n\n"
                    + "class A { }\n";

            assertFalse(normalizer.normalize(source).changed(),
                    "this is exactly the case where removing one changes which overload binds");
        }
    }

    @Nested
    class BlockLabels {

        private final BlockLabelNormalizer normalizer = new BlockLabelNormalizer();

        @Test
        @DisplayName("a label on a block nothing breaks out of is decoration and goes")
        void removesUnusedBlockLabel() {
            String source = """
                    class A {
                        void f() {
                            block0: {
                                int x = 1;
                                System.out.println(x);
                            }
                        }
                    }
                    """;

            var result = normalizer.normalize(source);

            assertTrue(result.changed());
            assertFalse(result.source().contains("block0"));
            assertTrue(result.source().contains("int x = 1;"));
            assertTrue(result.source().contains("System.out.println(x);"));
        }

        @Test
        @DisplayName("a loop label comes off when the body holds no nested loop")
        void removesLoopLabelWithoutNestedLoop() {
            String source = """
                    class A {
                        void f() {
                            block1: for (int i = 0; i < 3; i++) {
                                if (i == 2) {
                                    break block1;
                                }
                                System.out.println(i);
                            }
                        }
                    }
                    """;

            var result = normalizer.normalize(source);

            assertTrue(result.changed());
            assertFalse(result.source().contains("block1"));
            assertTrue(result.source().contains("break;"));
            assertTrue(result.source().contains("for (int i = 0;"));
        }

        @Test
        @DisplayName("a loop label stays when a nested loop could capture the branch")
        void keepsLoopLabelWithNestedLoop() {
            String source = """
                    class A {
                        void f() {
                            block1: for (int i = 0; i < 3; i++) {
                                while (true) {
                                    break block1;
                                }
                            }
                        }
                    }
                    """;

            assertFalse(normalizer.normalize(source).changed(),
                    "removing the label here would retarget the break at the inner loop");
        }
    }
}
