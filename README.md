# anydecoder

**Give your coding agent the ability to recover Java source from class files — and prove it got
it right.**

```
decompile  →  rebuild  →  compare the rebuilt bytecode with the original
```

anydecoder is a skill for Claude Code, Codex and Trae, backed by a CLI. Point your agent at a
directory of `.class` files, a jar, or a war. It recovers the source, then proves the recovery is
faithful by recompiling it and comparing the result against the bytecode it came from.

Whatever cannot be proved is reported, with the specific difference, instead of being quietly
handed over as recovered source.

[中文说明](README.zh-CN.md) · [How it decides](docs/verification.md) · [Decompiler failure modes](docs/pitfalls.md)

---

## Why this exists

Source gets lost. Build outputs get archived, the repository does not, and one day there is a
directory of `.class` files and no way to regain the code.

Decompilers are good and getting better, but their output has an awkward property: **it always
looks plausible.** A `finally` rendered as try-with-resources reads better than the original and
adds exception-suppression semantics the original never had. A local variable declared as
`FileWriter` instead of `Writer` compiles cleanly and binds to a different overload. Neither is
visible by reading. Both change what the program does.

The way out is not a better decompiler. It is a check that does not depend on reading:

> If you recompile the recovered source and the bytecode matches the original, the source is
> faithful — regardless of how it reads or what you think of it.

That check is the whole point, and it is mechanical. The agent is what makes it usable: a real
project produces hundreds of classes the checker cannot clear on its own, and each one needs
someone to read the disassembly, decide, and try again. That is the work anydecoder hands to your
agent, with the verdict — not the model's confidence — as the answer.

## What it establishes, and what it does not

**Establishes**, for each class the gate reaches: the exception table covers the same throwing
instructions with the same handler types; the same methods, fields and field accesses are used;
the same constants are loaded; the same number of rethrows appear; and the shape is within a
tolerance calibrated against real compiler differences.

**Does not establish:**

- that the source is what the author wrote. Variable names, comments, formatting, and whether a
  loop was written as `for` or `while` are not in the bytecode and are not claimed;
- anything about classes the gate did not reach. Coverage is reported next to every verdict for
  exactly this reason, and `HARNESS_ERROR` is never counted as a pass;
- runtime behaviour, except where differential testing is enabled.

Results are `EQUIVALENT`, `BENIGN`, `WAIVED` (all safe) or `SUSPECT`, `DAMAGED`, `HARNESS_ERROR`
(not safe). The difference between them is the subject of
[docs/verification.md](docs/verification.md), which also documents the signals, the recognised
compiler artefacts, and the blind spots that are known and not papered over.

## Quickstart

### 1. Build the CLI

Requires JDK 17+ and Maven. The decompilers ship inside the jar, so there is nothing else to
install.

```bash
git clone https://github.com/anyanyna/anydecoder
cd anydecoder
mvn -q package -DskipTests
```

This produces `anydecoder-cli/target/anydecoder.jar`. If your Maven points `central` at an
internal mirror, add `-s /path/to/settings.xml`.

### 2. Install the skill

The skill is one file, [`agents/skills/anydecoder/SKILL.md`](agents/skills/anydecoder/SKILL.md),
written to the [Agent Skills](https://agentskills.io) format and read unchanged by all three
agents. Copy the whole directory — `SKILL.md` is the entry point and its siblings are reference
material.

| Agent | Install for one project | Install for every project |
|---|---|---|
| **Claude Code** | `.claude/skills/anydecoder/` | `~/.claude/skills/anydecoder/` |
| **Codex** | `.agents/skills/anydecoder/` | `~/.agents/skills/anydecoder/` |
| **Trae** | `.trae/skills/anydecoder/` | `%userprofile%/.trae-cn/skills/anydecoder/` |

```bash
# Claude Code — project-level
mkdir -p .claude/skills && cp -r /path/to/anydecoder/agents/skills/anydecoder .claude/skills/

# Codex and Trae both read the .agents convention
mkdir -p .agents/skills && cp -r /path/to/anydecoder/agents/skills/anydecoder .agents/skills/
```

Two notes, both because the platforms differ:

- **Trae** uses `.trae/skills/` natively and reads `.agents/skills/` only after you switch on
  *Enable .agents skills directory* in its settings. Either works.
- **Codex** discovers `.agents/skills/` in your working directory and every directory above it,
  so a repository-root install is picked up everywhere inside the repository.

**Claude Code can also take the whole plugin**, which adds three repair subagents and two slash
commands on top of the skill:

```bash
claude --plugin-dir /path/to/anydecoder/agents
```

### 3. Tell your agent what you want

Say it in your own words. The skill triggers on the situation, not on a keyword:

```
Recover the source from build/classes and prove it's faithful.
```

```
We lost the source to this jar. Get it back, and tell me which classes you could not verify.
```

```
The gate says 12 classes need review. Work through them.
```

You get a source tree, a report, and a coverage number. **Read the coverage number first** — a
gate that reached a tenth of the input has not told you much about the other nine tenths, and the
skill is written to say so rather than to summarise it away.

## Using the CLI directly

The agent drives the CLI, but every step is a command you can run yourself. No AI involved.

```bash
# the whole pipeline: decompile, clean up, rebuild, gate, report
java -Xmx8g -jar anydecoder-cli/target/anydecoder.jar run <classes|jar|war> \
  -o recovered --deps <directory-of-dependency-jars>

# re-check after editing, without decompiling again
java -jar anydecoder-cli/target/anydecoder.jar gate <original-classes> <rebuilt-classes>

# call both versions and compare what they do — the one thing the gate cannot see
java -jar anydecoder-cli/target/anydecoder.jar difftest <original-classes> <rebuilt-classes>
```

Exit status is `0` when everything the gate reached is safe, `1` when something needs review.

Give `run` more heap than you would expect: the rebuild happens in-process on the whole tree, so
the peak is a property of the project rather than of the largest class. A few thousand classes
needs more than 4 GB. Under-provisioning does not fail cleanly, and
[the skill says why](agents/skills/anydecoder/SKILL.md) — it is worth reading before a first long
run.

`difftest` **runs real code**, which is why the pipeline does not invoke it by itself. A method
that writes files or opens sockets will still write files or open sockets.

## Repository layout

| | |
|---|---|
| [`anydecoder-core/`](anydecoder-core/) | ingest, decompile, normalise, rebuild, gate, report |
| [`anydecoder-cli/`](anydecoder-cli/) | the `anydecoder.jar` front end |
| [`agents/`](agents/) | the skill, plus the Claude Code plugin's subagents and commands |
| [`docs/`](docs/) | how it decides, how it is built, what goes wrong |
| [`validation/`](validation/README.md) | the standing corpus and its results |

## Documentation

| | |
|---|---|
| [How anydecoder decides](docs/verification.md) | every signal, every tolerance, and the measurement it came from |
| [Decompiler failure modes](docs/pitfalls.md) | the defects this catches, and the things that look alarming and are fine |
| [Architecture](docs/architecture.md) | the pipeline, module by module |
| [Validation results](validation/RESULTS.md) | the numbers from a real corpus |

## Where this came from

The method is not invented here. It was worked out during a nine-day recovery of a production
Java web application whose source had been lost — 2984 class files, 2451 recovered source files,
compiled with the Eclipse compiler and recompiled with javac.

That effort found **6 real defects** among 2451 files that a compiler-based comparison could not
distinguish, and produced a 97.9% figure for classes verified as behaviourally equivalent. It also
produced four bugs in the checker itself, two of them silent — including a parser that discarded
every `finally` handler, so a source that had dropped its `finally` compared equal. The four are
regression tests now.

**anydecoder finds two more.** Re-running the same corpus through this gate turned up **8**
defects, the extra two being a log message truncated from `集合深拷贝错误:` to `深拷贝错误:` and a
dead local the decompiler hoisted out of a `synchronized` monitor expression. Neither was visible
to the original checker.

## Limits

- **The gate is as good as its signals.** It cannot see a swap of two independent statements of
  the same kind. Differential testing covers that where it is enabled and the method is drivable.
- **Unobfuscated bytecode only.** Obfuscated input — control-flow flattening, encrypted strings,
  renamed members — is out of scope for this version.
- **Engines are pinned.** CFR 0.152 and Vineflower 1.11.1, because the gate's tolerance was
  calibrated against their output. Upgrading either changes what anydecoder validates.
- **The agent is not the authority.** It reads the disassembly and decides what to try; the gate
  decides whether the attempt worked. A plausible repair that does not satisfy the gate is still a
  failed repair.

## Licence

Apache License 2.0. See [LICENSE](LICENSE).
