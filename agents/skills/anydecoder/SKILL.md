---
name: anydecoder
description: Recover Java source from class files and prove the recovered source behaves like the original bytecode. Use when source has been lost and only .class files or a jar/war remain, when asked to decompile a Java project, or when asked to check whether decompiled source is faithful. Triggers on "recover source from class files", "反编译", "decompile this jar", "we lost the source", "source is gone", "verify the decompiled code", "restore java source".
---

# Recovering Java source, and proving it

You are driving `anydecoder`. The tool does the mechanical work; your job is to resolve what it
cannot, and to be honest about what remains unresolved.

## The one idea that matters

A decompiler guesses. You do not have to trust the guess, because it can be checked:

> **decompile → rebuild → compare the rebuilt bytecode with the original**

If the rebuild does the same things as the original class file, the source it came from is
faithful. This is a mechanical check, not a judgement call. Never assert that recovered source is
correct on the strength of how it reads.

Corollary: **the compiler is the authority, not you.** If you rewrite a method and think it looks
right, that means nothing until the gate says the bytecode still matches.

## Setup

Find the CLI jar before anything else. Look in this order:

1. `~/.anydecoder/anydecoder-cli/target/anydecoder.jar` — where the install instructions put it.
2. Any other path for it you have already established in this session.
3. Ask the user where the checkout is.

If there is no checkout, or the jar is missing, get one and build it:

```bash
git clone https://github.com/anyanyna/anydecoder ~/.anydecoder
cd ~/.anydecoder && mvn -q package -DskipTests
```

JDK 17+ is required, and the decompilers are bundled into the jar so nothing else has to be
installed. If the machine's Maven is pointed at an unreachable internal repository, add
`-s <path-to-a-settings-file-pointing-at-Maven-Central>`.

All commands below assume `java -jar <that jar>`.

## The loop

### 1. Run the pipeline

```bash
java -Xmx8g -jar anydecoder-cli/target/anydecoder.jar run <classes-or-jar-or-war> \
  -o <output-dir> \
  --deps <directory-of-dependency-jars>
```

**Give it memory.** Compiling the decompiled tree happens in-process on the whole tree, and the
peel repeats that until it converges, so the peak is a property of the project and not of the
largest class. A few thousand classes needs more than 4 GB, and under-provisioning does not fail
cleanly — the run dies one class at a time with an error about a class file rather than about
memory. If the report says a class failed because the run ran out of heap, raise `-Xmx`.

Add `--deps` for every jar the project compiled against. Discovery finds a `lib/` sitting beside
the classes, but a project whose jars live elsewhere needs to be told, and without them the
rebuild fails on symbols that were never missing — producing hundreds of errors that say nothing
about the decompiled source.

Each decompiler is a separate process with its own heap, set with `-Danydecoder.engine.heap`.
Vineflower builds a whole-project view and wants more than the others. An engine that exhausts its
memory is reported and stepped over rather than ending the run, so check the report's *Engines
that failed* section before trusting a verdict — a tree from the second choice is not the same as
one from the first.

Read `<output-dir>/report.json`. Its `coverage` block tells you how much of the input the gate
actually reached. **A low coverage number invalidates any conclusion drawn from the verdicts.**

### 2. Work the list

For each entry in `needsReview`, dispatch the `decompile-repairer` agent. In parallel — each
class is independent, and the gate re-checks everything at the end anyway.

Do not start with the largest class. Start with the ones whose `findings` mention `excp` or
`athrow`: a wrong exception table is a real behavioural difference, while a `instrCount` finding
is often a compiler artefact that will turn out to be benign.

### 3. Re-gate

After repairs, recompile and re-gate just what changed:

```bash
javac -g -d <classes-dir> -cp "<deps>:<original-classes>" @<sources-list>
java -jar anydecoder-cli/target/anydecoder.jar gate <original-classes> <classes-dir> --format json
```

Never accept a repair on inspection. Re-run the gate.

### 4. Triage what is left

Some findings will not be repairable — the decompiler produced something that cannot be
expressed in source while matching the original bytecode. Dispatch `divergence-triage` to decide
whether each is a real defect or an artefact, and **require the structural evidence**. "It looks
equivalent" is not an answer this tool accepts.

### 5. Record what is accepted

Some divergences are correct and permanent — source deliberately changed after recovery differs
from the original by design, and no amount of investigating changes that. Left unrecorded, the
gate keeps naming classes somebody has already cleared, which trains its readers to ignore it.

Write them into `anydecoder-waivers.md` beside the class files (or point at one with `--waivers`),
as a Markdown table. **Every entry must state why and cite evidence, or it is refused and
reported.** Matching is per class *and* per signal, so waiving one difference never hides another.
A class whose remaining differences are all waived is reported `WAIVED` — safe, and deliberately
distinct from `BENIGN`, because one is a fact about compilers and the other is a decision that can
be argued with.

### 6. Report

State coverage and the verdict counts. Name the classes that need review. Never report a pass
rate without the coverage it was computed over, and never describe a class as verified unless the
gate reached it.

## What must never be claimed

- That the recovered source is what the author wrote. Variable names, comments, formatting and
  loop style are not in the bytecode.
- That a class is correct because the gate did not reach it.
- That an unexplained divergence is harmless because it is small.
- That a `BENIGN` verdict means the source is nicer, or that it is the same text as the original.
  It means a specific, named compiler artefact accounts for a specific difference.

## Things that will bite you

- **`blockNN:` labels are not a bug.** They are valid Java that behaves identically to the
  structure the author wrote. Do not "fix" them expecting a verdict to change; fix them for
  readability, then let the gate confirm nothing moved.
- **Never rewrite `String.valueOf(x) + y` into `x + y`.** `String.valueOf(char[])` binds to a
  different overload from `String.valueOf(Object)`, so the change can silently alter output.
- **A decompiler that renders a `finally` as try-with-resources has changed behaviour**, not just
  shape: try-with-resources adds suppression semantics. This is one of the most common real
  defects and the gate catches it via the exception table.
- **The same class directory can resolve differently under two classpaths** when jars overlap.
  If a rebuild fails to find a symbol that clearly exists, check for duplicate jars before
  suspecting the source.
- **Compile at the original's Java release**, never a newer one. From Java 9, string
  concatenation becomes `invokedynamic`, and every concatenating method would then differ for
  reasons unrelated to the source.

## Commands

| Command | Use |
|---|---|
| `run <input>` | whole pipeline: decompile, clean up, rebuild, gate, report |
| `gate <original> <rebuilt>` | re-check without decompiling; the inner loop of a repair |
| `gate ... --format json` | machine-readable findings, for driving repairs |
| `gate ... --verbose` | include advisory divergences and the reasons for exemptions |
| `difftest <original> <rebuilt>` | call both versions and compare what they do — the one thing the gate cannot see |
