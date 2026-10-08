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

The CLI must be built:

```bash
mvn -q package -DskipTests
```

If the machine's Maven is pointed at an unreachable internal repository, add
`-s <path-to-a-settings-file-pointing-at-Maven-Central>`.

Locate the jar at `anydecoder-cli/target/anydecoder.jar`. All commands below assume
`java -jar <that jar>`.

## The loop

### 1. Run the pipeline

```bash
java -jar anydecoder-cli/target/anydecoder.jar run <classes-or-jar-or-war> \
  -o <output-dir> \
  --deps <directory-of-dependency-jars>
```

Add `--deps` for every jar the project compiled against. Discovery finds a `lib/` sitting beside
the classes, but a project whose jars live elsewhere needs to be told, and without them the
rebuild fails on symbols that were never missing — producing hundreds of errors that say nothing
about the decompiled source.

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

### 5. Report

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
