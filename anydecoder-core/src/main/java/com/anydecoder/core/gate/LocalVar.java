package com.anydecoder.core.gate;

/**
 * A declared local variable from the LocalVariableTable.
 *
 * <p>The slot index is deliberately dropped: it is assigned by the compiler's register
 * allocator and differs between javac and ecj for the same source. Name and type are kept
 * because a faithful recompilation reproduces both.
 */
public record LocalVar(String name, String descriptor) {

    public String render() {
        return name + ":" + descriptor;
    }
}
