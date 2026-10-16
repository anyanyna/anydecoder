# anydecoder

**Give your coding agent the ability to recover Java source from class files — and prove it got
it right.**

```
decompile  →  rebuild  →  compare the rebuilt bytecode with the original
```

anydecoder is a skill for Claude Code, Codex and Trae, backed by a CLI. Point your agent at a
directory of `.class` files, a jar, or a war: it recovers the source, then proves the recovery is
faithful by recompiling it and comparing the result against the bytecode it came from. Whatever
cannot be proved is reported with its specific difference, not quietly handed over as recovered
source.

[中文说明](README.zh-CN.md) · [How it decides](docs/verification.md) · [Decompiler failure modes](docs/pitfalls.md)

---

## Why this exists

Source gets lost. Build outputs get archived, the repository does not, and one day there is a
directory of `.class` files and no way to regain the code.

Decompilers are good and getting better, but their output has an awkward property: **it always
looks plausible.** A `finally` rendered as try-with-resources reads better than the original and
silently adds exception-suppression semantics; a local declared as `FileWriter` instead of
`Writer` compiles cleanly and binds to a different overload. Neither is visible by reading.

So the way out is not a better decompiler but a check that does not depend on reading:

> If you recompile the recovered source and the bytecode matches the original, the source is
> faithful — regardless of how it reads or what you think of it.

That check is mechanical and it is the whole point. The agent is what makes it usable: a real
project produces hundreds of classes the checker cannot clear on its own, each needing someone to
read the disassembly, decide and try again. The verdict — not the model's confidence — is the
answer.

## What it does and does not establish

For every class the gate reaches it confirms the same exception table, members, field accesses,
constants and rethrow counts, within a tolerance calibrated against real compiler differences.

It does **not** claim the source is what the author wrote — names, comments, formatting and loop
style are not in the bytecode. It says nothing about classes it did not reach, which is why
coverage is printed beside every verdict and `HARNESS_ERROR` never counts as a pass.

Verdicts are `EQUIVALENT`, `BENIGN`, `WAIVED` (safe) or `SUSPECT`, `DAMAGED`, `HARNESS_ERROR`
(not safe). [How it decides](docs/verification.md) covers the signals, the recognised compiler
artefacts, and the blind spots that are documented rather than papered over.

## Quickstart

### 1. Build the CLI

JDK 17+ and Maven. The decompilers ship inside the jar, so nothing else is needed.

```bash
git clone https://github.com/anyanyna/anydecoder && cd anydecoder
mvn -q package -DskipTests
```

If your Maven points `central` at an internal mirror, add `-s /path/to/settings.xml`.

### 2. Install the skill

One file, [`agents/skills/anydecoder/SKILL.md`](agents/skills/anydecoder/SKILL.md), written to the
[Agent Skills](https://agentskills.io) format and read unchanged by all three agents. Copy the
whole directory.

| Agent | One project | Every project |
|---|---|---|
| **Claude Code** | `.claude/skills/anydecoder/` | `~/.claude/skills/anydecoder/` |
| **Codex** | `.agents/skills/anydecoder/` | `~/.agents/skills/anydecoder/` |
| **Trae** | `.trae/skills/anydecoder/` | `%userprofile%/.trae-cn/skills/anydecoder/` |

```bash
mkdir -p .claude/skills .agents/skills
cp -r /path/to/anydecoder/agents/skills/anydecoder .claude/skills/   # Claude Code
cp -r /path/to/anydecoder/agents/skills/anydecoder .agents/skills/   # Codex and Trae
```

Codex searches `.agents/skills/` upward from your working directory, so a repository-root install
is found everywhere inside it. Trae reads the same directory once you enable *Enable .agents
skills directory* in its settings.

Claude Code can take the whole plugin instead, which adds three repair subagents and two slash
commands on top of the skill:

```bash
claude --plugin-dir /path/to/anydecoder/agents
```

### 3. Ask your agent

Say it in your own words. The skill triggers on the situation, not on a keyword.

```
Recover the source from build/classes and prove it's faithful.
```

```
We lost the source to this jar. Get it back, and tell me which classes you could not verify.
```

You get a source tree, a report, and a coverage number. **Read the coverage first** — a gate that
reached a tenth of the input has told you nothing about the other nine tenths.

## Using the CLI directly

Every step the agent takes is a command you can run yourself.

```bash
# the whole pipeline: decompile, clean up, rebuild, gate, report
java -Xmx8g -jar anydecoder-cli/target/anydecoder.jar run <classes|jar|war> \
  -o recovered --deps <dependency-jars>

# re-check after editing, without decompiling again
java -jar anydecoder-cli/target/anydecoder.jar gate <original-classes> <rebuilt-classes>

# call both versions and compare behaviour — the one thing the gate cannot see
java -jar anydecoder-cli/target/anydecoder.jar difftest <original-classes> <rebuilt-classes>
```

Exit status is `0` when everything the gate reached is safe, `1` when something needs review. Give
`run` more heap than you expect — a few thousand classes needs over 4 GB, and under-provisioning
does not fail cleanly. `difftest` **runs real code**, which is why the pipeline never invokes it by
itself.

## Where this came from

The method was worked out during a nine-day recovery of a production Java web application whose
source had been lost — 2984 class files, 2451 recovered source files. That effort found **6 real
defects** among files a compiler-based comparison could not distinguish, and four bugs in the
checker itself, two of them silent. Re-running the same corpus through this gate found **8**,
including a log message truncated from `集合深拷贝错误:` to `深拷贝错误:`. The numbers are in
[validation/RESULTS.md](validation/RESULTS.md).

## Documentation

| | |
|---|---|
| [How anydecoder decides](docs/verification.md) | every signal, every tolerance, and the measurement it came from |
| [Decompiler failure modes](docs/pitfalls.md) | the defects this catches, and what only looks alarming |
| [Architecture](docs/architecture.md) | the pipeline, module by module |
| [Validation results](validation/RESULTS.md) | the numbers from a real corpus |

## Limits

- **The gate is as good as its signals.** It cannot see a swap of two independent statements of
  the same kind; differential testing covers that where it is enabled.
- **Unobfuscated bytecode only.** Control-flow flattening, encrypted strings and renamed members
  are out of scope for this version.
- **Engines are pinned** — CFR 0.152 and Vineflower 1.11.1 — because the gate's tolerance was
  calibrated against their output.
- **The agent is not the authority.** A plausible repair that does not satisfy the gate is still a
  failed repair.

## Licence

Apache License 2.0. See [LICENSE](LICENSE).
