package com.anydecoder.core.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JSON writer, tested on its own because the report is a machine interface.
 *
 * <p>{@code report.json} is what an agent reads to drive repairs. A stray quote or an unescaped
 * newline in a diagnostic does not make the report look wrong -- it makes it unparseable, and the
 * reader is a program that cannot fall back to reading the Markdown. The Markdown writer has tests
 * for what it says; this one is for whether the JSON can be read at all.
 */
class JsonTest {

    @Test
    @DisplayName("quotes, backslashes and newlines are escaped, not emitted raw")
    void escapesCharactersThatWouldEndTheString() {
        String json = Json.write("a\"b\\c\nd\te");

        assertEquals("\"a\\\"b\\\\c\\nd\\te\"", json);
    }

    @Test
    @DisplayName("control characters become \\u escapes")
    void escapesControlCharactersAsUnicode() {
        String json = Json.write("\u0001\u001f");

        assertEquals("\"\\u0001\\u001f\"", json);
        assertFalse(json.indexOf('\u0001') >= 0, "a raw control character is not valid JSON");
        assertFalse(json.indexOf('\u001f') >= 0, "a raw control character is not valid JSON");
    }

    @Test
    @DisplayName("non-ASCII text is left as text, because the report is meant to be readable")
    void leavesNonAsciiTextAlone() {
        assertEquals("\"中文报告\"", Json.write("中文报告"));
    }

    @Test
    @DisplayName("non-finite numbers become null rather than invalid JSON")
    void writesNonFiniteNumbersAsNull() {
        assertEquals("null", Json.write(Double.NaN));
        assertEquals("null", Json.write(Double.POSITIVE_INFINITY));
        assertEquals("null", Json.write(Double.NEGATIVE_INFINITY));
        assertEquals("null", Json.write(Float.NaN));
        assertEquals("null", Json.write(Float.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("empty containers stay on one line")
    void writesEmptyContainersCompactly() {
        assertEquals("{}", Json.write(Map.of()));
        assertEquals("[]", Json.write(List.of()));
    }

    @Test
    @DisplayName("a list of scalars stays on one line, because the report is read by people too")
    void keepsScalarListsOnOneLine() {
        assertEquals("[1, 2, 3]", Json.write(List.of(1, 2, 3)));
        assertEquals("[\"a\", \"b\"]", Json.write(List.of("a", "b")));
    }

    @Test
    @DisplayName("nested structures keep insertion order and indent their members")
    void writesNestedStructuresInOrder() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tool", "anydecoder");
        root.put("counts", Map.of("good", 2));
        root.put("names", List.of(Map.of("class", "com/example/Foo")));

        String json = Json.write(root);

        assertTrue(json.indexOf("\"tool\"") < json.indexOf("\"counts\""),
                "a LinkedHashMap's order is the report's order, not the writer's");
        assertTrue(json.contains("{\n  \"tool\": \"anydecoder\""), json);
        assertTrue(json.contains("\"names\": [\n"), "a list of objects is not crammed onto one line");
        assertTrue(json.contains("\"class\": \"com/example/Foo\""), json);
    }
}
