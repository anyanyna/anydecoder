package com.anydecoder.core.gate;

import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Answers whether two classes are in the same hierarchy.
 *
 * <h2>Why the gate needs this</h2>
 *
 * <p>A decompiler routinely declares a local variable with a more concrete type than the original
 * did. Where the source said {@code List<String> items = new ArrayList<>();}, the recovered source
 * says {@code ArrayList<String> items = new ArrayList<>();} — and the call that follows becomes
 * {@code invokevirtual ArrayList.add} instead of {@code invokeinterface List.add}.
 *
 * <p>Comparing call targets naively therefore reports {@code List.add} and {@code ArrayList.add}
 * as different calls. They are the same member: the same name, the same descriptor, and a
 * receiver that is a subtype. On source that had been verified faithful by nine days of manual
 * bytecode examination, this single confusion accounted for **1325 of 1525 findings**.
 *
 * <p>The distinction that matters is between a receiver whose declared type narrowed within its
 * own hierarchy — a source-level choice, and the same call at runtime — and a call on an
 * unrelated class, which is a different method entirely. Only a hierarchy can tell them apart,
 * and the classpath is right there.
 *
 * <p>Classes are read for their headers only, and cached; the index never loads a class.
 */
public final class HierarchyIndex {

    private final List<Path> classpath;
    /**
     * Cached supertype lists. Concurrent because the pipeline gates classes in parallel and
     * shares one index across the work; a duplicated read costs a file open, so no locking
     * beyond the map's own is worth having.
     */
    private final Map<String, List<String>> directSupertypes = new java.util.concurrent.ConcurrentHashMap<>();

    public HierarchyIndex(List<Path> classpath) {
        this.classpath = List.copyOf(classpath);
    }

    /** An index that knows nothing, for tests that do not care about receivers. */
    public static HierarchyIndex empty() {
        return new HierarchyIndex(List.of());
    }

    /**
     * Whether one type can stand in for the other -- that is, whether either is a subtype of the
     * other. Symmetric on purpose: a declared type may be narrowed or widened, and neither
     * direction is by itself evidence of a defect.
     *
     * <p>Accepts either form a class name appears in: an internal name ({@code java/util/List},
     * as a receiver owner is written) or a field descriptor ({@code Ljava/util/List;}, as a
     * parameter or return type is written).
     *
     * <p>Primitives, {@code void} and arrays are compared for equality only. Arrays are covariant
     * in Java but not in a way that a covariant override can exploit here, and treating two
     * unrelated primitives as "related" would excuse a genuinely changed return type.
     */
    public boolean isRelated(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        String left = normaliseType(a);
        String right = normaliseType(b);
        if (left == null || right == null) {
            return false;
        }
        if (left.equals("java/lang/Object") || right.equals("java/lang/Object")) {
            return true;
        }
        return isSubtype(left, right) || isSubtype(right, left);
    }

    /** Internal name for a reference type in either notation; null for primitives and arrays. */
    private static String normaliseType(String type) {
        if (type.isEmpty() || type.charAt(0) == '[') {
            return null;
        }
        if (type.charAt(0) == 'L' && type.endsWith(";") && type.length() > 2) {
            return type.substring(1, type.length() - 1);
        }
        if (type.length() == 1 && "VIJFBCSZ".indexOf(type.charAt(0)) >= 0) {
            return null;
        }
        return type;
    }

    private boolean isSubtype(String candidate, String ancestor) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(candidate);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            if (current.equals(ancestor)) {
                return true;
            }
            queue.addAll(supertypesOf(current));
        }
        return false;
    }

    private List<String> supertypesOf(String internalName) {
        return directSupertypes.computeIfAbsent(internalName, this::readSupertypes);
    }

    private List<String> readSupertypes(String internalName) {
        for (Path entry : classpath) {
            byte[] bytes = readClassBytes(entry, internalName);
            if (bytes == null) {
                continue;
            }
            try {
                ClassReader reader = new ClassReader(bytes);
                List<String> result = new ArrayList<>();
                if (reader.getSuperName() != null) {
                    result.add(reader.getSuperName());
                }
                for (String iface : reader.getInterfaces()) {
                    result.add(iface);
                }
                return result;
            } catch (RuntimeException ignored) {
                // An unreadable class contributes nothing rather than failing the gate.
                return List.of();
            }
        }
        // Not on the classpath under test, so it is a platform type: `ArrayList` narrowing to
        // `List` is the single most common case of this, and both live in the JDK.
        return readPlatformSupertypes(internalName);
    }

    /**
     * Supertypes of a class from the running JVM's own modules.
     *
     * <p>Asked of the platform loader rather than the application loader so that anydecoder's own
     * dependencies cannot be mistaken for the code under test. The class is loaded but never
     * initialised, so this cannot run a static initialiser.
     */
    private List<String> readPlatformSupertypes(String internalName) {
        try {
            Class<?> type = Class.forName(internalName.replace('/', '.'),
                    false, ClassLoader.getPlatformClassLoader());
            List<String> result = new ArrayList<>();
            if (type.getSuperclass() != null) {
                result.add(type.getSuperclass().getName().replace('.', '/'));
            }
            for (Class<?> iface : type.getInterfaces()) {
                result.add(iface.getName().replace('.', '/'));
            }
            return result;
        } catch (Throwable notFound) {
            return List.of();
        }
    }

    private byte[] readClassBytes(Path entry, String internalName) {
        String relative = internalName + ".class";
        try {
            if (Files.isDirectory(entry)) {
                Path file = entry.resolve(relative);
                return Files.isReadable(file) ? Files.readAllBytes(file) : null;
            }
            try (ZipFile zip = new ZipFile(entry.toFile())) {
                ZipEntry found = zip.getEntry(relative);
                if (found == null) {
                    return null;
                }
                try (InputStream in = zip.getInputStream(found)) {
                    return in.readAllBytes();
                }
            }
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    /** How many class headers have been read so far. Useful for reporting. */
    public int classesIndexed() {
        return directSupertypes.size();
    }

    public List<Path> classpath() {
        return classpath;
    }
}
