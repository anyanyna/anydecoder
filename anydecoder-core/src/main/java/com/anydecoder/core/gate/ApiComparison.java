package com.anydecoder.core.gate;

import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Compares the members a class declares.
 *
 * <p>Three things are stripped before comparing, each for a measured reason:
 *
 * <ul>
 *   <li><b>Synthetic bridge methods.</b> ecj names the accessors it generates for inner-class
 *       access {@code access$0}, {@code access$1}; javac names them {@code access$000},
 *       {@code access$100}. Same role, different spelling. They are excluded rather than
 *       renamed because the access they perform is already compared inside the methods that
 *       use them.</li>
 *   <li><b>Member order.</b> javap and the class file list members in storage order, and the two
 *       compilers disagree -- typically about where the constructor sits. Comparing ordered
 *       lists flagged a class as an API difference when the member <em>sets</em> were identical.
 *       Comparing multisets is the fix. Field initialisation order is a real, semantic thing,
 *       but it lives in the {@code <clinit>} body, which the method signals compare.</li>
 *   <li><b>Synthetic and bridge access flags.</b> Whether the compiler marked a member
 *       synthetic is its own bookkeeping, not part of the declared API.</li>
 * </ul>
 */
public final class ApiComparison {

    private static final int METHOD_MASK = Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE;
    private static final int FIELD_MASK = Opcodes.ACC_SYNTHETIC;

    private ApiComparison() {
    }

    public static Optional<Divergence> compare(ClassModel original, ClassModel rebuilt) {
        Map<String, Integer> a = counts(members(original));
        Map<String, Integer> b = counts(members(rebuilt));
        if (a.equals(b)) {
            return Optional.empty();
        }
        return Optional.of(Divergence
                .of("api", original.name(), Severity.HARD,
                        "declared members differ: " + describe(a, b))
                .withEvidence("original", a.keySet().stream().sorted().toList().toString())
                .withEvidence("rebuilt", b.keySet().stream().sorted().toList().toString()));
    }

    /**
     * The class-level access flags that carry source-level meaning.
     *
     * <p>{@code ACC_FINAL} is masked off for anonymous classes, which ecj marks final and javac
     * does not. The flag cannot be written on an anonymous class and nothing can name one to
     * extend it, so it is inert -- and leaving it in reported every anonymous class in the corpus
     * as an API difference.
     */
    private static int classFlags(ClassModel model) {
        int access = model.access();
        if (isAnonymous(model.name())) {
            access &= ~Opcodes.ACC_FINAL;
        }
        return access;
    }

    /** {@code Outer$1}, {@code Outer$1$2} and the like: classes with no source-level name. */
    private static boolean isAnonymous(String internalName) {
        int dollar = internalName.lastIndexOf('$');
        if (dollar < 0 || dollar == internalName.length() - 1) {
            return false;
        }
        String tail = internalName.substring(dollar + 1);
        return tail.chars().allMatch(Character::isDigit);
    }

    private static List<String> members(ClassModel model) {
        List<String> out = new ArrayList<>();
        out.add("class flags=0x" + Integer.toHexString(classFlags(model))
                + " extends " + model.superName()
                + " implements " + model.interfaces().stream().sorted().toList());
        for (FieldModel f : model.fields()) {
            out.add("field " + f.name() + f.descriptor() + " flags=0x"
                    + Integer.toHexString(f.access() & ~FIELD_MASK));
        }
        for (MethodModel m : model.methods()) {
            if ((m.access() & METHOD_MASK) != 0 || isSyntheticAccessor(m.name())) {
                continue;
            }
            out.add("method " + m.name() + m.descriptor() + " flags=0x"
                    + Integer.toHexString(m.access() & ~METHOD_MASK));
        }
        return out;
    }

    private static Map<String, Integer> counts(List<String> items) {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String item : items) {
            counts.merge(item, 1, Integer::sum);
        }
        return counts;
    }

    private static String describe(Map<String, Integer> a, Map<String, Integer> b) {
        List<String> onlyA = a.keySet().stream().filter(k -> !b.containsKey(k)).sorted().toList();
        List<String> onlyB = b.keySet().stream().filter(k -> !a.containsKey(k)).sorted().toList();
        StringBuilder sb = new StringBuilder();
        if (!onlyA.isEmpty()) {
            sb.append("only in original: ").append(onlyA);
        }
        if (!onlyB.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append("only in rebuild: ").append(onlyB);
        }
        return sb.toString();
    }

    /**
     * True for the compiler-generated inner-class accessors whose names differ per compiler.
     *
     * <p>Matched by <b>name</b>, not only by the {@code ACC_SYNTHETIC} flag. javac does not always
     * set that flag on them: a real class in the validation corpus has {@code access$302} in the
     * rebuild carrying only {@code ACC_STATIC}, so a flag-based filter kept it and reported it as
     * a method the original does not have — when the original's equivalent was filtered out
     * because <em>its</em> compiler did set the flag. The asymmetry, not the accessor, was the
     * finding.
     */
    public static boolean isSyntheticAccessor(String methodName) {
        return methodName.startsWith("access$") && methodName.length() > 7
                && methodName.substring(7).chars().allMatch(Character::isDigit);
    }
}
