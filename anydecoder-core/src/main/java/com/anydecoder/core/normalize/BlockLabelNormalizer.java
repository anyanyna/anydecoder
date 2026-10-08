package com.anydecoder.core.normalize;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes the {@code blockNN:} labels a decompiler emits when it loses a method's structure.
 *
 * <p>These labels are the most alarming-looking thing in decompiled source and the least
 * dangerous. {@code block3: { ... if (!x) break block3; ... }} is ordinary Java that means
 * exactly what the original meant -- the bytecode gate confirms it byte for byte -- and a
 * source file full of them is functionally correct. What they cost is readability, and with it
 * the ability of a person to review or modify the code.
 *
 * <p>Two shapes are handled mechanically, and only where the rewrite is provably
 * target-preserving:
 *
 * <ul>
 *   <li><b>loop label</b> -- {@code blockN: for (...)} where the loop body contains no nested
 *       loop, so {@code break blockN} and {@code break} can only mean the same thing;</li>
 *   <li><b>empty label</b> -- {@code blockN: { ... }} that nothing breaks out of, where the
 *       label and its braces are pure decoration.</li>
 * </ul>
 *
 * <p>Anything else is left alone. A label with a forward {@code break} across nested
 * try/catch blocks is a real control-flow edge, and rewriting it is a comprehension task rather
 * than a mechanical one -- that is a job for the readable-refactor agent, which can attempt it
 * freely because the gate will reject anything that changes behaviour.
 */
public final class BlockLabelNormalizer implements SourceNormalizer {

    private static final Pattern LOOP_LABEL = Pattern.compile(
            "^([ \\t]*)block(\\d+):[ \\t]*(for|while|do)\\b", Pattern.MULTILINE);

    private static final Pattern PLAIN_LABEL = Pattern.compile(
            "^([ \\t]*)block(\\d+):[ \\t]*\\{[ \\t]*\\R", Pattern.MULTILINE);

    private static final Pattern NESTED_LOOP = Pattern.compile("\\b(?:for|while|do)[ \\t]*[({]");

    @Override
    public String id() {
        return "R3-block-labels";
    }

    @Override
    public String description() {
        return "remove blockNN labels where the rewrite cannot change a branch target";
    }

    @Override
    public Result normalize(String source) {
        if (!source.contains("block")) {
            return Result.unchanged(source);
        }
        String working = source;
        List<String> changes = new ArrayList<>();

        int removedLoops = removeLoopLabels(working);
        if (removedLoops > 0) {
            working = rewriteLoopLabels(working);
            changes.add("removed " + removedLoops + " loop label(s)");
        }

        int removedPlain = removePlainLabels(working);
        if (removedPlain > 0) {
            working = rewritePlainLabels(working);
            changes.add("removed " + removedPlain + " unused block label(s)");
        }

        if (changes.isEmpty()) {
            return Result.unchanged(source);
        }
        return new Result(working, List.copyOf(changes), Map.of());
    }

    // ------------------------------------------------------------------------------------------

    private static int removeLoopLabels(String source) {
        Matcher m = LOOP_LABEL.matcher(source);
        int count = 0;
        while (m.find()) {
            int labelNumber = Integer.parseInt(m.group(2));
            int bodyOpen = source.indexOf('{', m.end());
            if (bodyOpen < 0) {
                continue;
            }
            int bodyClose = matchBrace(source, bodyOpen);
            if (bodyClose < 0) {
                continue;
            }
            String body = JavaText.stripComments(source.substring(bodyOpen, bodyClose));
            if (NESTED_LOOP.matcher(body).find()) {
                continue;
            }
            if (!body.contains("break block" + labelNumber)
                    && !body.contains("continue block" + labelNumber)) {
                continue;
            }
            count++;
        }
        return count;
    }

    private static String rewriteLoopLabels(String source) {
        Matcher m = LOOP_LABEL.matcher(source);
        StringBuilder out = new StringBuilder(source);
        List<int[]> edits = new ArrayList<>();
        while (m.find()) {
            int labelNumber = Integer.parseInt(m.group(2));
            int bodyOpen = source.indexOf('{', m.end());
            if (bodyOpen < 0) {
                continue;
            }
            int bodyClose = matchBrace(source, bodyOpen);
            if (bodyClose < 0) {
                continue;
            }
            String body = JavaText.stripComments(source.substring(bodyOpen, bodyClose));
            if (NESTED_LOOP.matcher(body).find()) {
                continue;
            }
            if (!body.contains("break block" + labelNumber)
                    && !body.contains("continue block" + labelNumber)) {
                continue;
            }
            // Drop the "blockN: " prefix, keeping the loop statement itself.
            edits.add(new int[]{m.start(2), m.end(2) + 1});
            replaceAllInRange(out, bodyOpen, bodyClose,
                    "break block" + labelNumber + ";", "break;");
            replaceAllInRange(out, bodyOpen, bodyClose,
                    "continue block" + labelNumber + ";", "continue;");
        }
        for (int i = edits.size() - 1; i >= 0; i--) {
            int[] edit = edits.get(i);
            // The label text sits between the indentation and the keyword.
            out.delete(edit[0] - "block".length(), edit[1]);
        }
        return out.toString();
    }

    private static int removePlainLabels(String source) {
        Matcher m = PLAIN_LABEL.matcher(source);
        int count = 0;
        while (m.find()) {
            int labelNumber = Integer.parseInt(m.group(2));
            int bodyOpen = source.indexOf('{', m.start());
            int bodyClose = bodyOpen < 0 ? -1 : matchBrace(source, bodyOpen);
            if (bodyClose < 0) {
                continue;
            }
            if (source.substring(bodyOpen, bodyClose).contains("break block" + labelNumber)) {
                continue;
            }
            count++;
        }
        return count;
    }

    private static String rewritePlainLabels(String source) {
        Matcher m = PLAIN_LABEL.matcher(source);
        StringBuilder out = new StringBuilder(source);
        // Rightmost first, so the offsets found in the original text stay valid as we edit.
        List<int[]> edits = new ArrayList<>();
        List<String> replacements = new ArrayList<>();
        while (m.find()) {
            int labelNumber = Integer.parseInt(m.group(2));
            int bodyOpen = source.indexOf('{', m.start());
            int bodyClose = bodyOpen < 0 ? -1 : matchBrace(source, bodyOpen);
            if (bodyClose < 0) {
                continue;
            }
            if (source.substring(bodyOpen, bodyClose).contains("break block" + labelNumber)) {
                continue;
            }
            String indent = m.group(1);
            String body = source.substring(m.end(), bodyClose);
            String deIndented = body.replaceAll("(?m)^" + Pattern.quote(indent + "    "),
                    Matcher.quoteReplacement(indent));
            // The label line and the closing brace line both disappear; everything between
            // them is the block body, one indentation level shallower.
            int closeLineEnd = bodyClose + 1;
            while (closeLineEnd < source.length() && source.charAt(closeLineEnd) != '\n') {
                closeLineEnd++;
            }
            int end = Math.min(source.length(), closeLineEnd + 1);
            edits.add(new int[]{m.start(), end});
            replacements.add(deIndented);
        }
        for (int i = edits.size() - 1; i >= 0; i--) {
            out.replace(edits.get(i)[0], edits.get(i)[1], replacements.get(i));
        }
        return out.toString();
    }

    // ------------------------------------------------------------------------------------------

    private static void replaceAllInRange(StringBuilder text, int from, int to,
                                          String target, String replacement) {
        int index = text.indexOf(target, from);
        while (index >= 0 && index < to) {
            text.replace(index, index + target.length(), replacement);
            to -= target.length() - replacement.length();
            index = text.indexOf(target, index + replacement.length());
        }
    }

    /** Index of the brace matching the one at {@code open}, or -1. String and comment aware. */
    static int matchBrace(String source, int open) {
        Deque<Integer> stack = new ArrayDeque<>();
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < n) {
                    if (source.charAt(i) == '\\') {
                        i += 2;
                        continue;
                    }
                    if (source.charAt(i) == quote || source.charAt(i) == '\n') {
                        i++;
                        break;
                    }
                    i++;
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
            if (c == '{') {
                stack.push(i);
            } else if (c == '}') {
                if (stack.isEmpty()) {
                    return -1;
                }
                int matched = stack.pop();
                if (matched == open) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }
}
