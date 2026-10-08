package com.anydecoder.core.gate;

/** A declared field. {@code signature} carries the generic type when it differs from the erasure. */
public record FieldModel(int access, String name, String descriptor, String signature) {

    /** Identity used by the API signal: visibility, staticness, name and erased type. */
    public String identity() {
        return name + descriptor + " flags=0x" + Integer.toHexString(access);
    }
}
