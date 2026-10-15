---
description: Recover Java source from class files and drive repairs until the gate is satisfied
argument-hint: <classes-dir|jar|war> [--deps <dir>] [--output <dir>]
---

Recover source from `$ARGUMENTS` and drive the loop until every class the gate reaches is either
verified or reported as unreachable.

Use the `anydecoder` skill. In outline:

1. Build the CLI if `anydecoder-cli/target/anydecoder.jar` is absent, then run the pipeline:
   ```bash
   java -jar anydecoder-cli/target/anydecoder.jar run <input> -o <output> --deps <deps>
   ```
2. Read `<output>/report.json`. **Report the coverage number before anything else.** If the gate
   reached a small fraction of the input, say so plainly — the verdicts mean little on their own.
3. For each entry in `needsReview`, dispatch a `decompile-repairer` agent in parallel, giving it
   the class name, its findings, and the paths to the original and rebuilt class directories.
4. Recompile and re-gate. Repeat from step 3 for anything still failing, up to three rounds.
   A class that has not moved in two rounds is not going to be fixed by another attempt — stop
   and report it.
5. Dispatch `divergence-triage` on whatever remains after repairs stop working.

Finish with: coverage, the verdict counts, the classes that still need review, and for each one a
one-line statement of what is wrong. Do not summarise away the ones that could not be fixed.
