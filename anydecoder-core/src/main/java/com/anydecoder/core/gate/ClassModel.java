package com.anydecoder.core.gate;

import java.util.List;

/** The structural facts about one class file that the gate compares. */
public record ClassModel(
        int version,
        int access,
        String name,
        String superName,
        List<String> interfaces,
        List<FieldModel> fields,
        List<MethodModel> methods) {

    public MethodModel method(String key) {
        return methods.stream().filter(m -> m.key().equals(key)).findFirst().orElse(null);
    }

    /**
     * The class's minor/major version, which tells which compiler produced it and therefore
     * which {@code -source}/{@code -target} a faithful recompilation needs.
     */
    public String javaVersion() {
        int major = version & 0xFFFF;
        return switch (major) {
            case 45 -> "1.1";
            case 46 -> "1.2";
            case 47 -> "1.3";
            case 48 -> "1.4";
            case 49 -> "5";
            case 50 -> "6";
            case 51 -> "7";
            case 52 -> "8";
            case 53 -> "9";
            case 54 -> "10";
            case 55 -> "11";
            case 56 -> "12";
            case 57 -> "13";
            case 58 -> "14";
            case 59 -> "15";
            case 60 -> "16";
            case 61 -> "17";
            case 62 -> "18";
            case 63 -> "19";
            case 64 -> "20";
            case 65 -> "21";
            default -> "unknown(" + major + ")";
        };
    }
}
