package com.anydecoder.core.normalize;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes CFR's leading banner, and records what it said.
 *
 * <p>The banner is a comment, so leaving it in changes no bytecode -- but it names the classes
 * CFR could not resolve, and that list is the single most useful quality signal about a
 * decompilation. A file whose banner lists unresolved types was decompiled without a classpath,
 * which is exactly where a decompiler guesses at types instead of reading them. The banner is
 * removed from the source and the list is moved into the report where it can be acted on.
 */
public final class CfrHeaderNormalizer implements SourceNormalizer {

    private static final Pattern BANNER = Pattern.compile(
            "\\A\\s*/\\*(?:(?!\\*/).)*?(?:Decompiled with|Could not load the following classes)"
                    + "(?:(?!\\*/).)*?\\*/\\s*",
            Pattern.DOTALL);

    /**
     * The class names in the banner's unresolved list. Names are restricted to identifier
     * characters so the closing {@code *}{@code /} of the comment is not mistaken for an entry.
     */
    private static final Pattern UNRESOLVED = Pattern.compile(
            "Could not load the following classes:\\s*((?:\\s*\\*\\s*[\\w$.]+)+)", Pattern.DOTALL);

    @Override
    public String id() {
        return "R1-cfr-header";
    }

    @Override
    public String description() {
        return "remove the decompiler banner, keeping its unresolved-class list as metadata";
    }

    @Override
    public Result normalize(String source) {
        Matcher banner = BANNER.matcher(source);
        if (!banner.find()) {
            return Result.unchanged(source);
        }
        String header = banner.group();
        List<String> unresolved = unresolvedClasses(header);
        String stripped = banner.replaceFirst("");

        List<String> changes = new ArrayList<>();
        changes.add("removed the decompiler banner");
        Map<String, String> metadata = unresolved.isEmpty()
                ? Map.of()
                : Map.of("unresolvedClasses", String.join(",", unresolved));
        return new Result(stripped, changes, metadata);
    }

    private static List<String> unresolvedClasses(String header) {
        Matcher m = UNRESOLVED.matcher(header);
        if (!m.find()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String line : m.group(1).split("\\R")) {
            String name = line.replaceFirst("^\\s*\\*\\s*", "").trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }
}
