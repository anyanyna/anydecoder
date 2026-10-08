# Measured results

Two runs, recorded because the numbers are the argument. Environment: JDK 17, Windows, the
corpus described in [README.md](README.md).

## 1. The gate against source already known to be faithful

The strongest available test is not to run anydecoder on raw decompiler output — it is to run it
on source whose fidelity has already been established by other means.

That source exists. The consolidation project's `src/main/java` was verified by nine days of
manual bytecode examination and rebuilt to zero compiler errors before anydecoder existed. Its
answer is known: **every file faithful**.

```
anydecoder gate <corpus>/support/classes.backup <compiled-historical-src> --deps <corpus>/lib
```

| Verdict | Count |
|---|---:|
| EQUIVALENT | 2795 |
| BENIGN | 38 |
| SUSPECT | 75 |
| DAMAGED | 54 |
| HARNESS_ERROR | 22 |

**94.7% of classes matched on every signal.** The 22 unreached are two source files using
`sun.misc.BASE64Encoder`, which `--release 8` hides on a JDK 17 build; their classes are absent
from the rebuild.

### What the 129 review findings are

This is the number to be honest about. The historical toolchain reported zero, and anydecoder
reports 129. The difference is not that anydecoder is finding defects the other missed — it is
that anydecoder looks at things the other did not:

| Signal | Findings | Why the historical gate could not see it |
|---|---:|---|
| `fields`, count difference | 75 (SUSPECT) | There was no field signal at all. |
| `invokes` | 53 | It compared call *counts*; these differ in call *target*. |
| `fields`, set difference | 23 | As above. |
| `consts` | 17 | There was no constant signal. |
| `api` | 7 | Remaining anonymous-class and synthetic-member cases. |
| `members`, `instrCount`, `excp`, `athrow` | 9 | |

The 75 `fields` findings are all the same shape, and it is worth stating precisely because it is
the largest single number here. For `this.x = cond ? a : b;`, the Eclipse compiler duplicates the
`putfield` into each arm of the conditional while javac emits one after the branches join. The
original therefore writes the field more times than the rebuild does, to the same field, on
mutually exclusive paths. It is benign — and proving that requires control-flow analysis, which
the gate does not do. So it is reported as `SUSPECT` with the count difference named, rather than
silently excused. Excusing it on a heuristic would be the one thing this design refuses to do.

### How this run changed the code

Every one of the figures above is an improvement on an earlier run of the same test, and each
step was a real defect in the gate rather than in the source under test:

| Run | EQUIVALENT | DAMAGED | Cause of the change |
|---|---:|---:|---|
| first | 2269 | 668 | — |
| after hierarchy-aware owners | 2686 | 248 | `List.add` and `ArrayList.add` are the same member; so is a field reached through a narrowed type. Taking owners literally was 1457 findings. |
| after filtering synthetic members | 2697 | 225 | javac from JDK 15 emits a synthetic `$values()` for every enum; javac 8 did not. |
| after enum initialiser classification | 2783 | 66 | ecj and javac build an enum's constant table differently, by more than a quarter of the instructions. |
| after covariant return types | 2783 | 58 | `CloseableHttpClient.execute` returns a subtype of `HttpClient.execute`'s return type; it is the same method. |
| after anonymous-class `final` | 2791 | 58 | ecj marks anonymous classes `ACC_FINAL`, javac does not. |
| after separating count from set | 2795 | 54 | A field referenced more often is not the same claim as a different field referenced. |

That table is the reason the corpus is worth keeping. Four of those seven were invisible to unit
tests, because they are properties of a compiler no test fixture reproduces.

## 2. Raw decompiler output, end to end

```
anydecoder run <corpus>/support/classes.backup -o out --deps <corpus>/src/main/webapp/WEB-INF/lib
```

| Stage | Result |
|---|---|
| classes found | 2984 |
| source files decompiled by CFR | 2451 |
| dependency jars used as classpath | 236 |
| decompiler banner removed | 2451 files |
| CJK escapes decoded | 545 files |
| `blockNN` labels removed | 12 files |
| compiled | 2100 of 2451 files, in 21 peel rounds |
| removed as uncompilable | 351 files |

The 351 files that will not compile are CFR's structure-collapse failures — `'catch' without
'try'`, `'finally' without 'try'`, `illegal start of expression` — which is what the original
recovery spent most of its nine days repairing by hand. They are reported as a distinct worklist
and are **not** counted as passes.

The peel needs 21 rounds because javac attributes nothing while any file fails to parse, so each
round can only see the errors the previous round's removals exposed. Raising `-Xmaxerrs` from its
default of 100 took this from more than twenty rounds of a hundred files each to two rounds
followed by a long tail.

## Reproducing

Neither corpus nor compiled historical source is vendored here. Point the commands above at your
own copy; `--deps` takes a directory of jars. Expect roughly half an hour for the full pipeline,
dominated by decompilation.
