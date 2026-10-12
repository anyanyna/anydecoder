package com.anydecoder.core.gate;

import com.anydecoder.core.gate.signals.Multisets;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides whether a divergence is a compiler artefact or a real defect.
 *
 * <p>This is what turns an alarm into an explanation. The rule it follows is absolute:
 * <b>a divergence may only be excused when this class can point at the structural pattern that
 * produces it.</b> There is no fallback to "probably fine", no confidence threshold, and no
 * category that fires merely because the numbers are close. A difference nothing here
 * recognises stays a difference and reaches a human.
 *
 * <p>Every category below was measured on a corpus of 2451 classes whose original bytecode was
 * compiled by the Eclipse compiler and whose rebuilds came from javac.
 */
public final class BenignDivergenceClassifier {

    /** A named, evidenced reason a divergence does not indicate a defect. */
    public record Explanation(String category, String reason, List<String> signals) {
    }

    /**
     * @param explained    true when every HARD and SUSPECT divergence was accounted for
     * @param explanations the categories that applied
     * @param unexplained  divergences nothing here could account for
     */
    public record Result(
            boolean explained,
            List<Explanation> explanations,
            List<Divergence> unexplained) {
    }

    private static final Set<String> BOX_TYPES = Set.of(
            "java/lang/Boolean", "java/lang/Byte", "java/lang/Character", "java/lang/Short",
            "java/lang/Integer", "java/lang/Long", "java/lang/Float", "java/lang/Double");

    private BenignDivergenceClassifier() {
    }

    public static Result classify(ClassModel originalClass, ClassModel rebuiltClass,
                                  MethodModel original, MethodModel rebuilt,
                                  List<Divergence> divergences) {
        List<Divergence> blocking = divergences.stream()
                .filter(d -> d.severity() != Severity.ADVISORY)
                .toList();

        List<Explanation> explanations = new ArrayList<>();
        List<Divergence> unexplained = new ArrayList<>(blocking);

        tryExplainExceptionSegmentation(original, rebuilt, unexplained, explanations);
        tryExplainBooleanMaterialization(original, rebuilt, blocking, unexplained, explanations);
        tryExplainMissingIincOptimization(original, rebuilt, blocking, unexplained, explanations);
        tryExplainFieldStoreHoisting(original, rebuilt, blocking, unexplained, explanations);
        tryExplainBoxing(original, rebuilt, blocking, unexplained, explanations);
        tryExplainEnumInitialization(originalClass, rebuiltClass, original, blocking,
                unexplained, explanations);

        return new Result(unexplained.isEmpty(), explanations, unexplained);
    }

    // ------------------------------------------------------------------------------------------
    // Exception table: same protected code, different row segmentation.
    // ------------------------------------------------------------------------------------------

    private static void tryExplainExceptionSegmentation(
            MethodModel original, MethodModel rebuilt,
            List<Divergence> unexplained, List<Explanation> explanations) {

        Divergence target = find(unexplained, "excp");
        if (target == null) {
            return;
        }
        Set<String> types = new LinkedHashSet<>();
        original.exceptionTable().forEach(e -> types.add(canonical(e.type())));
        rebuilt.exceptionTable().forEach(e -> types.add(canonical(e.type())));

        for (String type : types) {
            if (!throwingCoverage(original, type).equals(throwingCoverage(rebuilt, type))) {
                // The protected instructions themselves differ, so this is not segmentation.
                return;
            }
        }
        unexplained.remove(target);
        explanations.add(new Explanation(
                "EXCEPTION_SEGMENT_SPLIT",
                "the exception table covers the same throwing instructions but splits them into"
                        + " different rows, which is how javac and ecj disagree about a protected"
                        + " range's boundaries",
                List.of("excp")));
    }

    /** Indices of the throwing instructions a given handler type actually protects. */
    private static Set<Integer> throwingCoverage(MethodModel method, String type) {
        Set<Integer> covered = new TreeSet<>();
        List<Insn> insns = method.instructions();
        for (ExceptionEntry entry : method.exceptionTable()) {
            if (entry.isSelfProtection() || !canonical(entry.type()).equals(type)) {
                continue;
            }
            for (int i = entry.start(); i < entry.end() && i < insns.size(); i++) {
                if (insns.get(i).canThrow()) {
                    covered.add(i);
                }
            }
        }
        return covered;
    }

    // ------------------------------------------------------------------------------------------
    // ecj materialises a boolean result instead of branching to it.
    // ------------------------------------------------------------------------------------------

    /**
     * Excuses a boolean result materialised into instructions rather than branched to.
     *
     * <p>One measured pattern produces two signals at once. Where javac emits
     * {@code ifeq L; iconst_0; goto M; L: iconst_1}, the older compiler here emits
     * {@code ifeq L; iconst_1; ireturn; L: iconst_0; ireturn} — an extra {@code ireturn}, an
     * extra {@code iconst_0} and an extra {@code iconst_1}, per boolean result.
     *
     * <p>So the body grows by that many instructions <em>and</em> pushes that many more constants.
     * Both have to be explained together or the class stays flagged for a difference that is one
     * artefact seen twice. The rule is stated as an equality rather than a threshold: the size
     * delta, the constant delta and the {@code ireturn} delta must all be the same number, and the
     * only constants that may differ are {@code 0} and {@code 1}.
     */
    private static void tryExplainBooleanMaterialization(
            MethodModel original, MethodModel rebuilt, List<Divergence> blocking,
            List<Divergence> unexplained, List<Explanation> explanations) {

        if (!onlySignals(blocking, "instrCount", "consts", "smallConsts")) {
            return;
        }
        int ireturnDelta = countOpcode(rebuilt, Opcodes.IRETURN) - countOpcode(original, Opcodes.IRETURN);
        int totalDelta = rebuilt.instructions().size() - original.instructions().size();
        if (ireturnDelta == 0 || ireturnDelta != totalDelta) {
            return;
        }
        // When the constants are also reported, only the ones a boolean materialisation
        // introduces may differ. When they are not reported there is nothing to check.
        boolean constantsReported = find(blocking, "consts") != null
                || find(blocking, "smallConsts") != null;
        if (constantsReported
                && !constantsDifferOnlyInBooleanMaterialization(original, rebuilt, ireturnDelta)) {
            return;
        }

        List<Divergence> explained = new ArrayList<>();
        for (Divergence divergence : unexplained) {
            if (divergence.signalId().equals("instrCount")
                    || divergence.signalId().equals("consts")
                    || divergence.signalId().equals("smallConsts")) {
                explained.add(divergence);
            }
        }
        if (explained.isEmpty()) {
            return;
        }
        unexplained.removeAll(explained);
        explanations.add(new Explanation(
                "ECJ_BOOLEAN_MATERIALIZATION",
                "the rebuild is " + Math.abs(totalDelta) + " instruction(s) "
                        + (totalDelta > 0 ? "larger" : "smaller") + " and pushes "
                        + Math.abs(ireturnDelta) + " more/fewer int constants, in step with "
                        + Math.abs(ireturnDelta) + " ireturn difference(s): the compiler that built"
                        + " the original materialises a boolean result into iconst/ireturn where"
                        + " javac branches straight to the constant",
                explained.stream().map(Divergence::signalId).distinct().toList()));
    }

    /**
     * Whether the two methods' constant multisets differ only by {@code int:0} and {@code int:1},
     * and by exactly {@code delta} of them.
     */
    private static boolean constantsDifferOnlyInBooleanMaterialization(
            MethodModel original, MethodModel rebuilt, int delta) {
        Map<String, Integer> left = constantCounts(original);
        Map<String, Integer> right = constantCounts(rebuilt);
        Set<String> keys = new java.util.LinkedHashSet<>(left.keySet());
        keys.addAll(right.keySet());

        int movedInts = 0;
        for (String key : keys) {
            int difference = right.getOrDefault(key, 0) - left.getOrDefault(key, 0);
            if (difference == 0) {
                continue;
            }
            if (key.equals("int:0") || key.equals("int:1")) {
                movedInts += difference;
                continue;
            }
            // Something else changed, so this is not the boolean pattern.
            return false;
        }
        return Math.abs(movedInts) == Math.abs(delta);
    }

    private static Map<String, Integer> constantCounts(MethodModel method) {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (Insn insn : method.instructions()) {
            switch (insn.mnemonic()) {
                case "ldc", "iconst", "lconst", "fconst", "dconst", "aconst_null", "push" ->
                        counts.merge(insn.operand(), 1, Integer::sum);
                default -> { }
            }
        }
        return counts;
    }

    // ------------------------------------------------------------------------------------------
    // ecj does not rewrite `x = x + 1` into iinc.
    // ------------------------------------------------------------------------------------------

    private static void tryExplainMissingIincOptimization(
            MethodModel original, MethodModel rebuilt, List<Divergence> blocking,
            List<Divergence> unexplained, List<Explanation> explanations) {

        if (!onlySignals(blocking, "instrCount")) {
            return;
        }
        int iincOriginal = countOpcode(original, Opcodes.IINC);
        int iincRebuilt = countOpcode(rebuilt, Opcodes.IINC);
        int iincDelta = iincRebuilt - iincOriginal;
        if (iincDelta <= 0) {
            return;
        }
        int totalDelta = Math.abs(rebuilt.instructions().size() - original.instructions().size());
        // Each increment javac folds into one iinc replaces a load, a push, an add and a store.
        if (totalDelta < 2 * iincDelta || totalDelta > 4 * iincDelta) {
            return;
        }
        Divergence target = find(unexplained, "instrCount");
        unexplained.remove(target);
        explanations.add(new Explanation(
                "ECJ_NO_IINC_OPTIMIZATION",
                "the rebuild has " + iincDelta + " more iinc instructions and roughly "
                        + totalDelta + " fewer instructions in total, which is ecj declining to"
                        + " fold increment-by-one sequences into a single iinc",
                List.of("instrCount")));
    }

    // ------------------------------------------------------------------------------------------
    // Every write stores the same value and nothing reads the field back.
    // ------------------------------------------------------------------------------------------

    private static void tryExplainFieldStoreHoisting(
            MethodModel original, MethodModel rebuilt, List<Divergence> blocking,
            List<Divergence> unexplained, List<Explanation> explanations) {

        if (!onlySignals(blocking, "fields")) {
            return;
        }
        Divergence target = find(unexplained, "fields");
        if (target == null) {
            return;
        }
        Map<String, Integer> originalWrites = fieldWrites(original);
        Map<String, Integer> rebuiltWrites = fieldWrites(rebuilt);

        Set<String> fields = new LinkedHashSet<>(originalWrites.keySet());
        fields.addAll(rebuiltWrites.keySet());
        for (String field : fields) {
            int a = originalWrites.getOrDefault(field, 0);
            int b = rebuiltWrites.getOrDefault(field, 0);
            if (a <= b) {
                // Only a reduction is characteristic of hoisting; anything else is unexplained.
                return;
            }
            if (!isNeverRead(original, field) || !isNeverRead(rebuilt, field)) {
                return;
            }
            if (distinctStoredValues(original, field) > 1) {
                return;
            }
        }
        unexplained.remove(target);
        explanations.add(new Explanation(
                "FIELD_STORE_HOISTING",
                "the original writes a field " + originalWrites.values() + " time(s) where the"
                        + " rebuild writes it " + rebuiltWrites.values() + " time(s), every write"
                        + " stores the same value, and the field is never read back -- so the"
                        + " surviving write is the only observable effect",
                List.of("fields")));
    }

    private static Map<String, Integer> fieldWrites(MethodModel method) {
        Map<String, Integer> writes = new java.util.LinkedHashMap<>();
        for (Insn insn : method.instructions()) {
            if (insn.opcode() == Opcodes.PUTFIELD || insn.opcode() == Opcodes.PUTSTATIC) {
                writes.merge(insn.operand(), 1, Integer::sum);
            }
        }
        return writes;
    }

    private static boolean isNeverRead(MethodModel method, String field) {
        return method.instructions().stream().noneMatch(i ->
                (i.opcode() == Opcodes.GETFIELD || i.opcode() == Opcodes.GETSTATIC)
                        && i.operand().equals(field));
    }

    /**
     * How many distinct values the method stores into a field, looking at the instruction
     * immediately preceding each store. Returns a large number when a store's value cannot be
     * attributed to a simple constant, which makes the caller treat it as unexplained.
     */
    private static int distinctStoredValues(MethodModel method, String field) {
        Set<String> values = new LinkedHashSet<>();
        List<Insn> insns = method.instructions();
        for (int i = 0; i < insns.size(); i++) {
            Insn insn = insns.get(i);
            if ((insn.opcode() != Opcodes.PUTFIELD && insn.opcode() != Opcodes.PUTSTATIC)
                    || !insn.operand().equals(field)) {
                continue;
            }
            String source = valueSource(insns, i);
            if (source == null) {
                return Integer.MAX_VALUE;
            }
            values.add(source);
        }
        return values.size();
    }

    /** The nearest preceding constant-producing instruction, or null if there is none nearby. */
    private static String valueSource(List<Insn> insns, int storeIndex) {
        for (int i = storeIndex - 1; i >= 0 && i >= storeIndex - 4; i--) {
            Insn candidate = insns.get(i);
            String m = candidate.mnemonic();
            if ("ldc".equals(m) || "push".equals(m) || "aconst_null".equals(m)
                    || m.startsWith("iconst") || m.startsWith("lconst") || m.startsWith("fconst")
                    || m.startsWith("dconst")) {
                return candidate.render();
            }
            if (!"dup".equals(m) && !"aload".equals(m) && !"aload_0".equals(m)) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Enum constants are built differently by the two compilers.
    // ------------------------------------------------------------------------------------------

    /**
     * Excuses a difference confined to an enum's static initialiser.
     *
     * <p>ecj and javac construct an enum's constant table quite differently -- ecj writes each
     * constant and the backing array inline, javac from JDK 15 on routes through a synthetic
     * {@code $values()} helper -- so the initialiser legitimately differs by more than a quarter
     * of its instructions.
     *
     * <p>The check that keeps this honest is on the fields: the class must declare the same set
     * of them. A missing enum constant would show up there, and excusing a difference in
     * {@code <clinit>} without that guard would let a lost constant pass as a compiler artefact.
     */
    private static void tryExplainEnumInitialization(
            ClassModel originalClass, ClassModel rebuiltClass,
            MethodModel original, List<Divergence> blocking,
            List<Divergence> unexplained, List<Explanation> explanations) {

        if (!original.name().equals("<clinit>") || !isEnum(originalClass)) {
            return;
        }
        if (!onlySignals(blocking, "invokes", "fields", "instrCount")) {
            return;
        }
        java.util.Set<String> declared = new java.util.TreeSet<>();
        originalClass.fields().forEach(f -> declared.add(f.name()));
        java.util.Set<String> rebuiltDeclared = new java.util.TreeSet<>();
        rebuiltClass.fields().forEach(f -> rebuiltDeclared.add(f.name()));

        if (!declared.equals(rebuiltDeclared)) {
            // A constant was added or removed; the initialiser difference is not the only one.
            return;
        }

        List<Divergence> toRemove = new ArrayList<>();
        for (Divergence divergence : unexplained) {
            if (divergence.signalId().equals("invokes")
                    || divergence.signalId().equals("fields")
                    || divergence.signalId().equals("instrCount")) {
                toRemove.add(divergence);
            }
        }
        if (toRemove.isEmpty()) {
            return;
        }
        unexplained.removeAll(toRemove);
        explanations.add(new Explanation(
                "ENUM_CLINIT_CODEGEN",
                "the difference is confined to the enum's static initialiser, and both versions"
                        + " declare the same fields -- so the constants present are the same and"
                        + " only the code that builds the constant table differs",
                toRemove.stream().map(Divergence::signalId).distinct().toList()));
    }

    private static boolean isEnum(ClassModel model) {
        return (model.access() & Opcodes.ACC_ENUM) != 0;
    }

    // ------------------------------------------------------------------------------------------
    // Autoboxing introduced or removed.
    // ------------------------------------------------------------------------------------------

    private static void tryExplainBoxing(
            MethodModel original, MethodModel rebuilt, List<Divergence> blocking,
            List<Divergence> unexplained, List<Explanation> explanations) {

        boolean hasInvokes = find(blocking, "invokes") != null;
        if (!hasInvokes || !onlySignals(blocking, "invokes", "instrCount")) {
            return;
        }
        Map<String, Integer> a = Multisets.counts(boxingCalls(original), s -> s);
        Map<String, Integer> b = Multisets.counts(boxingCalls(rebuilt), s -> s);
        if (a.equals(b)) {
            return;
        }
        // Every non-boxing call must still line up, otherwise this is not a boxing artefact.
        Map<String, Integer> aAll = Multisets.counts(nonBoxingCalls(original), s -> s);
        Map<String, Integer> bAll = Multisets.counts(nonBoxingCalls(rebuilt), s -> s);
        if (!aAll.equals(bAll)) {
            return;
        }

        int boxingOriginal = boxingCalls(original).size();
        int boxingRebuilt = boxingCalls(rebuilt).size();
        int boxingDelta = Math.abs(boxingRebuilt - boxingOriginal);
        if (boxingDelta == 0) {
            return;
        }
        // A box or unbox is one instruction, so the body can only have grown or shrunk in step
        // with the boxing traffic. Without this bound the category would excuse any size change
        // in a method that happened to differ in one box call -- which is the kind of
        // unearned exemption the whole classifier is meant to avoid.
        int instructionDelta = Math.abs(
                rebuilt.instructions().size() - original.instructions().size());
        if (instructionDelta > 2 * boxingDelta + 2) {
            return;
        }

        Divergence target = find(unexplained, "invokes");
        unexplained.remove(target);
        Divergence count = find(unexplained, "instrCount");
        if (count != null) {
            unexplained.remove(count);
        }
        explanations.add(new Explanation(
                "BOXING_MATERIALIZATION",
                "the only call difference is " + boxingDelta + " box/unbox call(s), and the"
                        + " body size moved by " + instructionDelta + " instruction(s), which is"
                        + " what adding or removing a box costs; every other call still lines up",
                List.of("invokes")));
    }

    private static List<String> boxingCalls(MethodModel method) {
        List<String> out = new ArrayList<>();
        for (Insn insn : method.instructions()) {
            if (isBoxingCall(insn.operand())) {
                out.add(insn.operand());
            }
        }
        return out;
    }

    private static List<String> nonBoxingCalls(MethodModel method) {
        List<String> out = new ArrayList<>();
        for (Insn insn : method.instructions()) {
            if (insn.opcode() < Opcodes.INVOKEVIRTUAL || insn.opcode() > Opcodes.INVOKEDYNAMIC) {
                continue;
            }
            if (isBoxingCall(insn.operand()) || isConcatCall(insn.operand())) {
                continue;
            }
            out.add(insn.operand());
        }
        return out;
    }

    /**
     * Whether a resolved callee is a boxing or unboxing call.
     *
     * <p>The name is matched exactly rather than by substring. {@code contains("Value(")} would
     * also match an unrelated {@code setValue(...)}; the owner check makes that unlikely rather
     * than impossible, and an exemption granted on a near-miss is exactly what this classifier
     * must not do. {@code valueOf} is the boxing direction; {@code intValue}, {@code charValue}
     * and the rest are unboxing.
     */
    private static boolean isBoxingCall(String callee) {
        int dot = callee.indexOf('.');
        if (dot < 0 || !BOX_TYPES.contains(callee.substring(0, dot))) {
            return false;
        }
        String member = callee.substring(dot + 1);
        int paren = member.indexOf('(');
        String name = paren < 0 ? member : member.substring(0, paren);
        if (name.equals("valueOf")) {
            return true;
        }
        return switch (name) {
            case "intValue", "longValue", "doubleValue", "floatValue",
                 "byteValue", "shortValue", "booleanValue", "charValue" -> true;
            default -> false;
        };
    }

    private static boolean isConcatCall(String callee) {
        return callee.startsWith("java/lang/StringBuilder.")
                || callee.startsWith("java/lang/StringBuffer.");
    }

    // ------------------------------------------------------------------------------------------

    private static String canonical(String type) {
        if (type == null || type.equals("java/lang/Throwable")) {
            return ExceptionEntry.ANY;
        }
        return type;
    }

    private static Divergence find(List<Divergence> divergences, String signalId) {
        return divergences.stream().filter(d -> d.signalId().equals(signalId)).findFirst().orElse(null);
    }

    /** True when every blocking divergence comes from one of the given signals. */
    private static boolean onlySignals(List<Divergence> blocking, String... signalIds) {
        Set<String> allowed = Set.of(signalIds);
        return !blocking.isEmpty() && blocking.stream().allMatch(d -> allowed.contains(d.signalId()));
    }

    private static int countOpcode(MethodModel method, int opcode) {
        return (int) method.instructions().stream().filter(i -> i.opcode() == opcode).count();
    }

    private static String sign(int n) {
        return (n >= 0 ? "+" : "") + n;
    }
}
