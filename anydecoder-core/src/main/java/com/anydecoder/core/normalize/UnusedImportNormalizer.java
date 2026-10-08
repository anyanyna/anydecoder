package com.anydecoder.core.normalize;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Removes imports whose simple name the file never mentions.
 *
 * <p>This pass is the one that looks safest and is not. Deleting {@code import a.b.C;} from a
 * file that never writes {@code C} is fine right up until the classpath also offers another
 * {@code C}: then the import was the only thing selecting which one an unqualified use
 * resolves to, and removing it changes which overload is called while still compiling cleanly.
 * Rewriting an unused import into a silent behaviour change is the worst outcome available, so
 * three guards apply:
 *
 * <ul>
 *   <li>wildcard imports are never removed -- {@code import a.b.*;} has no simple name to check,
 *       and its reach is invisible to a text scan;</li>
 *   <li>static imports are never removed -- they participate in overload resolution by
 *       signature, not by name;</li>
 *   <li>an import is left alone when the file also imports something else under the same simple
 *       name, which is precisely the ambiguous case.</li>
 * </ul>
 *
 * <p>The check itself is on whole identifiers, not substrings: {@code UpdateDate} does not keep
 * {@code Date} alive.
 */
public final class UnusedImportNormalizer implements SourceNormalizer {

    @Override
    public String id() {
        return "R4-unused-imports";
    }

    @Override
    public String description() {
        return "remove imports whose simple name the file never uses";
    }

    @Override
    public Result normalize(String source) {
        List<JavaText.ImportStatement> imports = JavaText.imports(source);
        if (imports.isEmpty()) {
            return Result.unchanged(source);
        }
        String body = JavaText.bodyAfterImports(source);

        Map<String, Integer> simpleNameCounts = new HashMap<>();
        for (JavaText.ImportStatement statement : imports) {
            if (!statement.isStatic() && !statement.isWildcard()) {
                simpleNameCounts.merge(statement.simpleName(), 1, Integer::sum);
            }
        }

        List<JavaText.ImportStatement> removable = new ArrayList<>();
        for (JavaText.ImportStatement statement : imports) {
            if (statement.isStatic() || statement.isWildcard()) {
                continue;
            }
            String simpleName = statement.simpleName();
            if (simpleNameCounts.getOrDefault(simpleName, 0) > 1) {
                continue;
            }
            if (JavaText.usesIdentifier(body, simpleName)) {
                continue;
            }
            removable.add(statement);
        }
        if (removable.isEmpty()) {
            return Result.unchanged(source);
        }

        // Delete from the end so earlier offsets stay valid, and take the whole line with it.
        StringBuilder out = new StringBuilder(source);
        for (int i = removable.size() - 1; i >= 0; i--) {
            JavaText.ImportStatement statement = removable.get(i);
            int start = lineStart(out, statement.start());
            int end = lineEnd(out, statement.end());
            out.delete(start, end);
        }

        return new Result(out.toString(),
                List.of("removed " + removable.size() + " unused import(s)"),
                Map.of("removedImports", Integer.toString(removable.size())));
    }

    private static int lineStart(CharSequence text, int position) {
        int i = position;
        while (i > 0 && text.charAt(i - 1) != '\n') {
            i--;
        }
        return i;
    }

    private static int lineEnd(CharSequence text, int position) {
        int i = position;
        while (i < text.length() && text.charAt(i) != '\n') {
            i++;
        }
        return Math.min(text.length(), i + 1);
    }
}
