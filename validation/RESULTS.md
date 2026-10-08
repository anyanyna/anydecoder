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
validation/scripts/run-corpus.sh <corpus>/support/classes.backup <corpus>/src/main/webapp/WEB-INF/lib
```

| Stage | Result |
|---|---|
| classes found | 2984 |
| source files decompiled, CFR | 2451 |
| dependency jars used as classpath | 236 |
| decompiler banner removed | 2451 files |
| CJK escapes decoded | 545 files |
| `blockNN` labels removed | 12 files |
| compiled | 2395 of 2451 files, in 23 peel rounds |
| **substituted from Vineflower** | **351 files** |
| set aside, needing repair | **56 files** |
| classes reached by the gate | 2826 of 2984 |
| classified safe | 2601 |

The 56 files still set aside are ones **both** engines failed to structure — CFR's
`'catch' without 'try'` and `'finally' without 'try'`, and whatever Vineflower did with the same
input. This is what the original recovery spent most of its nine days repairing by hand. They are
reported as a distinct worklist and are **not** counted as passes.

### What the second engine bought

| | without fallback | with fallback |
|---|---:|---:|
| files the rebuild could use | 2100 | 2395 |
| files set aside | 351 | 56 |
| classes reached by the gate | 2336 | 2826 |

Running two engines had been treated as cross-checking only. It is worth more than that:
decompilers fail on different inputs, and the file one cannot structure is often the one the other
handled. Discarding that tree after using it as a second opinion left 351 files in the repair
queue that did not need to be there.

The peel needs 23 rounds because javac attributes nothing while any file fails to parse, so each
round can only see the errors the previous round's removals exposed. Raising `-Xmaxerrs` from its
default of 100 took this from more than twenty rounds of a hundred files each to two rounds
followed by a long tail.

### The six known defects

Four of the six show up in the run: `DisplayUtil`, `ReportServiceImpl`, `FieldNameTest` and
`MatchMatrixRender`.

`AbstractMessageDriver` does not, and the reason is worth recording. CFR dropped the class's
constructor again — the same failure the original recovery found. But the recovery ran CFR
**without a classpath** in 42% of files, and this run gives it one, so the constructor was the
only thing missing and javac synthesises a default constructor to fill the gap. The default has no
`throws` clause where the original declared `throws BOException`, and a `throws` clause is not
part of a method descriptor — `void f()` and `void f() throws IOException` share one — so every
signal was blind to it.

The consequence is real: a subclass may not declare a checked exception its superclass does not,
so losing one on a base-class constructor leaves every subclass that declares it unable to
compile. A `throws` signal was added as a result, advisory because the clause cannot affect
runtime behaviour, and the report now lists the classes affected.

## Reproducing

Neither corpus nor compiled historical source is vendored here. Point the scripts at your own
copy; `--deps` takes a directory of jars. Expect roughly half an hour for the full pipeline,
dominated by decompilation.
