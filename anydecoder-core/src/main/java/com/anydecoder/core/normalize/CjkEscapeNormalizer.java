package com.anydecoder.core.normalize;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites {@code \\uXXXX} escapes for CJK characters back to the characters themselves.
 *
 * <p>CFR escapes every non-ASCII character in a string literal; Vineflower writes them raw. Both
 * produce the same bytes, but a tree full of {@code 中文} is unreadable and unreviewable,
 * which matters because a human still has to sign off on the result.
 *
 * <p>Only the CJK ranges are touched. Decoding an arbitrary escape would also decode
 * {@code \u000a} into a real newline, and Java processes unicode escapes before parsing, so a
 * character that is invisible in the source can be load-bearing.
 */
public final class CjkEscapeNormalizer implements SourceNormalizer {

    private static final Pattern ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

    /** CJK Unified Ideographs, the range the original toolchain settled on. */
    private static final int CJK_START = 0x4E00;
    private static final int CJK_END = 0x9FFF;

    @Override
    public String id() {
        return "R5-cjk-escapes";
    }

    @Override
    public String description() {
        return "decode \\uXXXX escapes in the CJK range back to characters";
    }

    @Override
    public Result normalize(String source) {
        Matcher m = ESCAPE.matcher(source);
        StringBuilder out = new StringBuilder(source.length());
        int decoded = 0;
        while (m.find()) {
            int codePoint = Integer.parseInt(m.group(1), 16);
            if (codePoint >= CJK_START && codePoint <= CJK_END) {
                m.appendReplacement(out, Matcher.quoteReplacement(String.valueOf((char) codePoint)));
                decoded++;
            }
        }
        if (decoded == 0) {
            return Result.unchanged(source);
        }
        m.appendTail(out);
        return new Result(out.toString(),
                List.of("decoded " + decoded + " CJK escape(s)"),
                Map.of("decodedEscapes", Integer.toString(decoded)));
    }
}
