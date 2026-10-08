package com.anydecoder.core.normalize;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text-level helpers for reading decompiler output.
 *
 * <p>Every one of these has to be aware of string and character literals. A regular expression
 * that strips {@code //} without that awareness will happily delete the tail of
 * {@code String url = "http://example.com";}, and the result still compiles -- into something
 * that does a different thing.
 */
public final class JavaText {

    /**
     * An import declaration. The name part accepts a trailing {@code .*}, so that wildcard
     * imports are recognised rather than silently skipped -- a caller that does not know a
     * wildcard is present cannot reason about what removing another import would rebind. The
     * leading run is spaces and tabs only, never {@code \s}, so a match can never start on the
     * previous line.
     */
    private static final Pattern IMPORT = Pattern.compile(
            "^[ \\t]*import\\s+(static\\s+)?([\\w$.]+(?:\\.\\*)?)[ \\t]*;", Pattern.MULTILINE);

    private JavaText() {
    }

    /** Remove comments, leaving string and character literals untouched. */
    public static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                char quote = c;
                out.append(c);
                i++;
                while (i < n) {
                    char inner = source.charAt(i);
                    if (inner == '\\' && i + 1 < n) {
                        out.append(source, i, i + 2);
                        i += 2;
                        continue;
                    }
                    out.append(inner);
                    i++;
                    if (inner == quote || inner == '\n') {
                        break;
                    }
                }
                continue;
            }
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Imported fully-qualified names mapped to the simple name they bind, ignoring wildcards. */
    public static Map<String, String> importMap(String source) {
        Map<String, String> map = new LinkedHashMap<>();
        Matcher m = IMPORT.matcher(source);
        while (m.find()) {
            String fq = m.group(2);
            if (fq.endsWith(".*")) {
                continue;
            }
            map.put(fq, fq.substring(fq.lastIndexOf('.') + 1));
        }
        return map;
    }

    /** Every single-type import in the file, in source order, wildcards included. */
    public static List<ImportStatement> imports(String source) {
        List<ImportStatement> out = new ArrayList<>();
        Matcher m = IMPORT.matcher(source);
        while (m.find()) {
            out.add(new ImportStatement(m.start(), m.end(),
                    m.group(1) != null, m.group(2)));
        }
        return out;
    }

    /**
     * The part of the file after the last import. Used to decide whether a simple name is
     * actually referenced, as opposed to merely appearing in the import list itself.
     */
    public static String bodyAfterImports(String source) {
        int lastEnd = 0;
        Matcher m = IMPORT.matcher(source);
        while (m.find()) {
            lastEnd = m.end();
        }
        return lastEnd == 0 ? source : source.substring(lastEnd);
    }

    /**
     * Whether {@code simpleName} appears as a whole identifier in {@code text}.
     *
     * <p>The left boundary excludes word characters but <em>not</em> {@code .}, so a
     * fully-qualified {@code java.util.Date} counts as a mention of {@code Date}. That errs
     * toward reporting a use, which errs toward leaving an import in place -- the harmless
     * direction. A substring is still not a mention: {@code UpdateDate} is not a use of
     * {@code Date}.
     */
    public static boolean usesIdentifier(String text, String simpleName) {
        if (simpleName.isEmpty()) {
            return false;
        }
        String withoutComments = stripComments(text);
        Pattern p = Pattern.compile("(?<![\\w$])" + Pattern.quote(simpleName) + "(?![\\w$])");
        return p.matcher(withoutComments).find();
    }

    /** A single {@code import} declaration and where it sits in the file. */
    public record ImportStatement(int start, int end, boolean isStatic, String name) {
        public boolean isWildcard() {
            return name.endsWith(".*");
        }

        public String simpleName() {
            return isWildcard() ? "*" : name.substring(name.lastIndexOf('.') + 1);
        }
    }
}
