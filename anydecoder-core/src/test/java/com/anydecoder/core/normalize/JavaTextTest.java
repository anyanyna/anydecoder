package com.anydecoder.core.normalize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaTextTest {

    @Test
    @DisplayName("a // inside a string literal is not a comment")
    void doesNotTreatUrlAsAComment() {
        String source = "class A { String u = \"http://example.com/x\"; int n = 1; }";

        String stripped = JavaText.stripComments(source);

        assertTrue(stripped.contains("http://example.com/x"),
                "removing the comment marker truncated the URL, and the result still compiles");
        assertTrue(stripped.contains("int n = 1;"));
    }

    @Test
    @DisplayName("a /* inside a string literal does not open a comment")
    void doesNotTreatBlockMarkerInStringAsAComment() {
        String source = "class A { String s = \"/*not a comment*/\"; int n = 1; }";

        String stripped = JavaText.stripComments(source);

        assertTrue(stripped.contains("int n = 1;"), "the rest of the file was swallowed");
    }

    @Test
    @DisplayName("real comments are removed")
    void removesRealComments() {
        String source = "class A {\n  // gone\n  int n = 1; /* gone too */\n}";

        String stripped = JavaText.stripComments(source);

        assertFalse(stripped.contains("gone"));
        assertTrue(stripped.contains("int n = 1;"));
    }

    @Test
    @DisplayName("an escaped quote inside a string does not end it")
    void handlesEscapedQuotes() {
        String source = "class A { String s = \"a\\\"b // c\"; int n = 1; }";

        String stripped = JavaText.stripComments(source);

        assertTrue(stripped.contains("int n = 1;"));
    }

    @Test
    @DisplayName("identifier use is matched whole, not as a substring")
    void doesNotCountSubstringAsUsage() {
        String body = "int UpdateDate = 1;";

        assertFalse(JavaText.usesIdentifier(body, "Date"),
                "UpdateDate is not a use of Date");
        assertTrue(JavaText.usesIdentifier("Date d = null;", "Date"));
        assertTrue(JavaText.usesIdentifier("java.util.Date d;", "Date"));
    }

    @Test
    @DisplayName("an identifier inside a comment does not count as a use")
    void ignoresCommentsWhenCheckingUsage() {
        String body = "// Date is mentioned here only\nint n = 1;";

        assertFalse(JavaText.usesIdentifier(body, "Date"),
                "keeping the import is the safe direction, but this check must not be fooled"
                        + " into the unsafe one either");
    }

    @Test
    @DisplayName("imports are listed with their positions and static flag")
    void listsImports() {
        String source = "package a;\nimport java.util.List;\nimport static java.util.Comparator.*;\nimport java.util.*;\n";

        var imports = JavaText.imports(source);

        assertEquals(3, imports.size());
        assertEquals("java.util.List", imports.get(0).name());
        assertFalse(imports.get(0).isStatic());
        assertEquals("java.util.Comparator.*", imports.get(1).name());
        assertTrue(imports.get(1).isStatic());
        assertTrue(imports.get(2).isWildcard());
    }

    @Test
    @DisplayName("the import map binds simple names, skipping wildcards")
    void buildsImportMap() {
        Map<String, String> map = JavaText.importMap(
                "import java.util.List;\nimport java.util.*;\nimport java.io.File;\n");

        assertEquals("List", map.get("java.util.List"));
        assertEquals("File", map.get("java.io.File"));
        assertEquals(2, map.size(), "a wildcard has no single binding to record");
    }

    @Test
    @DisplayName("the body starts after the last import, so imports do not count as usage")
    void bodyExcludesImports() {
        String source = "package a;\nimport java.util.List;\n\nclass A { }\n";

        String body = JavaText.bodyAfterImports(source);

        assertFalse(body.contains("import"));
        assertTrue(body.contains("class A"));
    }
}
