package com.anydecoder.core.build;

import java.nio.file.Path;
import java.util.List;

/** The result of compiling a source tree. */
public record CompileOutcome(boolean success, List<Diagnostic> diagnostics, Path outputDirectory) {

    /**
     * One compiler message, already structured.
     *
     * @param file    source file, or null for a message about the invocation itself
     * @param line    1-based line, or -1
     * @param column  1-based column, or -1
     * @param kind    {@code ERROR}, {@code WARNING} or {@code NOTE}
     * @param code    the compiler's own code, or null
     * @param message the message text
     */
    public record Diagnostic(
            Path file,
            long line,
            long column,
            String kind,
            String code,
            String message) {

        public String render() {
            StringBuilder sb = new StringBuilder();
            if (file != null) {
                sb.append(file);
                if (line > 0) {
                    sb.append(':').append(line);
                    if (column > 0) {
                        sb.append(':').append(column);
                    }
                }
                sb.append(": ");
            }
            sb.append(kind.toLowerCase());
            if (code != null) {
                sb.append(" [").append(code).append(']');
            }
            sb.append(": ").append(message);
            return sb.toString();
        }
    }

    public List<Diagnostic> errors() {
        return diagnostics.stream().filter(d -> "ERROR".equals(d.kind())).toList();
    }

    public long errorCount() {
        return errors().size();
    }
}
