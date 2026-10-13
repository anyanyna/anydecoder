# Decompiler failure modes

What actually goes wrong, what it looks like, and how to tell. Everything here came from a real
recovery of 2451 source files, and each entry is followed by the gate signal that detects it —
where one exists.

**The entries marked "silent" are the ones to care about.** They produce source that compiles, and
in several cases that reads *better* than the original.

---

## Defects the gate catches

### A `finally` rendered as try-with-resources

**What it looks like.** The decompiler recognised the shape and emitted idiomatic modern Java:

```java
// original bytecode, from:
//   Writer w = new FileWriter(target);
//   try { w.write(text); } finally { w.close(); }
try (FileWriter w = new FileWriter(target)) {
    w.write(text);
}
```

**Why it is wrong.** Two changes, both behavioural. Try-with-resources adds
`Throwable.addSuppressed` calls the original never made, so an exception thrown by `close()` while
another is propagating is now attached rather than replacing it. And the variable's declared type
became `FileWriter` where the original declared `Writer`, which changes which `write` and `close`
overloads bind.

**Detected by.** `excp` — the rebuild has more handler groups, and `invokes` shows
`addSuppressed` plus calls on a more derived receiver.

**Fix.** Restore the explicit `try`/`finally`, and declare the resource with the type the original
used.

---

### A `try` block dropped entirely

**What it looks like.** Not much. The method compiles and runs.

**Detected by.** `excp` — handler groups disappear.

**Fix.** Rebuild the `try` from the original's exception table, which records the exact protected
range and the handler type. This is the most reliable information available about failed control
flow and it is exact.

---

### A swallowed rethrow

**What it looks like.** A `throw` inside a `catch` becomes a fall-through.

**Why it is nearly silent.** The exception table still looks correct and the instruction count
moves by a couple of bytes, so `excp` and `instrCount` both stay quiet. Only the rethrow count
shows it.

**Detected by.** `athrow`.

---

### Structure collapse: `Unable to fully structure code`

**What it looks like.** A method that never closes, so every subsequent method in the file becomes
nested inside it. Local variable declarations vanish from the affected method, leaving assignments
to undeclared names, and the decompiler sometimes invents static imports to explain the mess.

One real instance swallowed roughly forty methods into a single method body and produced two
phantom static imports.

**Detected by.** `members` — the swallowed methods are absent from the rebuild.

**Fix.** Manual, from the bytecode: close the method, lift the nested methods back to class level,
restore the declarations. There is no mechanical shortcut.

---

### A narrowed or widened declared local type

**What it looks like.** `Writer w` becomes `FileWriter w`; `int i` becomes `Integer i`.

**Why it is wrong.** The first silently changes overload resolution. The second introduces boxing
calls the original did not have.

**Detected by.** `lvt` (advisory) for the type, `invokes` (hard) when it produces boxing traffic.

**Fix.** Take the declared types from the original's `LocalVariableTable`. It is the authority and
it is exact.

---

### A constructor lost or made public

**What it looks like.** A class that declares no constructor where the original had one, or a
package-private class whose constructor the decompiler emitted as `public`.

**Detected by.** `api`, and `members` when the constructor is missing entirely.

---

### Generic erasure guessed wrong

**What it looks like.** `ArrayList<Object>` where the original was `ArrayList<Record>`, or a
`Map<?, String>` that will not compile.

**Detected by.** Nothing directly — but the resulting source will not compile, and if it does
compile the erased bytecode often differs.

**Fix.** Read the real generic signature from the class file. Constant-pool method and field
entries carry a `Signature` attribute holding the actual type arguments; the decompiler's guess is
not authoritative and should not be copied.

---

### A source edit that the original does not have

The mirror image of a decompiler defect, and harder to notice because the recovered source reads
*better* than the original did.

One recovered method wrapped a call in `try { ... } catch (Exception e) { Log.log(ERROR, "...", e); }`
where the original had **no exception table at all** for that method. As recovered, a failure was
logged and start-up continued in a partially initialised state; originally it propagated and
aborted.

Nothing in a comparison of call counts, field accesses or signatures can see this — the call is
still made, once, to the same target. It shows up as an exception table the original does not
have, which is the `excp` signal. It is worth checking any `catch` that logs and continues,
because the recovered source may have invented it.

---

## Things that look alarming and are fine

### `blockNN:` labels

```java
block3: {
    ...
    if (!condition) break block3;
    ...
}
```

This is valid Java, and its runtime behaviour is identical to the `if`/`else` structure the author
wrote. The gate confirms it. It is a readability problem and nothing else — do not expect fixing
it to change any verdict, and do not treat its presence as evidence of damage.

The `readable-refactor` agent removes these under gate protection; the mechanical pass in
`BlockLabelNormalizer` handles the cases where the rewrite provably cannot retarget a branch.

### Decompiler warning comments

`/* WARNING - Removed try catching itself */`, `/* Exception decompiling */` and four similar
markers appear before methods the decompiler found hard. Many are false alarms — the method gates
clean, so the warning is noise. They are comments and cannot affect bytecode either way.

### Decompiler banners listing unresolved classes

```
/* Decompiled with CFR 0.152.
 * Could not load the following classes:
 *  org.zkoss.zul.Window
 */
```

The banner is removed, but the list is kept as metadata because it is the best single quality
signal about a run. A file with unresolved classes was decompiled without a classpath, which is
exactly where the decompiler guesses at types instead of reading them. In one corpus, 42% of files
carried this marker.

### `sun.misc.BASE64Encoder` and similar

These produce a compile **warning** about internal proprietary API, never an error. A warning is
not a defect. Suppress it with a javac option rather than editing the source.

### A malformed string constant

One recovered file contained a SQL string missing a closing parenthesis. That malformation was in
the original class file's constant pool — a bug in the original code, faithfully reproduced. Check
the constant pool before "fixing" a string that looks wrong.

---

## Pitfalls in the tooling, not the decompiler

### Removing an unused import can change behaviour

`import a.b.C;` in a file that never writes `C` looks removable. If the classpath also offers
another `C`, that import was the only thing selecting which one an unqualified use resolves to —
and removing it changes which overload is called while still compiling cleanly.

`UnusedImportNormalizer` applies three guards: wildcards are never removed (no simple name to
test, invisible reach), static imports are never removed (they participate in overload resolution
by signature), and an import is left alone when the file also imports something else under the
same simple name.

The check is on whole identifiers, and the gate is the backstop: a removal that changed the
bytecode shows up as a worse verdict.

### javac silently stops after 100 errors

The default `-Xmaxerrs` is 100. On the validation corpus the first rebuild reported exactly 100
errors; the real number was around 4800, which matches what the original nine-day recovery
measured from the other direction.

This is not a cosmetic undercount. Anything that acts on the error list acts on a truncated one:
`SourceTreeCompiler` peels the files the compiler names and retries, so with the cap in place it
removed a hundred files per round and took more than twenty rounds to converge. Raising the limit
took it to two.

If you write tooling that reads compiler diagnostics, raise the cap explicitly. There is no
signal in the output that it was applied.

### Rebuilding the jar under a running JVM

A JVM reads a jar's directory when it opens it, but loads each class from it lazily, on first use.
Replacing the jar while a run is in flight therefore breaks every class not yet loaded, and the
symptom is not "the jar changed" — it is a `ClassNotFoundException` for a class that is plainly in
the jar:

```
NoClassDefFoundError: org/objectweb/asm/ClassVisitor
Caused by: ClassNotFoundException: org.objectweb.asm.ClassVisitor
    at com.anydecoder.core.pipeline.DecoderPipeline.dominantRelease(...)
```

It cost two full pipeline runs to notice, because the class name changes with whatever the run
happened to need next. Copy the jar somewhere outside `target/` before a long run, and rebuild
freely:

```bash
cp anydecoder-cli/target/anydecoder.jar /tmp/frozen-anydecoder.jar
java -Xmx6g -jar /tmp/frozen-anydecoder.jar run ...
```

### A run that exhausts its heap fails one class at a time

Compiling a decompiled tree happens in-process on the whole tree, and the peel repeats that until
it converges, so the peak is a property of the project rather than of the largest class — a few
thousand classes needs more than 4 GB for the launcher.

Under-provisioning does not produce an `OutOfMemoryError` where you would look for one. Gating
runs over a parallel stream, so the first failure to surface is whichever class happens to be in
flight, and what it reports is an error about a class file. anydecoder recognises the case and
names the heap, but the flag still has to be raised.

### Two classpath orders can resolve differently

When jars overlap, `-cp "lib/*"` expands in alphabetical order while an IDE resolves in the order
its project file declares. Those can pick different implementations of the same class.

In one project, 3451 classes were provided by more than one jar and 844 of them differed in
content. A controller called three methods that existed in only one of two competing jars, so it
compiled under one order and failed under the other — having been reported as "0 errors" by a
build that used the other.

**Check both orders before declaring a build clean.** If a method is missing that clearly exists,
look for duplicate jars before suspecting the source.

### Compiling at a newer Java release breaks the comparison

From Java 9, string concatenation compiles to an `invokedynamic` call to `makeConcatWithConstants`
instead of a `StringBuilder` chain. Compiling Java 8 bytecode's source at release 17 therefore
changes every concatenating method for reasons unrelated to the source.

`ProjectCompiler` takes the release from the original class files' major version and pins the
rebuild to it.

### `String.valueOf(x) + y` must not be simplified

It looks like a needless wrapper. `String.valueOf(char[])` binds to a different overload from
`String.valueOf(Object)`, so removing it can change output — and it moves the call-count signal by
an amount indistinguishable from a deleted `close()`. This normalisation lives in the comparator
and must never reach the source.

---

## A note on `localNN`

`localNN` labels are sometimes cited as a decompiler output pattern. In the corpus this
documentation came from they do not appear: the decompiler emitted `varN` for locals. If you are
looking at output containing `localNN`, it is a different tool or a different version, and the
specifics should be re-derived rather than assumed.
