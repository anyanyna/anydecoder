---
description: Check whether an existing source tree really reproduces the original bytecode
argument-hint: <original-classes-dir> <rebuilt-classes-dir>
---

Compile the source that produced `$1` and check it against the original class files in `$2`…

Treat the arguments as: original class files first, then the class files rebuilt from the source
under test. If the rebuilt classes do not exist yet, compile the source first, at the Java
release the originals were built for — compiling at a newer release changes string concatenation
to `invokedynamic` and every concatenating method will then differ for reasons that have nothing
to do with the source.

```bash
javac -g -d <rebuilt> -cp "<deps>:<original>" @<sources>
java -jar anydecoder-cli/target/anydecoder.jar gate <original> <rebuilt> --format markdown
```

Read the output as: `EQUIVALENT` and `BENIGN` are safe; `SUSPECT` and `DAMAGED` are not;
`HARNESS_ERROR` is not a pass and means the gate never looked.

For anything not safe, dispatch `divergence-triage` with the class name and the findings.

Report coverage alongside the verdicts, and never describe a class as verified unless the gate
actually reached it.
