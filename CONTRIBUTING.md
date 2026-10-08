# Contributing

## The bar for changes to the gate

The gate's value is that its verdicts can be trusted. A signal that fires on a compiler artefact
costs someone an investigation; a signal that stays quiet on a behaviour change costs correctness.
Both have happened in this project's history, and the second is much worse.

So **a change to a signal or a classifier must come with the evidence that justifies it**:

- a new signal needs a corpus measurement showing it distinguishes a real difference from
  compiler noise, and the tolerance must be derived from that measurement rather than chosen;
- a new benign-divergence category must specify the structural pattern that identifies it.
  Categories that fire on proximity, on a size threshold, or on the absence of a better
  explanation will not be accepted: a divergence nothing recognises must stay a divergence;
- a widened tolerance needs numbers. "It produced false positives" is a start; the count, the
  corpus, and the pattern behind them is the requirement.

Tests are expected to encode the reasoning, not just the behaviour. A test named
`changedHandlerTypeIsDetected` with a comment explaining what the old implementation did is worth
several tests that only assert current output.

## Running things

```bash
mvn verify                          # unit and integration tests
mvn -q package -DskipTests          # the CLI jar
```

If your Maven points `central` at an unreachable mirror, pass a settings file with `-s`.

There is no test that downloads a decompiler: the engine wrappers are exercised against the
bundled jars, and everything about the comparison is tested through in-memory bytecode or
fixtures compiled at test time. A test that needs the network will not be merged.

## Style

Match what is there. Two conventions are worth calling out because they are load-bearing rather
than stylistic:

- **Comments explain why, not what.** A line like `// increment the counter` above `i++` is noise.
  A line explaining that a field is dropped because two compilers assign it differently is the
  reason the next person does not delete it.
- **No claim without its evidence.** If a comment says a tolerance is 15%, it should say what was
  measured to arrive at 15%.

Records, `List.of`, `Optional` over nulls, and `${}`-free string building are the house style.

## Adding an engine

Implement `Decompiler`, register it in `DecoderPipeline.engineFor`, add its `ToolArtifact` to
`ToolCache`'s bundle in `anydecoder-core/pom.xml`, and note in the class what its known failure
modes are. Engines are subprocess wrappers, so it is roughly thirty lines.

If the engine renders `try`/`catch`/`finally` unreliably — Procyon does — say so in the class
comment. Someone will otherwise use its output as ground truth and produce a repair that fails the
gate.

## Validation against a real corpus

The strongest check available is a real project whose original class files still exist. Run it,
compare against the figures in [`validation/`](validation/README.md), and report what differs.
A change that improves one number while quietly regressing coverage is the failure mode to watch
for; coverage is reported next to the verdicts for exactly that reason.
