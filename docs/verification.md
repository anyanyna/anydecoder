# How anydecoder decides

This document is the reasoning behind every judgement the gate makes. If you disagree with a
verdict, the answer to "why did it say that" is here.

## The claim

> Recompile the recovered source. If the result does the same things as the original class file,
> the source is faithful.

Everything follows from taking that seriously. It means the question "is this decompiled code
correct?" is replaced by the question "does this rebuild differ from the original?", which has a
mechanical answer.

## Why not just compare the bytes

The obvious approach — compare the rebuilt class with the original instruction by instruction —
does not work, and the reason is worth stating because it shapes everything else.

The originals were compiled by a different compiler from the one doing the rebuild. In the corpus
this method was developed on, they were built by the **Eclipse compiler (ecj)** and rebuilt with
**javac**. Two compilers given identical source emit different bytecode, always, for reasons that
carry no meaning:

- constant-pool indices are assigned in whatever order the compiler walks the tree;
- branch offsets depend on instruction widths the compiler chose;
- local variable slots come from the register allocator;
- `<clinit>` placement is a compiler convention — ecj emits it where the initialiser sits in the
  source, javac appends it last;
- synthetic accessor names differ (`access$0` from ecj, `access$000` from javac);
- string concatenation is built differently — for `String.valueOf(x) + y`, ecj emits
  `new StringBuilder(String.valueOf(x))` then one `append`, while javac emits
  `new StringBuilder()` then two;
- increment-by-one is folded into `iinc` by javac and not by ecj, turning one instruction into
  four;
- a boolean expression returned directly is materialised by ecj into
  `ifeq / iconst_1 / ireturn / iconst_0 / ireturn` where javac branches to the constant;
- a protected region in a `try` is segmented differently — ecj excludes trailing instructions
  that cannot throw, javac uses one continuous range.

A raw comparison reports all of this, and the volume buries anything real. Measured on one corpus,
a comparison that did not normalise the last of those reported **42 hard failures, 39 of which
were this single artefact.** Three real defects were hidden inside that noise.

So the gate does not compare bytes. It compares things that mean something, and it is careful
about which things those are.

## The signals

Every signal must be insensitive to a compiler's arbitrary choices and sensitive to a change in
behaviour. A signal that fires on an arbitrary choice costs a human an investigation; a signal
that stays quiet on a behaviour change costs correctness. Both are bugs, and both have happened.

### `excp` — exception table shape · hard

The most informative signal, and the one that catches the most consequential defects. It compares
a method's exception table normalised to a multiset of `(handler type, count of disjoint protected
groups)`.

What it catches:

- a `try` block the decompiler dropped entirely;
- a `finally` rendered as try-with-resources, which adds `addSuppressed` semantics the original
  never had;
- a handler whose exception type changed, so a different set of exceptions is caught;
- a `catch (Throwable)` narrowed to `catch (Exception)`.

**Why it is hard.** Measured on the corpus, this is the only signal that survived the javac/ecj
code-generation difference while still detecting structure. Everything a `finally` does is encoded
here and nowhere else.

**The normalisation, and why each part is needed.** An entry covering no throwing instruction is
dropped: it cannot affect behaviour. Entries are then grouped *per handler type*, counting how
many disjoint runs of protected code remain. Grouping must be per type and not in offset order —
a nested `try` lists its handler alongside the outer handler's, both beginning at the same offset,
so siblings of one type are never adjacent in the table and would never merge. Entries where
`from == target` are dropped: javac emits these to let an exception thrown inside a `finally`
re-enter it, ecj does not, and they say nothing about the source. A catch-all is canonicalised
with an explicit `catch (Throwable)`, because they are the same thing and the two compilers write
them differently.

### `athrow` — rethrow count · hard

Counts `athrow`. A rethrow rewritten into a jump is swallowed silently: the exception table still
looks right and the instruction delta is a couple of bytes, so every other signal stays quiet.
This was a real defect in the corpus, where a `continue` was moved into a `finally` and compiled
to a `goto`.

### `invokes` — call targets · hard

Compares the *multiset* of resolved callees — `owner.name:descriptor` — not a count. Comparing
counts was the original implementation and it only worked on methods under 300 instructions,
because on a large method a shifted invocation is invisible in a total. Comparing the set removes
that size caveat: a duplicated or missing `close()` is caught in a two-thousand-instruction method
too.

`StringBuilder` and `StringBuffer` calls are excluded, for the concatenation reason above. The
exclusion is recorded as an advisory `concat` signal so it appears in the report rather than
hiding inside a comparison.

Calls to `String.valueOf` are **not** excluded. They are semantic, and `String.valueOf(char[])`
binds to a different overload from `String.valueOf(Object)` — rewriting one into the other can
silently change output.

### `fields` — field accesses · hard, with a caveat about counts

Compares the resolved multiset of field reads and writes, with the opcode included so that
`getfield` and `getstatic` are distinguishable. Turning an instance access into a static one
compiles and changes which storage is touched.

**A field touched on one side only is hard. The same field touched a different number of times is
`SUSPECT`.** The distinction matters because of a specific compiler behaviour: for
`this.x = cond ? a : b;` the Eclipse compiler duplicates the `putfield` into each arm of the
conditional, while javac emits one after the branches join. The original therefore writes the
field more times than the rebuild, to the same field, on mutually exclusive paths.

That is benign — and proving it needs control-flow analysis, which the gate does not do. Reporting
it as `SUSPECT` rather than excusing it on a heuristic is the consistent choice. This is the
largest single category in the corpus results, and
[`validation/RESULTS.md`](../validation/RESULTS.md) says so with the counts.

### `consts` — pool-resident constants · hard

Compares pool-resident constants by **value**: string literals, class literals, method handles,
and numeric literals too wide to encode in an instruction. The pool index is stripped, because two
compilations of the same source assign different indices; the value is what carries meaning.

### `smallConsts` — constants encoded in the instruction · advisory

Compares the constants whose value lives in *which instruction appears* rather than in a pool
entry: `iconst_0` through `iconst_5`, the `lconst`/`fconst`/`dconst` forms, `aconst_null`, and
the `bipush`/`sipush` range.

**This signal exists because writing a test found a hole.** `return 1` and `return 2` were being
reported as equivalent. The two differ only in which of two instructions is present, and every
signal compared instructions by *kind* — so the same instruction appeared on both sides and
nothing looked further. A changed integer literal, in other words, was invisible.

Closing it is right; making it a finding is not, and three measurements were needed to accept
that.

- As a **hard** signal it produced 183 false positives on source already verified faithful by hand.
- As **suspect** it added about 150 classes to the review list. Still not actionable.
- The obvious discriminator — a changed *value* is real, a changed *count* is codegen — was
  tested and **rejected by the data**: 90 classes had a value present on one side only, and every
  one was a compiler choice about how to initialise a local or push a zero.

The mechanism is not characterised, and a difference that cannot be named cannot be excused under
any of the categories below — nor should it be asserted as a defect. So it is recorded and not
judged: a reader looking at the method sees the note, the verdict claims only what it can support,
and the review list stays actionable. The report groups findings by cause, so the note is read
once.

What this trades away is stated plainly: `return 1` becoming `return 2` would raise a note and not
a finding. The hard `consts` signal catches a changed *string*, class literal or wide numeric, so a
changed literal of those kinds still fails the gate; a changed small integer does not.

### `instrCount` — body size · suspect

Flags a method whose size changed well beyond what a compiler difference explains. The threshold
has a relative part (15%) and an absolute floor (6 instructions), and both are needed: a
relative-only threshold fires on tiny accessors, where nine instructions legitimately becoming
five is the boolean-materialisation artefact above.

Suspect rather than hard, because a large size change can be the compiler's own doing — an
unfolded increment costs three extra instructions each. The report says which.

### `throws` — declared exceptions · advisory

Compares each method's `throws` clause. Advisory, because the clause is compile-time only and
appears nowhere in the executed bytecode: it cannot change what the code does.

It is here because nothing else can see it. A `throws` clause is not part of a method descriptor —
`void f()` and `void f() throws IOException` share one — so a decompiler that omits a constructor,
or omits the exceptions one declares, produces source that matches the original on every other
signal. That happened on the validation corpus: CFR dropped an abstract class's constructor, javac
synthesised a default one, and the two were indistinguishable until the declared exceptions were
compared.

The consequence is concrete. A subclass may not declare a checked exception its superclass does
not, so losing one on a base-class constructor leaves every subclass that declares it unable to
compile. The report lists the affected classes in their own section rather than mixing them into
the review list, since a reader who finds advisory noise among real findings learns to ignore the
list.

### `lvt` — declared locals · advisory

Compares the name and type of declared local variables, ignoring slots. Advisory because a
decompiler routinely declares a narrower type than the original and that changes nothing.

It still earns its place as a review prompt: a local the original declared as `int` coming back as
`Integer` is the signature of an autoboxing defect that every other signal would miss.

Note that decompilers do not recover variable names — CFR emits `var1`, `var2`. Only the type
column carries information about the original.

## The classpath is part of the comparison

Two signals — `invokes` and `fields` — name a member by its owner as well as its name, and the
owner is exactly where a decompiler diverges from the source it came from.

Where the source said `List<String> items = new ArrayList<>();`, recovered source says
`ArrayList<String> items = new ArrayList<>();`, and the call that follows becomes
`invokevirtual ArrayList.add` instead of `invokeinterface List.add`. Same member, same descriptor,
receiver of a related type. And where an override returns a subtype —
`CloseableHttpClient.execute` returning `CloseableHttpResponse`, `HttpClient.execute` returning
`HttpResponse` — reaching the call through the concrete type names the override.

Taking either literally is not a small error. Measured on source that nine days of manual bytecode
examination had already verified as faithful:

| Comparison | Findings |
|---|---:|
| owner compared literally | 1325 |
| covariant return types also compared literally | +57 |

So the gate is constructed with a **hierarchy index** built from the same classpath the rebuild
used. When two owners differ, they are paired only if one is a subtype of the other; when two
return types differ, likewise. Only entries with no related counterpart are reported.

The index reads class headers only, never loads a class, and falls back to the platform class
loader for JDK types — `ArrayList` narrowing to `List` is the most common case of all and both
live there.

**What this does not do.** A different name or parameter list is always a different method and is
never paired. An owner with no related counterpart is reported. So a call moved to an unrelated
class is still caught, which is the thing the tolerance could most easily have hidden.

## Telling artefacts from defects

A difference is not automatically a defect. The classifier recognises these, and each recognition
requires the structural pattern to actually be present:

| Category | The pattern that must be visible |
|---|---|
| `ECJ_BOOLEAN_MATERIALIZATION` | The rebuild is smaller by exactly the excess `ireturn` count, with a matching excess of `iconst_0`/`iconst_1` in the original |
| `ECJ_NO_IINC_OPTIMIZATION` | The rebuild has more `iinc` and is smaller by 2–4 instructions per extra `iinc` |
| `EXCEPTION_SEGMENT_SPLIT` | Both tables protect the same throwing instructions; only the row boundaries differ |
| `FIELD_STORE_HOISTING` | The rebuild writes a field fewer times, every original write stores the same value, and the field is never read back |
| `BOXING_MATERIALIZATION` | The only differing calls are boxing or unboxing; every other call still lines up |
| `STRING_CONCAT` | The only difference is `StringBuilder`/`StringBuffer` traffic *(advisory)* |
| `ACCESSOR_NAMING`, `MEMBER_ORDER`, `FINALLY_SELF_PROTECTION` | Handled by normalisation; recorded so the exclusion is visible |

**The rule that governs all of them: a divergence may only be excused when the code can point at
the structural pattern that produces it.** There is no fallback to "probably fine", no confidence
threshold, and no category that fires because two numbers are close. A difference nothing
recognises stays a difference and reaches a human.

## Verdicts

| Verdict | Means | Safe? |
|---|---|---|
| `EQUIVALENT` | Every signal matched | yes |
| `BENIGN` | Something differed and every difference was matched to a named artefact | yes |
| `SUSPECT` | An unexplained difference at a severity that may or may not matter | **not counted as safe** |
| `DAMAGED` | An unexplained difference that changes behaviour | no |
| `HARNESS_ERROR` | The gate could not run — a class is missing from the rebuild, or a file could not be read | **never a pass** |

`EQUIVALENT` and `BENIGN` are kept apart on purpose. A `BENIGN` class was examined *more* than an
`EQUIVALENT` one, not less: something in it differed and had to be accounted for. Collapsing them
would hide the difference between "matched" and "differed, and here is why that is fine".

Artifacts from the report that make verdicts auditable:

- every exempted class is listed with its categories, so a specific category can be challenged
  without re-deriving the judgement;
- coverage is printed next to the counts, because "0 damaged" means something different at 12%
  coverage than at 100%;
- `HARNESS_ERROR` is counted separately and never as a pass.

## Blind spots

**Two independent statements of the same kind, swapped in order, produce identical signals.** An
operand swap in `a - b` yields the same instruction multiset: `iload`, `iload`, `isub`, `ireturn`
in both, with the slots deliberately not compared because they differ between compilers. There is
no static fix for this that does not reintroduce the operand-order noise the whole design is built
to exclude. Differential testing covers it for methods it can drive, and the test suite contains a
case that demonstrates both halves of that claim.

**A small constant pushed a different *number of times* cannot be told from a compiler
difference.** The `smallConsts` signal reports it, but the difference between "the author wrote a
literal here and the recovered source does not" and "the compiler pushes a zero one more time" is
not visible in the instruction stream without a control-flow analysis this does not do. A changed
literal's *value* is a hard difference and is caught; a changed *count* is not decidable here.

**A blind spot that was found by writing a test rather than by running one.** The small-constant
hole above had been invisible from the day the first signal was written. It surfaced when a test
helper accidentally swapped both a method's name and its body and asserted the result was
equivalent — the assertion passed, which was the bug report.

**Nothing here observes runtime behaviour** except differential testing.

**The gate is as good as its signals.** A behaviour difference that leaves no trace in exception
tables, call targets, field accesses, constants, rethrow counts or instruction count will not be
caught.

## Four bugs this checker shipped with

Worth reading, because they are the reason the current version reads structured bytecode instead
of parsing `javap` output. Each is a regression test in
[`HistoricalRegressionTest`](../anydecoder-core/src/test/java/com/anydecoder/core/gate/HistoricalRegressionTest.java).

**The exception type was never compared.** A `javap` exception-table row renders as
`from to target Class com/example/Foo`. The parser read field 3, which is the literal token
`Class`; the caught type is field 4. Every row therefore appeared to have the same type, and the
signal degenerated into comparing row *counts*. Swapping every handler type in a class for an
unrelated one, leaving the count unchanged, reported `HARD FAIL: 0`.

**Member order changed the API verdict.** `javap -p` lists members in class-file order and the two
compilers disagree about where the constructor sits. Comparing ordered lists flagged a class as an
API difference when the member *sets* were identical.

**Protected-range segmentation was read as damage.** As above: 39 of 42 hard failures were one
artefact, hiding 3 real defects.

**Every `finally` handler was silently discarded.** The parser required five whitespace-separated
fields per row, but a `finally` handler renders with four — its type is literally `any`. The
original's `finally` was therefore invisible to the entire comparison, and a source that had
dropped its `finally` compared equal. There were 245 such rows in a 300-class sample, meaning the
gate had been reporting passes on code it had not examined.

Two of those four made the gate report a pass on something it had never compared. That failure
mode — a check that silently stops checking — is the one this design is arranged around.

## A note on what the gate cannot tell you

It compares the rebuild against the original. It cannot tell you the decompiled source is what the
author wrote, and it does not try. The names, the comments, the formatting and the loop style are
not in the bytecode; no amount of comparison recovers them.

What it tells you is narrower and stronger: the recovered source, compiled, does what the original
did.

## Extending

**A new signal** implements `Signal`, is registered in `ClassGate`, and must come with the
measurement that justifies its tolerance. A signal added because it seemed useful, without a
corpus measurement showing it distinguishes a real difference from compiler noise, will produce
false positives and erode trust in the verdicts.

**A new artefact category** goes in `BenignDivergenceClassifier`, and must specify the structural
pattern that identifies it. Do not add a category that fires on proximity, on a size threshold, or
on the absence of a better explanation.
