package com.anydecoder.core.gate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reduces an exception table to a form two different compilers can agree on.
 *
 * <p>The raw tables are never equal, and the differences are not defects. Measured on a real
 * method, ecj emits
 * <pre>
 *   (0,13) (14,61) (62,65)  -&gt; handler
 * </pre>
 * where javac emits
 * <pre>
 *   (0,13) (14,64)          -&gt; handler
 * </pre>
 * The only instructions ecj leaves out are an {@code ireturn} at 61 and a {@code goto} at 65,
 * neither of which can throw. The tables are semantically identical. Comparing entry lists
 * reported 39 phantom defects on one corpus and hid the 3 real ones among them.
 *
 * <p>So: drop entries covering no throwing instruction, then count -- <em>per handler type</em> --
 * how many disjoint groups of protected code remain. The result is a multiset of
 * {@code (type, groupCount)} that still catches a dropped, added or changed handler, which is
 * what actually matters.
 *
 * <p>Grouping must be per type rather than by scanning in index order. A nested try lists its
 * handler alongside the outer handler's, both starting at the same index, so siblings of one
 * type are never adjacent in the table and would never merge.
 */
public final class ExceptionTableNormalizer {

    private ExceptionTableNormalizer() {
    }

    /** A handler type together with the number of disjoint protected groups that feed it. */
    public record TypeGroup(String type, int groupCount) implements Comparable<TypeGroup> {
        @Override
        public int compareTo(TypeGroup other) {
            int byType = type.compareTo(other.type);
            return byType != 0 ? byType : Integer.compare(groupCount, other.groupCount);
        }
    }

    public static List<TypeGroup> normalize(MethodModel method) {
        return normalize(method.exceptionTable(), method.instructions());
    }

    static List<TypeGroup> normalize(List<ExceptionEntry> table, List<Insn> instructions) {
        List<Integer> throwing = new ArrayList<>();
        for (int i = 0; i < instructions.size(); i++) {
            if (instructions.get(i).canThrow()) {
                throwing.add(i);
            }
        }

        Map<String, List<int[]>> byType = new LinkedHashMap<>();
        for (ExceptionEntry entry : table) {
            if (entry.isSelfProtection()) {
                // javac emits a self-protection row for finally handlers so an exception
                // thrown by the handler itself re-enters it. ecj never emits these, and
                // their presence says nothing about the source.
                continue;
            }
            if (!coversAnyThrowing(entry, throwing)) {
                continue;
            }
            byType.computeIfAbsent(canonicalType(entry.type()), k -> new ArrayList<>())
                    .add(new int[]{entry.start(), entry.end()});
        }

        List<TypeGroup> result = new ArrayList<>();
        for (Map.Entry<String, List<int[]>> e : byType.entrySet()) {
            List<int[]> ranges = e.getValue();
            ranges.sort(Comparator.comparingInt(r -> r[0]));
            int groups = 0;
            int lastEnd = -1;
            boolean first = true;
            for (int[] range : ranges) {
                if (first || hasThrowingBetween(throwing, lastEnd, range[0])) {
                    groups++;
                }
                lastEnd = first ? range[1] : Math.max(lastEnd, range[1]);
                first = false;
            }
            result.add(new TypeGroup(e.getKey(), groups));
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    private static boolean coversAnyThrowing(ExceptionEntry entry, List<Integer> throwing) {
        for (int offset : throwing) {
            if (offset >= entry.start() && offset < entry.end()) {
                return true;
            }
            if (offset >= entry.end()) {
                break;
            }
        }
        return false;
    }

    private static boolean hasThrowingBetween(List<Integer> throwing, int from, int to) {
        for (int offset : throwing) {
            if (offset >= from && offset < to) {
                return true;
            }
            if (offset >= to) {
                break;
            }
        }
        return false;
    }

    /**
     * An {@code any} handler and an explicit {@code catch (Throwable)} are the same thing;
     * ecj writes the former, javac can write either.
     */
    private static String canonicalType(String type) {
        if (type == null || type.equals("java/lang/Throwable")) {
            return ExceptionEntry.ANY;
        }
        return type;
    }
}
