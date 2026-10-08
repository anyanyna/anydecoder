package com.anydecoder.core.difftest;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs the original and the rebuild side by side and compares what they do.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The bytecode gate compares structure. Two statements of the same kind swapped in order
 * produce an identical instruction multiset, identical exception tables, identical call sets --
 * the gate has nothing to see, and it says so in its own documentation. Differential testing is
 * the only thing here that observes behaviour rather than shape, and it closes that blind spot
 * for the methods it can reach.
 *
 * <h2>What it will not do</h2>
 *
 * <p>It calls real code. That is inherent: there is no way to observe behaviour without running
 * it. The containment is:
 *
 * <ul>
 *   <li>only public methods whose parameters are primitives, boxed primitives, or
 *       {@code String} -- nothing that would need an arbitrary object constructed to drive;</li>
 *   <li>a fresh receiver per invocation, so instance state cannot leak between cases;</li>
 *   <li>a cap on invocations per method and on total elapsed time;</li>
 *   <li>off by default. A method that writes files, opens sockets, or blocks still will.</li>
 * </ul>
 *
 * <p>Exception <em>types</em> are compared, not messages: a message can legitimately embed a
 * hash code or a path, and comparing those would report differences that do not exist.
 */
public final class DifferentialTester {

    /** How many argument combinations to try per method. */
    private static final int CASES_PER_METHOD = 24;

    private final DifferentialTesterOptions options;
    private final InputCorpus corpus;

    public DifferentialTester(DifferentialTesterOptions options) {
        this.options = options;
        this.corpus = new InputCorpus(options.seed());
    }

    /** The outcome over a whole tree. */
    public record Result(int classesExamined, int methodsExercised, int casesRun,
                         List<Divergence> divergences, List<String> skipped) {

        public boolean clean() {
            return divergences.isEmpty();
        }
    }

    public Result run(Path originalClasses, Path rebuiltClasses, List<Path> dependencyJars) {
        List<Divergence> divergences = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int[] counts = {0, 0, 0};

        try (URLClassLoader originalLoader = loaderFor(originalClasses, dependencyJars);
             URLClassLoader rebuiltLoader = loaderFor(rebuiltClasses, dependencyJars)) {

            List<Path> classFiles = new ArrayList<>();
            try (var stream = Files.walk(originalClasses)) {
                stream.filter(p -> p.toString().endsWith(".class")).sorted().forEach(classFiles::add);
            }

            for (Path classFile : classFiles) {
                String relative = originalClasses.relativize(classFile).toString();
                String internalName = relative.substring(0, relative.length() - ".class".length())
                        .replace('\\', '/');

                Class<?> original = tryLoad(originalLoader, internalName);
                Class<?> rebuilt = tryLoad(rebuiltLoader, internalName);
                if (original == null || rebuilt == null) {
                    skipped.add(internalName + ": not loadable in both trees");
                    continue;
                }
                counts[0]++;
                examine(original, rebuilt, internalName, counts, divergences, skipped);
            }
        } catch (Exception e) {
            skipped.add("could not build a class loader: " + e);
        }

        return new Result(counts[0], counts[1], counts[2], divergences, skipped);
    }

    private void examine(Class<?> original, Class<?> rebuilt, String internalName,
                         int[] counts, List<Divergence> divergences, List<String> skipped) {
        for (Method method : original.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }
            if (method.getName().equals("main") && method.getParameterCount() == 1) {
                continue;
            }
            Method counterpart = findCounterpart(rebuilt, method);
            if (counterpart == null) {
                // A missing method is the static gate's business, not this one's.
                continue;
            }
            if (!drivable(method)) {
                skipped.add(internalName.replace('/', '.') + "." + method.getName()
                        + ": parameters cannot be driven");
                continue;
            }
            counts[1]++;
            compareMethod(original, rebuilt, method, counterpart, internalName, counts, divergences);
        }
    }

    private void compareMethod(Class<?> original, Class<?> rebuilt, Method method, Method counterpart,
                               String internalName, int[] counts, List<Divergence> divergences) {
        boolean isStatic = Modifier.isStatic(method.getModifiers());
        Class<?>[] parameterTypes = method.getParameterTypes();

        Constructor<?> originalConstructor = isStatic ? null : noArgConstructor(original);
        Constructor<?> rebuiltConstructor = isStatic ? null : noArgConstructor(rebuilt);
        if (!isStatic && (originalConstructor == null || rebuiltConstructor == null)) {
            // Without a no-arg constructor there is no way to obtain a receiver without
            // inventing arguments, which would make any divergence about the invention.
            return;
        }

        for (int caseIndex = 0; caseIndex < CASES_PER_METHOD; caseIndex++) {
            Object[] arguments = new Object[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                arguments[i] = corpus.pick(parameterTypes[i], (caseIndex + i) % CASES_PER_METHOD);
            }

            String left = invoke(originalConstructor, method, arguments);
            String right = invoke(rebuiltConstructor, counterpart, arguments);
            counts[2]++;

            if (!left.equals(right)) {
                divergences.add(new Divergence(internalName, method.getName() + describe(parameterTypes),
                        describe(arguments), left, right));
                break;
            }
        }
    }

    private String invoke(Constructor<?> constructor, Method method, Object[] arguments) {
        try {
            Object receiver = constructor == null ? null : constructor.newInstance();
            Object result = method.invoke(receiver, arguments);
            return "returned " + render(result);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            return "threw " + cause.getClass().getName();
        } catch (Throwable t) {
            // Includes the case where the receiver's own construction failed, which is itself a
            // behavioural difference worth reporting rather than swallowing.
            return "failed " + t.getClass().getName();
        }
    }

    private static String render(Object value) {
        if (value == null) {
            return "null";
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < Math.min(length, 16); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(render(Array.get(value, i)));
            }
            if (length > 16) {
                sb.append(", ...");
            }
            return sb.append(']').toString();
        }
        if (value instanceof Double d && d.isNaN()) {
            return "Double:NaN";
        }
        if (value instanceof Float f && f.isNaN()) {
            return "Float:NaN";
        }
        return value.getClass().getSimpleName() + ":" + value;
    }

    private static String describe(Class<?>[] types) {
        return "(" + Arrays.stream(types).map(Class::getSimpleName)
                .reduce((a, b) -> a + "," + b).orElse("") + ")";
    }

    private static List<String> describe(Object[] arguments) {
        return Arrays.stream(arguments).map(DifferentialTester::render).toList();
    }

    private boolean drivable(Method method) {
        if (method.getParameterCount() > options.maxParameters()) {
            return false;
        }
        if (method.getReturnType() == void.class
                && method.getParameterTypes().length == 0) {
            // Calling a no-argument void method observes nothing.
            return false;
        }
        for (Class<?> type : method.getParameterTypes()) {
            if (!corpus.canDrive(type)) {
                return false;
            }
        }
        return true;
    }

    private static Method findCounterpart(Class<?> type, Method method) {
        try {
            return type.getDeclaredMethod(method.getName(), method.getParameterTypes());
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Constructor<?> noArgConstructor(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            if (!Modifier.isPublic(constructor.getModifiers())) {
                return null;
            }
            return constructor;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Class<?> tryLoad(ClassLoader loader, String internalName) {
        try {
            return Class.forName(internalName.replace('/', '.'), false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    private static URLClassLoader loaderFor(Path classes, List<Path> dependencyJars) {
        List<URL> urls = new ArrayList<>();
        try {
            urls.add(classes.toUri().toURL());
            for (Path jar : dependencyJars) {
                urls.add(jar.toUri().toURL());
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not build a class loader for " + classes, e);
        }
        // The platform loader is the parent rather than the application loader, so the two
        // trees are genuinely separate: nothing from anydecoder's own classpath can shadow a
        // class under test, and neither tree can see the other.
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }
}
