package com.anydecoder.core.difftest;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Values to call the two versions of a method with.
 *
 * <p>The corpus is deliberately small and full of edges. A decompilation defect is far more
 * likely to show up at {@code Integer.MAX_VALUE}, {@code -1}, or the empty string than at 42,
 * and running ten thousand random integers would spend almost all its time in the middle of the
 * range where every implementation agrees.
 */
public final class InputCorpus {

    private static final List<Object> INTEGRAL = List.of(0, 1, -1, 2, 7, 10, 100,
            Integer.MAX_VALUE, Integer.MIN_VALUE, -100);
    private static final List<Object> CHARS = List.of('\0', 'a', 'A', 'z', '0', ' ',
            Character.MAX_VALUE);
    private static final List<Object> STRINGS = List.of("", "a", "abc", "0", " ",
            "abcdefghij", "中文", "null");

    private final Random random;

    public InputCorpus(long seed) {
        this.random = new Random(seed);
    }

    /** Values worth trying for a parameter of the given type. */
    public List<Object> valuesFor(Class<?> type) {
        if (type == int.class || type == Integer.class) {
            return INTEGRAL;
        }
        if (type == long.class || type == Long.class) {
            List<Object> values = new ArrayList<>();
            for (Object v : INTEGRAL) {
                values.add(((Integer) v).longValue());
            }
            values.add(Long.MAX_VALUE);
            values.add(Long.MIN_VALUE);
            return values;
        }
        if (type == short.class || type == Short.class) {
            return List.of((short) 0, (short) 1, (short) -1, Short.MAX_VALUE, Short.MIN_VALUE);
        }
        if (type == byte.class || type == Byte.class) {
            return List.of((byte) 0, (byte) 1, (byte) -1, Byte.MAX_VALUE, Byte.MIN_VALUE);
        }
        if (type == char.class || type == Character.class) {
            return CHARS;
        }
        if (type == boolean.class || type == Boolean.class) {
            return List.of(Boolean.TRUE, Boolean.FALSE);
        }
        if (type == float.class || type == Float.class) {
            return List.of(0.0f, 1.0f, -1.0f, Float.MAX_VALUE, Float.MIN_VALUE, Float.NaN);
        }
        if (type == double.class || type == Double.class) {
            return List.of(0.0, 1.0, -1.0, Double.MAX_VALUE, Double.MIN_VALUE, Double.NaN);
        }
        if (type == String.class) {
            return STRINGS;
        }
        return List.of();
    }

    /**
     * Whether a parameter type can be driven at all. Reference types other than String and the
     * boxed primitives are skipped: constructing a meaningful instance of an arbitrary type is
     * not something this can do reliably, and guessing would produce divergences that are about
     * the guess rather than about the code.
     */
    public boolean canDrive(Class<?> type) {
        return !valuesFor(type).isEmpty();
    }

    /** Pick one of the candidate values for a parameter, varying with {@code index}. */
    public Object pick(Class<?> type, int index) {
        List<Object> values = valuesFor(type);
        if (values.isEmpty()) {
            return null;
        }
        // Walk the corpus for the first few combinations so the edges are always hit, then
        // start sampling so that a long parameter list still gets varied inputs.
        if (index < values.size()) {
            return values.get(index);
        }
        return values.get(random.nextInt(values.size()));
    }
}
