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

## 3. The repair loop, on files the peel could not use

Three of the 56 files the rebuild set aside were handed to a repair agent, one per distinct kind
of failure. All three were fixed, compiled, and came out `EQUIVALENT`.

| File | What was wrong | The fix |
|---|---|---|
| `FormViewController` | the decompiler dropped a `checkcast java/lang/String`, leaving an `Object` argument that made `StringBuffer.append` ambiguous between `CharSequence` and `char[]` | restored the cast the original had at that call |
| `FormDataDAO` | two faults: a dropped `Integer.valueOf` left `int == null`, and CFR had placed an `if (add)` block inside the inner loop, emitting a phantom return on the loop's exit path | restored the boxing; moved the block after the loop and removed the phantom return |
| `ViewSetController` | CFR folded a hand-written `Iterator` loop with an explicit `checkcast Value` into an enhanced-for, so `Vector<IMember>`'s element type bound directly to a `Value` variable | restored the explicit iterator and cast |

Each diagnosis came from the original bytecode — a `checkcast` present in the original and absent
from the source, a boxing call present in the original, an `LocalVariableTable` entry for an
iterator that an enhanced-for would not have produced — rather than from reading the source and
guessing. The gate confirmed each one afterwards, which is the point: the model's confidence is
not evidence, the rebuild is.

The patterns are now in the repairer agent's table, since all three recur across a tree.

## 3b. Adjudicating every damaged class

Every class the gate reported `DAMAGED` was investigated individually against the original
bytecode, by six agents working in parallel, one class at a time, with the requirement that an
artefact verdict name the mechanism and show it present.

**47 of 54 classes adjudicated. Five are real defects.**

| Class | What is wrong |
|---|---|
| `ApplicationBO.initApp` | a `try`/`catch` the original does not have; failures are swallowed and start-up continues half-initialised |
| `MainController.initUserMenu` | an added `&& className.contains(".")` |
| `MainController$FileLogOffListener.onEvent` | an added SSO branch that redirects and returns |
| `DraftModelBuilder.alignToModel` | `ArrayList.remove(int)` became `remove(Object)` — by index became by value |
| `CommandDefineViewModel.toExcel` | `"2007".equals(version)` became `version.equals(version)`, a tautology |

Two of the five are in `MainController.java`'s authentication paths, which is where the original
hand verification was thinnest.

**What this says about the tool.** Four of the five are invisible to anything that compares call
counts, field accesses or signatures — a handler added around an existing call, a constant
replaced by a variable, an overload resolved differently. They are the same family as the six
defects the original recovery found, and they were found the same way: by comparing structure
rather than reading.

**What it says about the report.** Every one of the remaining 42 adjudicated classes was a
compiler difference, and they reduce to about nine mechanisms — receiver narrowing, an implicit
null check in two encodings, boxing materialisation, a store duplicated into each conditional arm,
synthetic accessor renumbering, adjacent string folding, an elided checkcast, increment folding,
loop rotation. That is the reason the review list is worth grouping by cause rather than listing
alphabetically: nine explanations cover forty-two entries.

## 4. Adjudication

The two classes with the most signals firing at once were investigated in depth. One real defect,
one false positive. Both write-ups live in the run's own output directory, which is not tracked --
they are produced from a third party's compiled code and describe it. The real defect is set out
in full below, since the reasoning is what generalises.

The real defect — a `try`/`catch` in the recovered source that the original does not have, so
failures are swallowed instead of aborting start-up — is the kind the hand verification that
produced this tree could not have found, because it had no field or constant signal and compared
call counts rather than targets.

The false positive is a compiler-version difference: the original was built by an older javac
whose try-with-resources encoding emits paired `Throwable`/`any` handlers and pre-initialised
exception temporaries, which javac 17 does not produce at any target level. The agent established
this by reproduction rather than inference: compiling the recovered structure at every target
level reproduced the rebuild's exception table offset for offset, while neither javac 17 nor ecj
3.44 produces the original's shape.

That last point casts doubt on the project's own record that these originals came from the Eclipse
compiler. The dual-handler try-with-resources encoding and the two-return boolean layout are javac
8 signatures, and ecj 3.44 produces neither. The compiler's identity is therefore not settled,
which does not change what the tolerances must accommodate — differences between whichever
compiler built the original and whichever is doing the rebuild — but the `ECJ_*` category names
should be read as inherited labels rather than a verified attribution.

## 5. Applying the fixes to the recovered tree

The twelve defects were fixed in the source tree itself, on a branch, with the gate re-run after
each batch. The tree is compiled with **JDK 8** for this — the compiler generation that built the
originals — which is a sharper check than the `--release 8` on a newer JDK used elsewhere here:
the closer the rebuild compiler is to the original's, the less noise there is to see past.

| | before | after |
|---|---:|---:|
| EQUIVALENT | 2803 | **2818** |
| DAMAGED | 45 | **28** |
| SUSPECT | 81 | 88 |

| Fix | Verdict after |
|---|---|
| `ApplicationBO.initApp` — removed the `try`/`catch` | EQUIVALENT |
| `MainController.initUserMenu` — removed the added `contains(".")` | EQUIVALENT |
| `MainController$FileLogOffListener.onEvent` — removed the added SSO branch | EQUIVALENT |
| `CustomUserService` — removed the added branch and the added method | EQUIVALENT |
| the three `$ResultRender` — restored the explicit `public` constructor | EQUIVALENT |
| `ExportStructureChartHelper$NodeMaxXAndMinX` — restored the explicit `public` constructor | EQUIVALENT |
| `ElimruleController` — removed two dead boxed locals | SUSPECT¹ |

¹ The remaining finding on the four still-flagged classes is the ternary field-store artefact:
`this.x = cond ? a : b` compiles to one store per arm in the compiler that built the original and
one store after the join in javac 8. It cannot be matched without rewriting the ternary into an
`if`/`else` purely to please the comparator, which the original toolchain explicitly declined to
do and this one does too.

**Two bugs in the tool were found by doing this work**, both by looking at what it printed rather
than by reasoning about it:

- The field-store-hoisting rule excused a divergence whenever the loop over field *writes* had
  nothing to iterate — a vacuous check running none of its guards and then granting the exemption.
  A silent pass on evidence that did not exist. Fixed by requiring the check to have examined
  something.
- Compiler-generated method bodies were being compared. A synthetic accessor's instruction
  sequence is the compiler's choice, and the two compilers order the underlying loads and casts
  differently, so comparing them produced findings about nothing. They are now skipped by name as
  well as by flag.

Both were latent until the tree was compiled with its original compiler, where the remaining
differences are few enough that a wrong one stands out.

## 6. Working the list down, and the four mechanisms it produced

The recovered source was gated against the originals on **commit `0e91a4ef`** — the tree as
recovered, before any hand-editing — compiled with JDK 8, the compiler generation that built the
originals.

| | start | end |
|---|---:|---:|
| EQUIVALENT | 2820 | 2820 |
| BENIGN | 28 | 43 |
| SUSPECT | 86 | 87 |
| **WAIVED** | — | **2** |
| **DAMAGED** | **28** | **10** |

Four mechanisms accounted for most of the reduction, and each became a gate category or a
normalisation rather than a per-class waiver:

| Mechanism | What it costs if not handled | Classes |
|---|---|---:|
| Adjacent string literals folded | one cause arriving as `consts`, `invokes` and `instrCount` — three investigations | 12 |
| An appended empty literal | `append("")` cannot change the result, but the newer compiler emits it | — |
| Boxing alongside a narrowed receiver | the boxing rule compared raw target strings, so a `List`/`ArrayList` receiver counted as a difference and the rule gave up | 10 |
| `String.valueOf(primitive)` against `String.valueOf(Object)` | a boxed local turns one into the other; same string either way | 1 |

The first two share an arithmetic: every fold removes two instructions, every empty append adds
two, and the rule refuses unless the delta matches exactly. That is what keeps it a test rather
than a resemblance.

**Eight real defects** were found, all confirmed by reading the bytecode: a tautological
`version.equals(version)`, a `remove(int)` that became `remove(Object)`, four nested classes whose
explicit `public` constructor was dropped, a log literal truncated from `集合深拷贝错误:` to
`深拷贝错误:`, and a dead local the decompiler hoisted out of a `synchronized` monitor expression.

**All eight are still reported at every stage of this work.** That is the property that had to
survive: a category that accounts for more is a category that can hide more, so each new one was
tested against a case it must not excuse — folding refused when the instruction arithmetic does
not fit, a changed literal not excused by a merge, boxing refused when a call genuinely moved.

Two classes were waived rather than fixed, with the mechanism and the observation recorded: a
static call made through an instance expression whose qualifier javac evaluates and discards, and
a rotated loop. One class is left **unresolved** — its count differences are consistent with a
`finally` body inlined once per completion path, and consistent is not the same as checked, so it
stays on the list.

## 7. End to end, with everything wired up

The whole pipeline over 2984 classes, both engines, with the peel, the engine fallback, the gate,
the substitution pass and the reporting all running:

| | |
|---|---|
| classes found | 2984 |
| decompiled by CFR | 2451 |
| submitted to the gate | 2824 (94.6%) |
| classified safe | 2770 (98.1% of those reached) |
| need review | 54 |
| reached by neither engine as compilable source | 55 |
| not rebuildable on a modern JDK | 2 |

Verdicts: EQUIVALENT 2717, BENIGN 53, SUSPECT 3, DAMAGED 51, not reached 160.

Compiler artefacts accounted for, by category: `FIELD_STORE_HOISTING` 37, `ENUM_CLINIT_CODEGEN` 12,
`STRING_LITERAL_FOLDING` 6, `BOXING_MATERIALIZATION` 5, `ECJ_NO_IINC_OPTIMIZATION` 3. Every one of
those was a cause that from the outside looks like several separate findings.

### The engine substitution earned its place

The pass that offers each class the gate was unhappy with to the other engine took the review list
from **184 to 54**. It attempted 129 classes and **every one improved** — the first evidence that
running a second engine for its *rendering* rather than only as a cross-check is worth the time it
costs. Previously the second tree was used only to rescue files that would not compile.

That figure also corrects an assumption: the two engines were being treated as having comparable
quality with different failure modes. On this corpus Vineflower's rendering was strictly better on
every class where they differed enough for the gate to notice.

### What the 54 are

Raw decompiler output, so the largest group is what one would expect: **31 classes with a changed
exception table**, where the handler-group counts moved — three groups to five, three to two. That
is the shape of a `try`/`finally` restructured, which is a real difference in behaviour and the
reason the exception table is the gate's hard signal.

Then `invokes` 26, `fields` 7, `consts` 4, `athrow` 2, `instrCount` 2. None of them improved under
the other engine's rendering, so they are the genuine repair worklist for a CFR-only tree.

## Reproducing

Neither corpus nor compiled historical source is vendored here. Point the scripts at your own
copy; `--deps` takes a directory of jars. Expect roughly half an hour for the full pipeline,
dominated by decompilation.
