# anydecoder

**让你的编码 Agent 能恢复 Java 源码 —— 并证明它恢复对了。**

```
反编译  →  重新编译  →  拿重建的字节码与原 class 比对
```

anydecoder 是一份给 Claude Code、Codex、Trae 用的 skill，背后是一个 CLI。把 class 目录、jar
或 war 指给你的 Agent，它会恢复出源码，然后把恢复出来的源码重新编译、与它来源的那个 class
文件比对，从而**证明**这次恢复是忠实的。

证明不了的，会被报出来、附上具体差异，而不是被当成"已恢复的源码"悄悄交出去。

[English](README.md) · [判定方法](docs/verification.md) · [反编译陷阱目录](docs/pitfalls.md)

---

## 为什么要做这个

源码是会丢的。构建产物进了归档，仓库没进，某一天你面对的就是一个 `.class` 目录，没有别的。

反编译器很好，也在变好，但它们的输出有个别扭的性质：**看起来永远都合理。** 一个 `finally`
被渲染成 try-with-resources，读起来比原文还漂亮，却引入了原文没有的异常抑制语义；一个局部
变量被声明成 `FileWriter` 而不是 `Writer`，编译毫无问题，重载绑定却换了。这两件事读代码
都看不出来，都会改变程序行为。

出路不是换一个更好的反编译器，而是一个不依赖"读"的判据：

> 把恢复的源码重新编译，如果字节码和原 class 对得上，源码就是忠实的 —— 不管它读起来像不像
> 原逻辑，也不管你怎么看它。

这个判据是整件事的核心，而且它是机械的。Agent 负责的是让它变得可用：一个真实项目会产出几百个
判据自己清不掉的类，每一个都需要有人去读反汇编、做判断、再试一次。这些活 anydecoder 交给你的
Agent 去做，而**答案由判定结果给出，不是由模型的信心给出**。

## 它证明了什么，没证明什么

**能证明**（对每个判据覆盖到的类）：异常表保护的是同一批会抛异常的指令、处理器类型一致；
调用的方法、访问的字段一致；加载的常量一致；`athrow` 次数一致；形态差异在按真实编译器差异
校准过的容差之内。

**不能证明**：

- 源码是作者当时写的样子。变量名、注释、格式、循环写成 `for` 还是 `while`，都不在字节码里，
  不作任何声称；
- 判据没覆盖到的类，什么都不能说。所以覆盖率永远与判定结果并排打印，`HARNESS_ERROR` 永远
  不计入通过；
- 运行时行为 —— 除非开启了动态差分测试。

判定结果是 `EQUIVALENT`、`BENIGN`、`WAIVED`（安全）或 `SUSPECT`、`DAMAGED`、
`HARNESS_ERROR`（不安全）。它们之间的区别、判据比对的每一个信号、能识别出的编译器行为、
以及已知且没有被掩盖的盲区，都在 [docs/verification.md](docs/verification.md) 里。

## 快速开始

### 1. 构建 CLI

需要 JDK 17+ 和 Maven。反编译器打包在 jar 里，不需要另外装。

```bash
git clone https://github.com/anyanyna/anydecoder
cd anydecoder
mvn -q package -DskipTests
```

产出 `anydecoder-cli/target/anydecoder.jar`。如果你的 Maven 把 `central` 指向了内网镜像，
加一个 settings 文件：`-s /path/to/settings.xml`。

### 2. 安装 skill

skill 就是一个文件：[`agents/skills/anydecoder/SKILL.md`](agents/skills/anydecoder/SKILL.md)，
按 [Agent Skills](https://agentskills.io) 规范编写，三个 Agent 读的是同一份，内容不用改。
拷贝整个目录 —— `SKILL.md` 是入口，同级目录里是它的参考资料。

| Agent | 装到单个项目 | 装到所有项目 |
|---|---|---|
| **Claude Code** | `.claude/skills/anydecoder/` | `~/.claude/skills/anydecoder/` |
| **Codex** | `.agents/skills/anydecoder/` | `~/.agents/skills/anydecoder/` |
| **Trae** | `.trae/skills/anydecoder/` | `%userprofile%/.trae-cn/skills/anydecoder/` |

```bash
# Claude Code —— 项目级
mkdir -p .claude/skills && cp -r /path/to/anydecoder/agents/skills/anydecoder .claude/skills/

# Codex 和 Trae 都认 .agents 这个约定目录
mkdir -p .agents/skills && cp -r /path/to/anydecoder/agents/skills/anydecoder .agents/skills/
```

两点说明，都是因为各家不一样：

- **Trae** 原生的项目技能目录是 `.trae/skills/`；要让它读 `.agents/skills/`，需要在设置里打开
  **启用 .agents 技能目录** 开关。两条路都行。
- **Codex** 会在当前工作目录以及它的每一层上级目录里找 `.agents/skills/`，所以装在仓库根目录，
  仓库内任意位置都能被发现。

**Claude Code 还可以整个插件一起装**，这样除了 skill 之外还多出三个修复用子 Agent 和两个斜杠命令：

```bash
claude --plugin-dir /path/to/anydecoder/agents
```

### 3. 对 Agent 说话

用自己的话讲就行。skill 是靠场景触发的，不是靠关键词：

```
把 build/classes 的源码恢复出来，并证明它是忠实的。
```

```
这个 jar 的源码丢了，帮我恢复。恢复完告诉我哪些类你验证不了。
```

```
判据说还有 12 个类要人工看，挨个处理掉。
```

你会拿到一棵源码树、一份报告，和一个覆盖率数字。**先看覆盖率** —— 只覆盖了十分之一的判据，
对另外十分之九什么结论也给不出；skill 里是这么写的：宁可直说，也不含糊带过。

## 直接用 CLI

Agent 驱动的是 CLI，但每一步你都可以自己跑。不需要任何 AI。

```bash
# 完整流水线：反编译、清理、重建、过判据、出报告
java -Xmx8g -jar anydecoder-cli/target/anydecoder.jar run <classes|jar|war> \
  -o recovered --deps <依赖-jar-所在目录>

# 改完源码后重新校验，不必再反编译一遍
java -jar anydecoder-cli/target/anydecoder.jar gate <原始-class> <重建-class>

# 两个版本都真正跑起来比对行为 —— 判据唯一看不见的那件事
java -jar anydecoder-cli/target/anydecoder.jar difftest <原始-class> <重建-class>
```

判据覆盖到的类全部安全时退出码为 `0`，有需要人工看的为 `1`。

`run` 的堆要比你预期的大：重建是在同一个进程里对整棵树做的，所以峰值取决于项目规模，而不是
最大的那个类。几千个类需要 4GB 以上。给少了**不会干净地失败**，[skill 里解释了为什么](agents/skills/anydecoder/SKILL.md)
—— 第一次长时间跑之前值得先读一下。

`difftest` **会真的执行代码**，所以流水线本身不会主动跑它。会写文件或开套接字的方法，照样会
写文件或开套接字。

## 仓库结构

| | |
|---|---|
| [`anydecoder-core/`](anydecoder-core/) | 分类、反编译、归一化、重建、过判据、出报告 |
| [`anydecoder-cli/`](anydecoder-cli/) | `anydecoder.jar` 命令行入口 |
| [`agents/`](agents/) | skill 本体，外加 Claude Code 插件的子 Agent 与命令 |
| [`docs/`](docs/) | 怎么判定、怎么构建、会踩什么坑 |
| [`validation/`](validation/README.md) | 长期验证语料与结果 |

## 文档

| | |
|---|---|
| [判定方法](docs/verification.md) | 每一个信号、每一个容差，以及它来自哪次测量 |
| [反编译失败模式](docs/pitfalls.md) | 能抓到的真实缺陷，以及那些看着像问题其实没问题的 |
| [架构](docs/architecture.md) | 流水线，逐模块 |
| [验证结果](validation/RESULTS.md) | 真实语料上的数字 |

## 这个方法的来历

方法不是在这里发明的。它来自一次为期九天的生产环境 Java Web 应用源码恢复 —— 2984 个 class
文件，恢复出 2451 个源码文件，原始产物由 Eclipse 编译器 ecj 编译，用 javac 重建。

那次工作在这些文件里找出了 **6 个真实缺陷**，它们与"用编译器比对无法区分"的产物混在一起；
最终结论是 97.9% 的类经证明行为等价。同时，它还暴露了**检查器自身的 4 个 bug，其中 2 个是
静默的** —— 包括一个解析器丢掉了所有 `finally` 处理器，导致一个删掉了 `finally` 的源码被判为
一致。这 4 个现在是回归测试。

**anydecoder 又多找到 2 个。** 把同一份语料重新过一遍现在的判据，找到的是 **8** 个 —— 多出的
两个是一段被截断的日志文本（`集合深拷贝错误:` 成了 `深拷贝错误:`），以及一个被反编译器从
`synchronized` 监视表达式里提升出来的死局部变量。当年那套检查器两个都看不见。

## 局限

- **判据的好坏取决于它的信号。** 它看不见两条同类型独立语句的交换。开启差分测试且方法可驱动
  时，这个盲区被补上。
- **只支持未混淆字节码。** 控制流平坦化、字符串加密、成员重命名等混淆输入在本版本范围之外。
- **引擎版本锁定。** CFR 0.152 与 Vineflower 1.11.1，因为判据的容差是针对它们的产物校准的。
  升级其中任何一个，都是改变了 anydecoder 的验证对象。
- **Agent 不是权威。** 它负责读反汇编、决定试什么；判据负责决定这次尝试成没成。一个看着合理的
  修复，如果过不了判据，仍然是失败的修复。

## 许可

Apache License 2.0，见 [LICENSE](LICENSE)。
