package com.anydecoder.core.gate.signals;

import com.anydecoder.core.gate.Divergence;
import com.anydecoder.core.gate.HierarchyIndex;
import com.anydecoder.core.gate.Severity;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares resolved member references in a way that survives how a source-level type was written.
 *
 * <h2>What has to be tolerated, and why</h2>
 *
 * <p>An entry is {@code owner.name+descriptor}. Three things about it vary between two compilations
 * of the same source without anything about the program changing, and each was measured on real
 * output:
 *
 * <ul>
 *   <li><b>A narrowed receiver.</b> Where the source said {@code List items = new ArrayList<>();},
 *       a decompiler writes {@code ArrayList items = ...}, so the call becomes
 *       {@code invokevirtual ArrayList.add} instead of {@code invokeinterface List.add}. Same
 *       member, same descriptor, related owners.</li>
 *   <li><b>A covariant override.</b> {@code CloseableHttpClient.execute} returns
 *       {@code CloseableHttpResponse} where {@code HttpClient.execute} returns
 *       {@code HttpResponse}. Reaching the call through the concrete type names the override,
 *       and at runtime it is the same method.</li>
 *   <li><b>Repetition count.</b> A compiler may duplicate an operation into each arm of a
 *       conditional; this is where the count differs but the set does not.</li>
 * </ul>
 *
 * <p>Taking owners literally reported 1325 findings on source that nine days of manual bytecode
 * examination had verified as faithful. Taking return types literally added another 57.
 *
 * <h2>What is not tolerated</h2>
 *
 * <p>A different name or parameter list is a different method, always. An owner with no related
 * counterpart is a call on something else. Those stay differences, and a count difference is
 * reported at whatever severity the calling signal chooses for it.
 */
public final class ResolvedTargets {

    private ResolvedTargets() {
    }

    /**
     * Whether two lists of resolved references correspond exactly, allowing for a receiver or a
     * return type reached through a related class.
     *
     * <p>For a caller that has already set some differences aside — the boxing classifier, which
     * removes the box and unbox calls and then asks whether everything else still lines up. It has
     * to ask that question through the same hierarchy reasoning as the signal does; comparing the
     * raw strings would report a narrowed receiver as a difference and make the classifier give
     * up on a class it could have accounted for.
     */
    public static boolean match(List<String> original, List<String> rebuilt, HierarchyIndex hierarchy) {
        if (Objects.equals(sorted(original), sorted(rebuilt))) {
            return true;
        }
        Map<String, List<Target>> left = bySignature(original);
        Map<String, List<Target>> right = bySignature(rebuilt);
        Set<String> allSignatures = new LinkedHashSet<>(left.keySet());
        allSignatures.addAll(right.keySet());
        for (String signature : allSignatures) {
            List<Target> a = new ArrayList<>(left.getOrDefault(signature, List.of()));
            List<Target> b = new ArrayList<>(right.getOrDefault(signature, List.of()));
            pairRelated(a, b, hierarchy);
            if (!a.isEmpty() || !b.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * One resolved reference, split into the parts compared separately.
     *
     * <p>A method is {@code owner.name(params)return}; a field is {@code owner.name:type}.
     * Keeping the return type out of {@link #signature()} is what lets a covariant override line
     * up. It is compared separately, against the hierarchy, rather than ignored.
     */
    private record Target(String owner, String name, String descriptor) {

        static Target parse(String resolved) {
            int dot = resolved.indexOf('.');
            if (dot < 0) {
                return new Target("", resolved, "");
            }
            String owner = resolved.substring(0, dot);
            String rest = resolved.substring(dot + 1);
            int paren = rest.indexOf('(');
            if (paren >= 0) {
                return new Target(owner, rest.substring(0, paren), rest.substring(paren));
            }
            int colon = rest.lastIndexOf(':');
            return colon < 0
                    ? new Target(owner, rest, "")
                    : new Target(owner, rest.substring(0, colon), rest.substring(colon + 1));
        }

        boolean isMethod() {
            return descriptor.startsWith("(");
        }

        /** The part that must match exactly: a different name or parameter list is another method. */
        String signature() {
            if (!isMethod()) {
                return name + ":" + descriptor;
            }
            int close = descriptor.indexOf(')');
            return name + (close < 0 ? descriptor : descriptor.substring(0, close + 1));
        }

        /** The return type for a method, the field type for a field. */
        String resultType() {
            if (!isMethod()) {
                return descriptor;
            }
            int close = descriptor.indexOf(')');
            return close < 0 ? descriptor : descriptor.substring(close + 1);
        }

        /** The owner and the return type, which is what has to pair up across a hierarchy. */
        String pairingKey() {
            return owner + "|" + resultType();
        }

        String render() {
            return owner + "." + signature() + (isMethod()
                    ? descriptor.substring(descriptor.indexOf(')') + 1)
                    : "");
        }
    }

    /**
     * @param countedDiffer what to report when both sides reference exactly the same members,
     *                      through related owners, and differ only in repetition count. A
     *                      different <em>member</em> is always a hard difference; a different
     *                      <em>count</em> of the same member can come from the compiler
     *                      duplicating an operation into each arm of a conditional, so each
     *                      signal picks its own severity.
     */
    static Optional<Divergence> compare(String signalId, String memberName, Severity severity,
                                        List<String> original, List<String> rebuilt,
                                        HierarchyIndex hierarchy, Severity countedDiffer) {

        if (Objects.equals(sorted(original), sorted(rebuilt))) {
            return Optional.empty();
        }

        List<String> unpairedOriginal = new ArrayList<>();
        List<String> unpairedRebuilt = new ArrayList<>();

        Map<String, List<Target>> left = bySignature(original);
        Map<String, List<Target>> right = bySignature(rebuilt);

        Set<String> allSignatures = new LinkedHashSet<>(left.keySet());
        allSignatures.addAll(right.keySet());
        for (String signature : allSignatures) {
            List<Target> a = new ArrayList<>(left.getOrDefault(signature, List.of()));
            List<Target> b = new ArrayList<>(right.getOrDefault(signature, List.of()));
            pairRelated(a, b, hierarchy);
            a.forEach(t -> unpairedOriginal.add(t.render()));
            b.forEach(t -> unpairedRebuilt.add(t.render()));
        }

        if (unpairedOriginal.isEmpty() && unpairedRebuilt.isEmpty()) {
            // Every reference on one side found a counterpart on the other. Nothing differs.
            return Optional.empty();
        }

        // Whether the same members are reached and only the repetition counts differ. This is
        // tested on the *sets* rather than on what pairing left over, because a leftover is
        // itself the evidence of a count difference and so cannot also be the test for whether
        // the difference is one.
        boolean onlyCountsDiffer = setsMatch(original, rebuilt, hierarchy);

        Severity reported = onlyCountsDiffer ? countedDiffer : severity;
        String detail = onlyCountsDiffer
                ? "the same members are referenced, a different number of times: "
                    + describeCountDifference(original, rebuilt)
                : "the set of members differs: only in original " + summarise(unpairedOriginal)
                    + "; only in rebuild " + summarise(unpairedRebuilt);

        return Optional.of(Divergence
                .of(signalId, memberName, reported, detail)
                .withEvidence("original", renderCounted(original))
                .withEvidence("rebuilt", renderCounted(rebuilt))
                .withEvidence("unpairedOriginal", String.join(", ", unpairedOriginal))
                .withEvidence("unpairedRebuilt", String.join(", ", unpairedRebuilt)));
    }

    /**
     * Whether both sides reach exactly the same members, through owners and return types that are
     * pairwise related -- ignoring how many times each is referenced.
     */
    private static boolean setsMatch(List<String> original, List<String> rebuilt,
                                     HierarchyIndex hierarchy) {
        Map<String, Set<Target>> left = distinctBySignature(original);
        Map<String, Set<Target>> right = distinctBySignature(rebuilt);
        if (!left.keySet().equals(right.keySet())) {
            return false;
        }
        for (String signature : left.keySet()) {
            List<Target> a = new ArrayList<>(left.get(signature));
            List<Target> b = new ArrayList<>(right.get(signature));
            pairRelated(a, b, hierarchy);
            if (!a.isEmpty() || !b.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Set<Target>> distinctBySignature(List<String> resolved) {
        Map<String, Set<Target>> result = new LinkedHashMap<>();
        for (String entry : resolved) {
            Target target = Target.parse(entry);
            result.computeIfAbsent(target.signature(), k -> new LinkedHashSet<>()).add(target);
        }
        return result;
    }

    /**
     * Pairs entries across the two sides, each pairing requiring the owners to be related and the
     * return types to be related. Both matter: the first covers a narrowed receiver, the second a
     * covariant override.
     */
    private static void pairRelated(List<Target> left, List<Target> right, HierarchyIndex hierarchy) {
        for (Iterator<Target> l = left.iterator(); l.hasNext(); ) {
            Target candidate = l.next();
            for (Iterator<Target> r = right.iterator(); r.hasNext(); ) {
                Target other = r.next();
                if (hierarchy.isRelated(candidate.owner(), other.owner())
                        && hierarchy.isRelated(candidate.resultType(), other.resultType())) {
                    r.remove();
                    l.remove();
                    break;
                }
            }
        }
    }

    private static Map<String, List<Target>> bySignature(List<String> resolved) {
        Map<String, List<Target>> bySignature = new LinkedHashMap<>();
        for (String entry : resolved) {
            Target target = Target.parse(entry);
            bySignature.computeIfAbsent(target.signature(), k -> new ArrayList<>()).add(target);
        }
        return bySignature;
    }

    private static String describeCountDifference(List<String> original, List<String> rebuilt) {
        Map<String, Integer> left = count(original);
        Map<String, Integer> right = count(rebuilt);
        Set<String> keys = new TreeSet<>(left.keySet());
        keys.addAll(right.keySet());
        List<String> differing = new ArrayList<>();
        for (String key : keys) {
            int a = left.getOrDefault(key, 0);
            int b = right.getOrDefault(key, 0);
            if (a != b) {
                differing.add(shorten(key) + " " + a + "->" + b);
            }
        }
        return differing.isEmpty() ? "(none)" : String.join(", ", differing);
    }

    private static Map<String, Integer> count(List<String> values) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String value : values) {
            counts.merge(value, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * The full multiset, as {@code entry xN} pairs, with the owner's package stripped.
     *
     * <p>Counting rather than listing is what makes this usable: a method can reference the same
     * field twenty times, and twenty identical lines say less than one line saying {@code x20}.
     */
    private static String renderCounted(List<String> resolved) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String entry : resolved) {
            counts.merge(entry, 1, Integer::sum);
        }
        List<String> rendered = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> rendered.add(e.getValue() > 1
                        ? shorten(e.getKey()) + " x" + e.getValue()
                        : shorten(e.getKey())));
        if (rendered.size() > 60) {
            List<String> head = rendered.subList(0, 60);
            return String.join(", ", head) + " (+" + (rendered.size() - 60) + " more distinct)";
        }
        return String.join(", ", rendered);
    }

    private static String shorten(String resolved) {
        int dot = resolved.indexOf('.');
        if (dot < 0) {
            return resolved;
        }
        String owner = resolved.substring(0, dot);
        int slash = owner.lastIndexOf('/');
        return (slash < 0 ? owner : owner.substring(slash + 1)) + resolved.substring(dot);
    }

    private static List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        java.util.Collections.sort(copy);
        return copy;
    }

    private static String summarise(List<String> values) {
        if (values.isEmpty()) {
            return "(none)";
        }
        List<String> shown = values.subList(0, Math.min(5, values.size()));
        String text = String.join(", ", shown);
        return values.size() > shown.size() ? text + " (+" + (values.size() - shown.size()) + ")" : text;
    }
}
