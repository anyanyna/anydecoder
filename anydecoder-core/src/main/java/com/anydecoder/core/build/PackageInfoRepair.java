package com.anydecoder.core.build;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.TypePath;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Makes javac emit a class for an empty {@code package-info.java}.
 *
 * <p>javac omits the class file when a package declaration carries no annotations and the file
 * declares nothing else. The original build emitted one anyway -- older toolchains did, and
 * annotation processors can force it -- so the rebuilt tree would contain a class the gate can
 * never reach. Adding a {@code SOURCE}-retention annotation is the smallest source edit that
 * makes javac emit the same empty synthetic interface, and because the annotation is not
 * retained it leaves the class bytes identical.
 *
 * <p>The repair is deliberately narrow: it only touches a source whose original class is an
 * empty synthetic package-info with no fields, methods, or annotations. If the original carries
 * an annotation, the decompiler may have dropped it, and adding one here would hand the gate a
 * class it should be comparing instead.
 */
public final class PackageInfoRepair {

    private static final String CLASS_SUFFIX = ".class";
    private static final String SOURCE_SUFFIX = ".java";
    private static final String PACKAGE_INFO_CLASS = "package-info.class";

    private PackageInfoRepair() {
    }

    /**
     * Adds an annotation to empty package-info sources and returns how many files changed.
     *
     * @param originalClasses class files extracted from the input
     * @param sourceRoot      decompiled source tree that will be compiled
     * @param release         javac release used for the rebuild
     * @param log             progress output; may be {@code null}
     */
    public static int repair(Path originalClasses, Path sourceRoot, String release, PrintStream log)
            throws IOException {
        if (!Files.isDirectory(originalClasses) || !Files.isDirectory(sourceRoot)) {
            return 0;
        }
        String annotation = generatedAnnotation(release);
        if (annotation == null) {
            return 0;
        }

        List<Path> classFiles;
        try (var stream = Files.walk(originalClasses)) {
            classFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(PACKAGE_INFO_CLASS))
                    .sorted()
                    .toList();
        }

        int repaired = 0;
        for (Path classFile : classFiles) {
            if (isVersioned(classFile, originalClasses) || !isEmptyPackageInfo(classFile)) {
                continue;
            }
            Path source = sourceRoot.resolve(relativeSource(originalClasses, classFile));
            if (!Files.isRegularFile(source)) {
                continue;
            }
            String text = Files.readString(source, StandardCharsets.UTF_8);
            if (hasPackageAnnotation(text)) {
                continue;
            }
            Files.writeString(source, annotation + "\n" + text,
                    StandardCharsets.UTF_8);
            repaired++;
        }

        if (repaired > 0 && log != null) {
            log.printf("[build] annotated %d empty package-info file(s) so javac emits the class%n",
                    repaired);
        }
        return repaired;
    }

    private static String relativeSource(Path originalClasses, Path classFile) {
        String relative = originalClasses.relativize(classFile).toString().replace('\\', '/');
        return relative.substring(0, relative.length() - CLASS_SUFFIX.length()) + SOURCE_SUFFIX;
    }

    private static boolean isVersioned(Path classFile, Path originalClasses) {
        return originalClasses.relativize(classFile).toString().replace('\\', '/')
                .startsWith("META-INF/");
    }

    /**
     * {@code javax.annotation.Generated} was removed from the JDK in 11, after being deprecated
     * in 9; {@code javax.annotation.processing.Generated} exists from 9 onward. Both are
     * {@code SOURCE}-retention and target {@code PACKAGE}.
     */
    private static String generatedAnnotation(String release) {
        if (release == null) {
            return null;
        }
        int major;
        try {
            major = release.startsWith("1.")
                    ? Integer.parseInt(release.substring(2))
                    : Integer.parseInt(release);
        } catch (RuntimeException e) {
            return null;
        }
        if (major >= 9) {
            return "@javax.annotation.processing.Generated(\"anydecoder\")";
        }
        if (major >= 6) {
            return "@javax.annotation.Generated(\"anydecoder\")";
        }
        return null;
    }

    /** True when an annotation already sits between the start of the file and {@code package}. */
    static boolean hasPackageAnnotation(String source) {
        int packageKeyword = source.indexOf("package");
        if (packageKeyword < 0) {
            return false;
        }
        String before = source.substring(0, packageKeyword)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//[^\\n]*", "");
        return before.contains("@");
    }

    private static boolean isEmptyPackageInfo(Path classFile) throws IOException {
        EmptyPackageInfoVisitor visitor = new EmptyPackageInfoVisitor();
        new ClassReader(Files.readAllBytes(classFile))
                .accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return visitor.empty();
    }

    private static final class EmptyPackageInfoVisitor extends ClassVisitor {

        private boolean flagsMatch;
        private boolean simpleShape;
        private boolean members;
        private boolean annotations;

        EmptyPackageInfoVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                String[] interfaces) {
            flagsMatch = (access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT
                    | Opcodes.ACC_SYNTHETIC)) == (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT
                    | Opcodes.ACC_SYNTHETIC);
            simpleShape = "java/lang/Object".equals(superName)
                    && (interfaces == null || interfaces.length == 0);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            annotations = true;
            return null;
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef,
                TypePath typePath, String descriptor, boolean visible) {
            annotations = true;
            return null;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature,
                Object value) {
            members = true;
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
            members = true;
            return null;
        }

        boolean empty() {
            return flagsMatch && simpleShape && !members && !annotations;
        }
    }
}
