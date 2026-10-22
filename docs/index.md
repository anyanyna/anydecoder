# Decompiled code always looks correct. Here's how to prove it isn't.

[中文](index.zh.md) · [Repository](https://github.com/anyanyna/anydecoder)

Source gets lost. Build outputs get archived, the repository does not, and one day what you have
is a directory of `.class` files and no way back.

So you run a decompiler, and it hands you something that looks like the program. This is the trap,
and it is worth being precise about why: **a decompiler's output always looks correct.** It is
valid Java. It has sensible variable names where the debug info survived. It reads like something
a person wrote, because a person did write the shape it is reconstructing.

The problem is that "looks correct" and "is correct" come apart in ways you cannot see by reading.

## What that costs, on a library you have used

I ran my tool over Apache Commons Lang 3.14.0 — 404 class files, a library that is inside a very
large fraction of all Java applications ever deployed. About thirty seconds.

Of the 247 source files it recovered, **nine do not compile at all.** Not "compile to different
bytecode" — do not compile. Here is what `javac` said, in its own words:

```
ArrayUtils.java                    method getComponentType in class ClassUtils cannot be applied
                                     to given types; required: Class<T[]>, found: Class<T>
EnumUtils.java                     incompatible types: Enum cannot be converted to E
ObjectUtils.java                   clone() has protected access in java.lang.Object
ThreadUtils.java                   reference to findThreads is ambiguous; both
                                     findThreads(Predicate<Thread>) and
                                     findThreads(ThreadUtils.ThreadPredicate) match
FieldUtils.java                    reference to readField is ambiguous; both
                                     readField(Field, Object, boolean) and
                                     readField(Object, String, boolean) match
StrBuilder.java                    method does not override or implement a method from a supertype
AbstractConcurrentInitializer.java unreported exception capture#1 of ? extends Exception
ExceptionUtils.java                unreported exception java.lang.Throwable
module-info.java                   modules are not supported in -source 8
```

A decompiler alone hands you all nine and says nothing about any of them. The files have the right
names and the right package structure. You find out when you try to build — which, if you are
recovering a project whose build you have already lost, may be a while later.

Those nine files are 3.6% of the tree. That is the number nobody puts on the box.

## And the ones that compile are not automatically fine

Nine files failing to compile is the loud failure. The quiet one is worse, because nothing tells
you it happened.

Among the recovered files that compile perfectly, the check flagged
`org.apache.commons.lang3.reflect.TypeUtils`. The recovered source is here, and you should read it
as Java:

```java
if (isAssignable(midClass, superClass) && isAssignable(genericInterface, midClass)) {
```

It compiles. It reads correctly. It does what it looks like it does.

It also calls a **different method** than the original did. Here is the original source of Commons
Lang 3.14.0:

```java
if (isAssignable(midClass, superClass)
        && isAssignable(genericInterface, (Type) midClass)) {
```

There it is: `(Type) midClass`. The author wrote an explicit cast, because `midClass` is declared
`Class<?>` and without the cast `javac` picks the narrower overload. The decompiler dropped the
cast.

You can watch it happen. Disassemble the original and the call target is right there:

```
109: invokestatic  // Method isAssignable:(Ljava/lang/reflect/Type;Ljava/lang/Class;)Z
118: invokestatic  // Method isAssignable:(Ljava/lang/reflect/Type;Ljava/lang/reflect/Type;)Z
```

Two different methods, at two adjacent offsets. The rebuilt code calls the first one twice.

### Now the part that matters

I am not going to tell you Commons Lang is broken, because I do not know that and neither does the
tool. In this case the two overloads agree: `isAssignable(Type, Type)` delegates to
`isAssignable(Type, Type, Map)`, which dispatches straight back to `isAssignable(Type, Class)` when
the target is a `Class`. So the recovered source probably behaves identically — and you can check
that in the source above rather than taking my word for it.

What the tool reports is narrower, and it is a fact rather than a judgement: **the recovered source
calls a different method than the original did.**

The reason that is worth a report rather than a shrug is this: nothing in the bytecode separates

> a cast was dropped, and it happens to be harmless

from

> a cast was dropped, and the program now does something else.

Both look exactly like this. And the second one is not hypothetical — it is what `ThreadUtils` and
`FieldUtils` are, in the list above, where the same kind of lost type information turned two
overloads into an ambiguity that does not even compile.

A decompiler cannot tell you which side of that line you are on. That is not a criticism of
decompilers; it is a statement about what information is in a class file. The information needed to
tell the difference was never there.

## The check

So do not ask the decompiler. Ask the compiler.

> **decompile → rebuild → compare the rebuilt bytecode with the original**

Recompile the source the decompiler produced, then compare the result against the class file it
came from, method by method: the exception table, the call targets, the field accesses, the
constants, the rethrow counts. If the rebuild does the same things the original class file does,
the source it came from is faithful. That is a mechanical check, not a matter of opinion, and it
does not depend on anyone reading anything.

I built this into a tool called anydecoder, and the run above is what it prints. On that
commons-lang3 run: 384 of 404 classes reached, 375 classified safe, 9 needing review, 20 the check
could not reach at all.

**The 20 are not passes, and the tool says so in those words.** They are classes with no
counterpart in the rebuild: the module descriptor, which cannot be built at release 8, and the
classes from the eight files that would not compile. (Eighteen `package-info` classes used to be
in that list; the tool now emits them by adding a source-retention annotation when the original is
an empty synthetic interface.)
Coverage is printed next to every verdict, every time, because a pass rate without a coverage
number is a number that means nothing.

## What it does not claim

This is the part I care most about getting right, because a checker that overstates itself is worse
than no checker — you stop looking.

- **It does not claim the source is what the author wrote.** Variable names, comments, formatting,
  whether a loop was a `for` or a `while` — none of that is in the bytecode and none of it is
  claimed. Recovering "the original source" is not a thing that can be done.
- **It does not claim a class it did not reach is fine.** See above.
- **It does not excuse a difference it cannot explain.** This is the design rule the whole thing
  rests on. There is a list of compiler behaviours it recognises — the way one compiler materialises
  a boolean, the way another splits an exception region — but each one has to name the structural
  pattern that produces the difference *and* show that the pattern is present. There is no fallback
  to "probably harmless", no confidence threshold, no category that fires because two numbers are
  close. A difference nothing recognises stays a difference and reaches a human.
- **It compares structure, not behaviour.** Two independent statements of the same kind swapped in
  order produce identical bytecode signals. There is an optional differential test that runs both
  versions and compares what they do, which closes that gap where a method is drivable, and it is
  off by default because it executes real code.

## Using it

It ships as a skill for a coding agent, because the mechanical check gets you a worklist and not a
finished job. On the commons-lang3 run it produced nine classes to look at, each with the signal
that fired and the evidence for it — and working through that list means reading disassembly,
deciding, and trying again. That is exactly the work an agent is good at and a shell script is not.

Two steps. Paste this into Claude Code, Codex, Cursor, Trae, or anything else that reads the
[Agent Skills](https://agentskills.io) format:

```
Install anydecoder.

1. Download the CLI. It bundles the decompilers, so there is nothing to build:
     mkdir -p ~/.anydecoder
     curl -L -o ~/.anydecoder/anydecoder.jar \
       https://github.com/anyanyna/anydecoder/releases/latest/download/anydecoder.jar
2. Install the skill into your own skills directory, so you pick it up in this and future
   sessions. It is one file:
     mkdir -p <your-skills-dir>/anydecoder
     curl -L -o <your-skills-dir>/anydecoder/SKILL.md \
       https://github.com/anyanyna/anydecoder/releases/latest/download/SKILL.md
3. Confirm it runs: java -jar ~/.anydecoder/anydecoder.jar --version
```

Then ask it for what you want:

```
Recover the source from build/classes and prove it's faithful.
```

You need a JDK 17+ to run it. Nothing else.

**https://github.com/anyanyna/anydecoder** — Apache 2.0.

---

*The method came out of a nine-day recovery of a production Java web application whose source had
been lost: 2,984 class files, 2,451 recovered source files, originals compiled by the Eclipse
compiler and rebuilt with javac. That effort found six real defects among files a compiler-based
comparison could not distinguish. Re-running the same corpus through this check found eight,
including a log message truncated from `集合深拷贝错误:` to `深拷贝错误:` and a dead local the
decompiler had hoisted out of a `synchronized` monitor expression. It also turned up four bugs in
the checker itself, two of them silent — the check needed checking too. All of it is in the
repository.*
