package com.anydecoder.core.gate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Divergences that have been investigated and accepted, loaded from a file.
 *
 * <h2>The file</h2>
 *
 * <p>A Markdown table, so it reads as documentation and is parsed as data:
 *
 * <pre>
 * | class | signal | reason | evidence |
 * |---|---|---|---|
 * | com/example/Foo | excp | local auth added after recovery | commit 03433271 |
 * </pre>
 *
 * <p>{@code signal} may list several, comma separated, or {@code *} for every signal on the class.
 * Both forms of class name are accepted, {@code com/example/Foo} and {@code com.example.Foo}.
 *
 * <h2>What it refuses</h2>
 *
 * <p>An entry with no reason or no evidence is <b>rejected</b>, not applied, and reported. A
 * waiver list is only worth having if an entry means somebody checked; an entry that means
 * somebody gave up is worse than no entry at all, because it makes the two indistinguishable.
 *
 * <p>A waiver that matches nothing is <b>stale</b> — the difference it covered is gone, so the
 * entry should be deleted. Reporting that is how the list stays honest as the tree improves.
 *
 * <h2>Why it exists</h2>
 *
 * <p>Some divergences are correct and permanent. Source that was deliberately changed after
 * recovery differs from the original by design; a recorded decision is the only honest thing the
 * gate can do with that, short of reporting it forever. The alternative — a gate that keeps
 * naming classes somebody has already cleared — trains its readers to ignore it.
 */
public final class WaiverRegistry {

    private static final String HEADER_MARKER = "class";
    private static final String SEPARATOR = "---";

    private final List<Waiver> waivers;
    private final List<Rejected> rejected;
    private final Set<String> used = ConcurrentHashMap.newKeySet();

    /** An entry that could not be applied, and why. */
    public record Rejected(String className, String reason, String source) {
    }

    private WaiverRegistry(List<Waiver> waivers, List<Rejected> rejected) {
        this.waivers = List.copyOf(waivers);
        this.rejected = List.copyOf(rejected);
    }

    public static WaiverRegistry empty() {
        return new WaiverRegistry(List.of(), List.of());
    }

    public static WaiverRegistry load(Path file) throws IOException {
        if (!Files.isReadable(file)) {
            throw new IOException("waiver file not readable: " + file);
        }
        return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    static WaiverRegistry parse(List<String> lines) {
        List<Waiver> parsed = new ArrayList<>();
        List<Rejected> bad = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (!line.startsWith("|")) {
                continue;
            }
            List<String> cells = cells(line);
            if (cells.size() < 2) {
                continue;
            }
            String first = cells.get(0).toLowerCase(java.util.Locale.ROOT);
            if (first.equals(HEADER_MARKER) || first.startsWith(SEPARATOR)) {
                continue;   // the table's own header and rule
            }
            parseRow(cells, "line " + (i + 1), parsed, bad);
        }
        return new WaiverRegistry(parsed, bad);
    }

    private static void parseRow(List<String> cells, String source,
                                 List<Waiver> parsed, List<Rejected> bad) {
        String className = normalise(cells.get(0));
        if (className.isEmpty()) {
            return;
        }
        Set<String> signals = new LinkedHashSet<>();
        for (String signal : cells.get(1).split(",")) {
            String trimmed = signal.trim();
            if (!trimmed.isEmpty()) {
                signals.add(trimmed);
            }
        }
        String reason = cells.size() > 2 ? cells.get(2).trim() : "";
        String evidence = cells.size() > 3 ? String.join(" | ", cells.subList(3, cells.size())).trim() : "";

        if (signals.isEmpty()) {
            bad.add(new Rejected(className, "no signal named", source));
            return;
        }
        if (reason.isEmpty()) {
            bad.add(new Rejected(className, "no reason given", source));
            return;
        }
        if (evidence.isEmpty()) {
            bad.add(new Rejected(className, "no evidence given", source));
            return;
        }
        parsed.add(new Waiver(className, signals, reason, evidence, source));
    }

    /** A table row's cells, with the leading and trailing empties removed. */
    private static List<String> cells(String line) {
        String body = line;
        if (body.startsWith("|")) {
            body = body.substring(1);
        }
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : body.split("\\|", -1)) {
            cells.add(cell.trim());
        }
        return cells;
    }

    /** Accepts {@code com.example.Foo} as well as {@code com/example/Foo}. */
    private static String normalise(String name) {
        String trimmed = name.trim().replace('`', ' ').trim();
        return trimmed.replace('.', '/');
    }

    // ------------------------------------------------------------------------------------------

    /**
     * Reclassify a verdict in the light of the recorded waivers.
     *
     * <p>Only unexplained divergences can be waived; anything already accounted for stays as it
     * was. A class whose remaining differences are all waived becomes {@link Verdict#WAIVED}, and
     * one with some waived and some not keeps its original verdict, because the unwaived part is
     * still a problem.
     */
    public ClassVerdict apply(ClassVerdict verdict) {
        if (verdict.verdict() != Verdict.DAMAGED && verdict.verdict() != Verdict.SUSPECT) {
            return verdict;
        }
        List<Waiver> matched = new ArrayList<>();
        List<Divergence> remaining = new ArrayList<>();
        for (Divergence divergence : verdict.unexplained()) {
            Waiver waiver = find(verdict.className(), divergence.signalId());
            if (waiver == null) {
                remaining.add(divergence);
            } else {
                matched.add(waiver);
                used.add(waiver.source() + "|" + waiver.className());
            }
        }
        if (matched.isEmpty()) {
            return verdict;
        }

        List<BenignDivergenceClassifier.Explanation> explanations =
                new ArrayList<>(verdict.explanations());
        for (Waiver waiver : matched.stream().distinct().toList()) {
            explanations.add(new BenignDivergenceClassifier.Explanation(
                    "WAIVED_BY_RECORD", waiver.describe(), waiver.signalList()));
        }
        Verdict resulting = remaining.isEmpty() ? Verdict.WAIVED : verdict.verdict();
        return new ClassVerdict(verdict.className(), resulting, verdict.divergences(),
                List.copyOf(explanations), List.copyOf(remaining), verdict.note());
    }

    private Waiver find(String className, String signalId) {
        for (Waiver waiver : waivers) {
            if (waiver.className().equals(className) && waiver.covers(signalId)) {
                return waiver;
            }
        }
        return null;
    }

    /** Entries that matched nothing on this run: the difference is gone and the entry can go. */
    public List<Waiver> stale() {
        List<Waiver> unmatched = new ArrayList<>();
        for (Waiver waiver : waivers) {
            if (!used.contains(waiver.source() + "|" + waiver.className())) {
                unmatched.add(waiver);
            }
        }
        return List.copyOf(unmatched);
    }

    public List<Rejected> rejected() {
        return rejected;
    }

    public int size() {
        return waivers.size();
    }

    /** Waivers by class, for a caller that wants to look one up. */
    public Map<String, List<Waiver>> byClass() {
        Map<String, List<Waiver>> grouped = new LinkedHashMap<>();
        for (Waiver waiver : waivers) {
            grouped.computeIfAbsent(waiver.className(), k -> new ArrayList<>()).add(waiver);
        }
        return grouped;
    }
}