# Installing by hand

The [Quickstart](../README.md#quickstart) is a prompt you paste into an agent, and for almost
everyone that is the whole install. This page is the manual route, for when you would rather do it
yourself, and the place to look if you want to build from source.

## Download the CLI

One jar, 6 MB, with the decompilers bundled inside it — there is nothing else to install, and
nothing to build. JDK 17+ is needed to run it.

```bash
mkdir -p ~/.anydecoder
curl -L -o ~/.anydecoder/anydecoder.jar \
  https://github.com/anyanyna/anydecoder/releases/latest/download/anydecoder.jar
java -jar ~/.anydecoder/anydecoder.jar --version
```

Any other location works, but `~/.anydecoder/anydecoder.jar` is where the skill looks first, so
putting it there saves the skill from having to ask you.

## Install the skill

The skill is one file, `SKILL.md`, in the [Agent Skills](https://agentskills.io) format. It rides
along with every release, so it comes from the same place and the same version as the jar:

```bash
mkdir -p ~/.claude/skills/anydecoder
curl -L -o ~/.claude/skills/anydecoder/SKILL.md \
  https://github.com/anyanyna/anydecoder/releases/latest/download/SKILL.md
```

Install it wherever your agent looks for skills:

| Agent | This project | Every project |
|---|---|---|
| **Claude Code** | `.claude/skills/anydecoder/SKILL.md` | `~/.claude/skills/anydecoder/SKILL.md` |
| **Codex** | `.agents/skills/anydecoder/SKILL.md` | `~/.agents/skills/anydecoder/SKILL.md` |
| **Cursor** | `.cursor/skills/anydecoder/SKILL.md` or `.agents/skills/…` | `~/.cursor/skills/anydecoder/SKILL.md` or `~/.agents/skills/…` |
| **Trae** | `.trae/skills/anydecoder/SKILL.md` or `.agents/skills/…` | `~/.trae-cn/skills/anydecoder/SKILL.md` |

For a single project, `.agents/skills/` is the one to pick: Codex, Cursor and Trae all read it, and
Cursor additionally reads `.claude/skills/` and `.codex/skills/` for compatibility. Claude Code is
the exception — it wants `.claude/skills/`.

Two platform notes, both because they differ from the rest:

- **Trae** uses `.trae/skills/` natively. To have it read `.agents/skills/` instead, switch on
  *Enable .agents skills directory* in its settings. Its Windows global path is
  `%userprofile%/.trae-cn/skills/`; the international build may use `.trae/` in place of
  `.trae-cn/`, which is worth checking against its own documentation.
- **Codex** scans `.agents/skills/` in your working directory and in every directory above it, so a
  repository-root install is found from anywhere inside the repository.

## The Claude Code plugin

[`agents/`](../agents/) is also a Claude Code plugin, which ships the skill plus three repair
subagents and two slash commands. It is the one part of an install that needs the repository
rather than a release:

```bash
git clone --depth 1 https://github.com/anyanyna/anydecoder ~/.anydecoder
claude --plugin-dir ~/.anydecoder/agents
```

That gives you `/anydecoder:recover` and `/anydecoder:verify` alongside the skill. The skill alone
does everything either command does; the plugin is the more guided route.

## Building from source

Only needed if you intend to change the tool. JDK 17+ and Maven.

```bash
git clone https://github.com/anyanyna/anydecoder ~/.anydecoder
cd ~/.anydecoder
mvn -q package -DskipTests
```

The jar lands at `anydecoder-cli/target/anydecoder.jar` — again with the decompilers bundled in. If
your Maven points `central` at an unreachable internal mirror, add `-s /path/to/settings.xml`.
