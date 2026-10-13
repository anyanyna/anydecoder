# Working on anydecoder

## What it is

Recover Java source from class files, and prove the recovered source behaves like the original.
The claim is narrow and mechanical: **recompile the recovered source and compare the result against
the class file it came from.** Anything reported as equivalent matched on every signal; anything
that did not is reported with its evidence.

## The rule everything follows

> A difference may only be excused when the code can name the structural pattern that produces it
> and show that it is present.

There is no fallback to "probably fine", no confidence threshold, and no category that fires
because two numbers are close. A difference nothing recognises stays a difference and reaches a
human. This is enforced in `BenignDivergenceClassifier`, and it is the reason several plausible
exemptions are deliberately absent.

The corollary: **a category that accounts for more is a category that can hide more.** Every rule
is tested against a case it must *not* excuse — folding refused when the instruction arithmetic
does not fit, a changed literal not excused by a merge, boxing refused when a call genuinely moved.
Keep that pairing when adding one.

## Building and testing

```bash
JAVA_HOME=<jdk17> mvn verify
```

`.mvn/maven.config` points at a settings file that mirrors everything to Maven Central, because
this machine's Maven installation redefines `central` as an unreachable internal Nexus. Both files
are gitignored. The local repository is on D: via the global settings.

Do **not** rebuild the jar while a pipeline run is in flight — a JVM loads classes from a jar
lazily, so replacing it produces `ClassNotFoundException` for classes that are plainly in it. See
docs/pitfalls.md. Copy the jar somewhere outside `target/` before a long run.

## Where things are

| | |
|---|---|
| `gate/` | the comparison: models, signals, the classifier, waivers |
| `gate/MethodFlow` | control flow, used only by the classifier, never by the comparison |
| `pipeline/` | ingest → decompile → normalize → build → gate → substitute → report |
| `validation/out/` | scratch: gates, corpora, results. Gitignored except the docs |
| `docs/verification.md` | why each tolerance exists, and the four checker bugs it was built around |

## Two traps that cost real time

**Branch targets in `Insn` are for analysis only.** They are deliberately out of `render()` —
offsets move between compilers, and a comparison that saw them would report every method as
different.

**The validation corpus is a real project on another drive.** Nothing about it belongs in the
repository: not the classes, not their decompilation. `validation/*` is ignored except the
documentation; keep it that way.

## When adding a signal or a category

Come with the measurement. A new signal needs a corpus result showing it separates a real
difference from compiler noise, and a new benign category must name the structural pattern that
identifies it. The tolerances in this codebase are all derived from a measurement, and each one is
commented with it — 15% because of boolean materialisation, six instructions because of that plus
`iinc` folding, the exception-table normalisation because of 39 phantom defects in 42.