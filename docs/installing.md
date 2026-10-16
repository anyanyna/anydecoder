# Installing by hand

The [Quickstart](../README.md#quickstart) is a prompt you paste into an agent, and for almost
everyone that is the whole install. This page is the manual route, for when you would rather do it
yourself.

## Build the CLI

JDK 17+ and Maven. The decompilers are bundled into the jar, so nothing else is needed.

```bash
git clone https://github.com/anyanyna/anydecoder ~/.anydecoder
cd ~/.anydecoder
mvn -q package -DskipTests
```

That produces `anydecoder-cli/target/anydecoder.jar`. If your Maven points `central` at an
unreachable internal mirror, add `-s /path/to/settings.xml`.

Clone it somewhere durable. The skill's instructions look for the checkout at `~/.anydecoder`
first, and ask you if it is not there — so an unusual location works, it just costs a question.

## Install the skill

The skill is one directory, [`agents/skills/anydecoder/`](../agents/skills/anydecoder/), holding a
`SKILL.md` in the [Agent Skills](https://agentskills.io) format. Copy the whole directory; the
files beside `SKILL.md` are reference material it points at.

| Agent | This project | Every project |
|---|---|---|
| **Claude Code** | `.claude/skills/anydecoder/` | `~/.claude/skills/anydecoder/` |
| **Codex** | `.agents/skills/anydecoder/` | `~/.agents/skills/anydecoder/` |
| **Cursor** | `.cursor/skills/anydecoder/` or `.agents/skills/anydecoder/` | `~/.cursor/skills/anydecoder/` or `~/.agents/skills/anydecoder/` |
| **Trae** | `.trae/skills/anydecoder/` or `.agents/skills/anydecoder/` | `~/.trae-cn/skills/anydecoder/` |

For a single project, `.agents/skills/` is the one to pick: Codex, Cursor and Trae all read it, and
Cursor additionally reads `.claude/skills/` and `.codex/skills/` for compatibility. Claude Code is
the exception — it wants `.claude/skills/`.

```bash
# one project, every agent that follows the .agents convention
mkdir -p .agents/skills
cp -r ~/.anydecoder/agents/skills/anydecoder .agents/skills/

# one project, Claude Code
mkdir -p .claude/skills
cp -r ~/.anydecoder/agents/skills/anydecoder .claude/skills/

# every project on this machine
cp -r ~/.anydecoder/agents/skills/anydecoder ~/.claude/skills/
```

Two platform notes, both because they differ from the rest:

- **Trae** uses `.trae/skills/` natively. To have it read `.agents/skills/` instead, switch on
  *Enable .agents skills directory* in its settings. Its Windows global path is
  `%userprofile%/.trae-cn/skills/`; the international build may use `.trae/` in place of
  `.trae-cn/`, which is worth checking against its own documentation.
- **Codex** scans `.agents/skills/` in your working directory and in every directory above it, so a
  repository-root install is found from anywhere inside the repository.

## The Claude Code plugin

[`agents/`](../agents/) is also a Claude Code plugin, which ships the skill plus three repair
subagents and two slash commands. Loading it needs no copying:

```bash
claude --plugin-dir ~/.anydecoder/agents
```

That gives you `/anydecoder:recover` and `/anydecoder:verify` alongside the skill. The skill alone
does everything either command does; the plugin is the more guided route.
