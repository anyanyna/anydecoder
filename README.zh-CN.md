# anydecoder

**让你的编码 Agent 能恢复 Java 源码 —— 并证明它恢复对了。**

```
反编译  →  重新编译  →  拿重建的字节码与原 class 比对
```

anydecoder 是一份给 Claude Code、Codex、Trae 用的 skill，背后是一个 CLI。把 class 目录、jar
或 war 指给你的 Agent：它会恢复出源码，然后把源码重新编译、与它来源的那个 class 文件比对，
从而**证明**这次恢复是忠实的。证明不了的，会被报出来、附上具体差异，而不是被当成"已恢复的
源码"悄悄交出去。

[English](README.md) · [判定方法](docs/verification.md) · [反编译陷阱目录](docs/pitfalls.md)

---

## 为什么要做这个

源码是会丢的。构建产物进了归档，仓库没进，某一天你面对的就是一个 `.class` 目录，没有别的。

反编译器很好，也在变好，但它们的输出有个别扭的性质：**看起来永远都合理。** 一个 `finally`
被渲染成 try-with-resources，读起来比原文还漂亮，却悄悄引入了异常抑制语义；一个局部变量被
声明成 `FileWriter` 而不是 `Writer`，编译毫无问题，重载绑定却换了。这两件事读代码都看不出来。

所以出路不是换一个更好的反编译器，而是一个不依赖"读"的判据：

> 把恢复的源码重新编译，如果字节码和原 class 对得上，源码就是忠实的 —— 不管它读起来像不像
> 原逻辑，也不管你怎么看它。

这个判据是机械的，也是整件事的核心。Agent 负责让它变得可用：一个真实项目会产出几百个判据自己
清不掉的类，每一个都需要有人去读反汇编、做判断、再试一次。**答案由判定结果给出，不是由模型的
信心给出。**

## 它可以实现什么

把一个源码已失传的生产系统 —— 只剩 2984 个 class 文件 —— 变回 2451 个可读、可编译、
**每一个都有据可查**的源码文件。

- **一次跑完整个项目。** class 目录、jar、war 都行，出来是一棵完整源码树加一份报告。
- **每个类都有据。** 没有任何一个类是"看着像"就交给你的 —— 每一个都重新编译过、与它来源的
  字节码比对过。
- **找得出会悄悄上线的缺陷。** 在真实语料上，它找出了 8 个用编译器比对分辨不出的真实缺陷 ——
  一段被截断的日志文本、一个被从 `synchronized` 监视表达式里提升出去的死局部变量。这两个都
  编译得过，测试可能也过，但行为已经变了。
- **对没做到的部分说实话。** 覆盖率与判定结果永远并排打印，所以它没验证什么，和它验证了什么
  一样清楚。这类工具里很少有谁愿意告诉你它在哪里停下了 —— 而这恰恰是剩下的结论值得相信的
  原因。

判定结果是 `EQUIVALENT`、`BENIGN`、`WAIVED`（安全）或 `SUSPECT`、`DAMAGED`、
`HARNESS_ERROR`（不安全）。它不会声称恢复出来的变量名和注释是作者写的 —— 那些不在字节码里；
也不会为它没覆盖到的类背书。

信号、每一个容差及其来源、能识别出的编译器行为、以及那些被写下来而不是被掩盖的盲区，都在
[判定方法](docs/verification.md) 里。

## 快速开始

### 1. 构建 CLI

需要 JDK 17+ 和 Maven。反编译器打包在 jar 里，不需要另外装。

```bash
git clone https://github.com/anyanyna/anydecoder && cd anydecoder
mvn -q package -DskipTests
```

如果你的 Maven 把 `central` 指向了内网镜像，加一个 settings 文件：`-s /path/to/settings.xml`。

### 2. 安装 skill

就是一个文件：[`agents/skills/anydecoder/SKILL.md`](agents/skills/anydecoder/SKILL.md)，按
[Agent Skills](https://agentskills.io) 规范编写，三个 Agent 读的是同一份。拷贝整个目录。

| Agent | 装到单个项目 | 装到所有项目 |
|---|---|---|
| **Claude Code** | `.claude/skills/anydecoder/` | `~/.claude/skills/anydecoder/` |
| **Codex** | `.agents/skills/anydecoder/` | `~/.agents/skills/anydecoder/` |
| **Trae** | `.trae/skills/anydecoder/` | `%userprofile%/.trae-cn/skills/anydecoder/` |

```bash
mkdir -p .claude/skills .agents/skills
cp -r /path/to/anydecoder/agents/skills/anydecoder .claude/skills/   # Claude Code
cp -r /path/to/anydecoder/agents/skills/anydecoder .agents/skills/   # Codex 与 Trae
```

Codex 会从当前工作目录逐级向上找 `.agents/skills/`，装在仓库根目录，仓库内任意位置都能被发现。
Trae 也读同一个目录，需要在设置里打开 **启用 .agents 技能目录** 开关。

Claude Code 还可以整个插件一起装，这样除了 skill 之外还多出三个修复用子 Agent 和两个斜杠命令：

```bash
claude --plugin-dir /path/to/anydecoder/agents
```

### 3. 对 Agent 说话

用自己的话讲就行。skill 是靠场景触发的，不是靠关键词。

```
把 build/classes 的源码恢复出来，并证明它是忠实的。
```

```
这个 jar 的源码丢了，帮我恢复。恢复完告诉我哪些类你验证不了。
```

你会拿到一棵源码树、一份报告，和一个覆盖率数字。**先看覆盖率** —— 只覆盖了十分之一的判据，
对另外十分之九什么结论也给不出。

## 直接用 CLI

Agent 做的每一步，你都可以自己跑。

```bash
# 完整流水线：反编译、清理、重建、过判据、出报告
java -Xmx8g -jar anydecoder-cli/target/anydecoder.jar run <classes|jar|war> \
  -o recovered --deps <依赖-jar-所在目录>

# 改完源码后重新校验，不必再反编译一遍
java -jar anydecoder-cli/target/anydecoder.jar gate <原始-class> <重建-class>

# 两个版本都真正跑起来比对行为 —— 判据唯一看不见的那件事
java -jar anydecoder-cli/target/anydecoder.jar difftest <原始-class> <重建-class>
```

判据覆盖到的类全部安全时退出码为 `0`，有需要人工看的为 `1`。`run` 的堆要比你预期的大 —— 几千
个类需要 4GB 以上，而且给少了**不会干净地失败**。`difftest` **会真的执行代码**，所以流水线本身
不会主动跑它。

## 这个方法的来历

方法来自一次为期九天的生产环境 Java Web 应用源码恢复。那次工作找出了 **6 个真实缺陷**（与
"用编译器比对无法区分"的产物混在一起），以及**检查器自身的 4 个 bug，其中 2 个是静默的**。
把同一份语料重新过一遍现在的判据，找到的是 **8** 个。数字在
[validation/RESULTS.md](validation/RESULTS.md)。

## 文档

| | |
|---|---|
| [判定方法](docs/verification.md) | 每一个信号、每一个容差，以及它来自哪次测量 |
| [反编译失败模式](docs/pitfalls.md) | 能抓到的真实缺陷，以及那些看着像问题其实没问题的 |
| [架构](docs/architecture.md) | 流水线，逐模块 |
| [验证结果](validation/RESULTS.md) | 真实语料上的数字 |

## 局限

- **判据的好坏取决于它的信号。** 它看不见两条同类型独立语句的交换；开启差分测试且方法可驱动
  时，这个盲区被补上。
- **只支持未混淆字节码。** 控制流平坦化、字符串加密、成员重命名等混淆输入在本版本范围之外。
- **引擎版本锁定** —— CFR 0.152 与 Vineflower 1.11.1 —— 因为判据的容差是针对它们的产物校准的。
- **Agent 不是权威。** 一个看着合理的修复，如果过不了判据，仍然是失败的修复。

## 许可

Apache License 2.0，见 [LICENSE](LICENSE)。
