# Validation against a real corpus

Unit tests prove the gate does what its author intended. They cannot prove the tolerances are
right, because the tolerances exist to accommodate a compiler that no test fixture reproduces.

The only way to check that is to run anydecoder over a project whose original class files still
exist and see whether the verdicts hold up.

## The corpus

The method here was developed during the recovery of a production Java web application whose
source had been lost:

| | |
|---|---|
| class files | 2984 |
| recovered source files | 2451 |
| dependency jars | 236 |
| original compiler | Eclipse (ecj), class major 52 |
| rebuild compiler | javac |

That project's `classes.backup` directory is the ground truth. Its results, established by nine
days of manual examination *independently of this tool*:

- **2399 files (97.9%)** matched on every signal
- **52 files (2.1%)** diverged, and every divergence was traced to a named compiler artefact
- **6 files** contained real defects, each identified and fixed

Those six are the acceptance criterion that cannot be gamed, because they were found by people
looking at bytecode by hand before anydecoder existed:

| Class | What was wrong |
|---|---|
| `DisplayUtil.write` | a `try`/`finally` rendered as try-with-resources |
| `Util.writeJson` | the same |
| `ReportServiceImpl.getReportDatas` | the whole `try`/`catch` dropped |
| `FieldNameTest.testBadFieldNameAccepted` | an empty `finally` block dropped |
| `AbstractMessageDriver` | a constructor dropped |
| `MatchMatrixRender` | a constructor's visibility changed |

## Running it

The corpus is roughly 200 MB and is not vendored into this repository. Point the run at your own
copy:

```bash
java -Xmx4g -jar anydecoder-cli/target/anydecoder.jar run \
  <corpus>/support/classes.backup \
  -o validation/out/consolidation \
  --deps <corpus>/src/main/webapp/WEB-INF/lib
```

Budget about half an hour: decompiling 2984 classes dominates, and Vineflower is the slow half.

## Reading the result

`validation/out/consolidation/report.json`.

**What a good run looks like.** The six classes above appear in `needsReview` or are explained.
The safe rate is high — 97% and up. Anything lower means a tolerance has gone wrong and is
producing false positives, which is the failure this corpus is here to catch.

**A superset is acceptable.** anydecoder has signals the original toolchain did not: `fields` and
`consts` are new, and `invokes` compares call targets rather than counts. Finding divergences the
manual examination did not is expected, provided each one is explainable. What would be a problem
is an *inability to explain* them.

**Coverage is not optional.** Classes under `notReached` were never compared. A run reporting
nothing wrong over 40% coverage has established almost nothing, and the report says so.

## What this corpus cannot tell you

It is one project, from one compiler, in one style, using one Java version. A tolerance calibrated
entirely against it may be too loose or too tight for code built with a different compiler, or with
heavy generics, or with lambdas — that corpus used almost none.

If you have a project with original class files and recovered source, running it and reporting
what happens is one of the most useful contributions available.
