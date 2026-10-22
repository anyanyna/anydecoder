# anydecoder 项目记忆

本文件是 opencode 的项目规则与历史记忆，承接自 Claude Code 的 `CLAUDE.md` 与本项目的
claude-mem 会话记忆（`project=anydecoder`，918 条 observation、31 条 session summary，
时间跨度 2026-10-13 至 2026-10-21）。

`opencode.json` 通过 `instructions` 同时加载 `CLAUDE.md`。构建、门禁与比较器的细节规则以
`CLAUDE.md` 和 `docs/verification.md` 为准；本文件记录历史结论、当前状态、未决事项和已经
踩过的坑。

## 1. 项目是什么

从 class 文件恢复 Java 源码，并证明恢复出来的源码和原始 class 行为一致。主张很窄、很机械：

> 重新编译恢复出的源码，把结果和它来自的那个 class 文件比对。

报告为等价的东西，是在每一个信号上都对上了；没对上的东西，连同证据一起交给人。没有
"大概没问题"，没有置信度阈值，没有"两个数字接近所以放行"。

**不可妥协的规则：**

- 只有当代码能说出产生差异的结构模式、并证明该模式确实存在时，差异才可以被豁免。
- 一个能解释更多东西的类别，也能隐藏更多东西。每新增一条良性规则，必须同时配一个
  "它不该豁免"的反向测试。
- 说不清机制的残留就保持为差异，不猜、不压。`SUSPECT` 是保守上报，不是待消除的噪音。
- 不要为了让比较器闭嘴而改源码（例如把三元赋值改写成 `if/else`）。先问原始字节码是什么。

## 2. 代码地图

| 路径 | 作用 |
|---|---|
| `gate/` | 比较器：模型、12 个信号、`BenignDivergenceClassifier`、豁免（waivers） |
| `gate/MethodFlow` | 控制流，只被分类器使用，比较本身不用它 |
| `pipeline/` | ingest → decompile → normalize → build → gate → substitute → report |
| `agents/` | Agent skill 与插件；`agents/skills/anydecoder/SKILL.md` 是唯一参考文件 |
| `docs/` | `verification.md`（判据与容差）、`pitfalls.md`（反编译失败模式）、`architecture.md` |
| `validation/` | 真实语料、门禁输出、裁决记录；除脚本与文档外全部 gitignore |
| `examples/` | `demo.sh`、`demo.gif`、`demo.png`、走查文档 |
| `docs/index.md` / `docs/index.zh.md` | GitHub Pages 上的两篇长文 |

12 个信号：`excp`、`athrow`、`invokes`、`fields`、`consts`、`smallConsts`、`instrCount`、
`concat`、`lvt`、`throws`、`api`、`members`（`concat` 由 `StringBuilderSignal` 提供；
信号清单以 `ClassGate.signalIds()` 动态生成为准，不要在文档里手工维护第二份）。

## 3. 构建、测试、发布

```bash
JAVA_HOME=<JDK17> mvn verify
```

- 用户级 `JAVA_HOME` 已 setx 为 `C:\Users\anyan\.jdks\corretto-17.0.6`（2026-10-13 起）；
  系统级仍是 JDK 8（给其他项目用）。新 shell 里构建前先 `java -version` 确认，拿错了就
  显式覆盖 `JAVA_HOME`。
- Maven 3.6.3。`.mvn/maven.config` 与 `.mvn/maven-settings.xml` 都被 gitignore：本机 Maven
  把 `central` 改写成不可达的内网 Nexus，settings 把所有仓库镜像到 Maven Central。
- **Maven 3.6.3 的 `maven.config` 不支持 `#` 注释**。解析器按空白分词，注释会被当成非法参数，
  直接打印 usage 退出。该文件只放一行参数。
- 本地仓库在 D 盘；不要把缓存或构建产物写进 C 盘。
- `mvn verify` 全绿是提交前的最低要求；测试数 108（2026-10-22）。
- CI：GitHub Actions，Ubuntu/Windows × JDK 17/21 四矩阵；`main` 分支；Actions 已升级到
  checkout v7、setup-java v6、upload-artifact v7。
- 发布：`v*` 标签触发 `.github/workflows/release.yml`，上传 shaded jar 与 `SKILL.md`；
  稳定地址用 `/releases/latest/download/<asset>`。workflow 在 tag 时跑真实反编译冒烟
  （`-o .` 与 `-o ./out`）并校验 `plugin.json` 版本，这两步不经过普通 push，只有打标签才执行。

## 4. 已知陷阱（都是实际踩过的）

**分支目标只在分析里用。** `Insn` 里的跳转目标是分析用的，故意不进入 `render()`。偏移量在
不同编译器之间会变；比较器一旦看见它，就会把每个方法都报成不同。

**验证语料是另一个项目。** 语料、它的 class、它的反编译产物都不属于本仓库，`validation/*`
除脚本和文档外全部忽略。不要把语料内容贴进仓库、提交信息或公开讨论。

**JVM 惰性加载 jar。** 长跑期间不要重建 jar；替换正在被加载的 jar 会导致
`ClassNotFoundException`，而类明明就在 jar 里。长跑前把 jar 复制到 `target/` 之外冻结。
见 `docs/pitfalls.md`。

**`git push` 的 HTTP/2 假成功。** 本机推 GitHub 可能报
`curl 16 Error in the HTTP2 framing layer`，随后仍打印 `Everything up-to-date`，但实际没推上去。
不要信结尾输出：用 `git ls-remote origin refs/heads/main` 对比 `git rev-parse HEAD`，再用
`git -c http.version=HTTP/1.1 push origin main` 重试。

**Windows 路径映射。** Git Bash 的 `/d/...` 传给 Node 会被解析成 `C:\d\...`；使用 `D:/...`。
`/tmp` 与 `$TMPDIR` 可能指向不同位置，Node 写入的文件 Read 工具可能找不到。本机 Python 是
Windows Store 占位程序（退出码 49），也没有 `sqlite3`；需要读 SQLite 时用
`node --experimental-sqlite` 的 `node:sqlite`。

**控制台乱码不等于文件坏。** `report.md` 里的 em dash 在 Windows 控制台显示为乱码，文件本身是
正确的 UTF-8。不要强行改 stdout 编码，那会让原生 cmd/PowerShell 更乱。

**jar 不可复现。** zip 时间戳与条目顺序不同，发布 jar 与本地 jar 摘要不同是正常的；只有
`SKILL.md` 这类纯文本资产可以逐字节比对。

**发布前必须用发布物本身冒烟。** v0.1.0 是在 zip-slip 修复之前切的，按 `/releases/latest`
安装会拿到会崩的 jar，于是补了 v0.1.1。每次发布至少验证 `--version`、`-o .` 与 `-o ./out`
两种输出路径，并用发布 jar 跑一遍 demo。

**README 里的图片路径。** `examples/demo.gif` 由 README 通过 `raw/main` 引用，移动或改名会直接
破图；它不是 release 资产。

**不要复用系统字体排版静态图。** SVG 在等宽字体更宽的机器上会裁字，社交预览图改用 PNG。

**中文路径会让 javac 静默失败。** 语料路径含中文时 javac 报 file not found（退出码 2），需加
`-J-Dsun.jnu.encoding=UTF-8 -J-Dfile.encoding=UTF-8`；更稳妥的做法是把工作目录放在 ASCII
相对路径下。Git Bash 下 `-cp` 的分隔符是分号，且引号内的 `*` 通配符不会被展开，要逐 jar 拼接。

**zip-slip 误报的根因是 `-o ./` 前缀。** `ArtifactScanner.unpack` 曾因输出目录带 `./` 前缀而把
`META-INF/MANIFEST.MF` 误判为逃逸条目，拒绝真实 jar。修复后 `-o .` 与 `-o ./out` 两种形式
都要测（这也是发布冒烟的一部分）。

## 5. 验证方法论（历史结论）

- `instrCount` 容差 = 相对 15% + 绝对 6 条指令，两者缺一不可。只用相对阈值会在小型访问器上
  误报；九条指令合法缩减为五条正是布尔物化现象。该信号定为 `SUSPECT`，不是 hard。
- 42 个硬失败里有 39 个来自同一个原因：受保护范围切分被误读为破坏。异常表归一化就是为了它。
- `WAIVED` 与 `BENIGN` 刻意分开：一个是编译器事实，一个是可争议的决定。
- 豁免按「类 + 信号」匹配，绝不按类单独匹配；缺 reason 或 evidence 的条目被拒绝并报告行号；
  差异已消失的过期豁免会被提示删除；门禁从不标记的类会归入"匹配不到"。
- 已确认的良性/结构性模式：相邻字符串字面量折叠、空字面量 append、装箱与接收者收窄并存、
  `String.valueOf(int)` 与 `String.valueOf(Object)` 等价（必须排除 `char[]` 重载）、
  被静态字段遮蔽的类型名导致死读取、`synchronized` 监视表达式被提升为死局部变量。
- 字段存储提升规则两头都踩过：先发现判据缺「每路径恰好一次存储」校验（bd263b9 加护栏），
  又发现护栏过度保守、拒绝规则本就要豁免的形态（删护栏后 DAMAGED 从 12 回升到 28，
  8 个真缺陷中 16 个类一度被误判为良性）。改判据时两个方向都要量测。
- 仍保守上报、未编码为豁免的：三元的字段存储次数差（老编译器每个分支各存一次）、循环旋转、
  只有一次迭代的循环、以及"每轮末尾写、下轮开头读"这类需要真正 CFG 的形状。
- JDK 8 与 JDK 17 `--release 8` 编译同一基线，报出的硬发现完全相同，说明剩余差异是结构性的，
  不是编译器世代差异。不要为此改编译策略。
- 新增信号或类别必须带测量：先有语料上区分真实差异与编译器噪音的结果，再写代码。

## 6. 项目现状（截至 2026-10-21）

- 仓库：<https://github.com/anyanyna/anydecoder>，public，默认分支 `main`，Apache-2.0。
- 版本：`0.1.1`；latest release 含 jar 与 `SKILL.md`；GitHub Pages：
  <https://anyanyna.github.io/anydecoder/>。
- 定位：agent-first。README 教人把 skill 装给 Claude Code、Codex、Cursor、Trae，CLI 是底座。
- README 约 152 行（英文）/ 139 行（中文），"See it work" 在第一屏。
- 权威结果在 `validation/RESULTS.md`、`examples/README.md`、`docs/verification.md`。
- 真实语料（2984 个 class）上恢复出 2451 个源文件，门禁找到 8 个真实缺陷；其中 2 个是当年
  人工核查漏掉的。
- Commons Lang 3.14.0 演示：404 个 class → 247 个源文件，其中 9 个编译不过；
  门禁到达 366 个，357 个安全，9 个需复核，38 个未到达（不算通过）。
- 推广基线：仓库访问数曾为 0，克隆主要来自 CI。Phase 0（样例、GIF、社交预览图）与
  Phase 1（两篇文章、Pages）已完成；渠道文案与答疑稿在 `validation/out/`（已 gitignore）。

## 7. 未决事项

- 44 个 `SUSPECT` 仍未收敛，主要是字段存储次数；循环形状无法与真正的两次连续写区分，
  维持保守上报是正确的默认。
- `InputTaskManageController.calc` 是唯一有意保留的未确证项，没有豁免。给它一个说得通的故事
  不算查过。
- 22 个 `HARNESS_ERROR` 主要来自 `LicenseMgr` 使用 Java 9 已移除的 `sun.misc.Base64*`，
  `--release 8` 下拿不到这些类。
- 语料里剩余的死装箱局部变量（`Object bl = ...`）属于源码修复工作，不是门禁缺陷；不要为了
  消灭它们而放宽比较器。
- 推广的动手部分需要用户本人：Hacker News 账号需要先正常使用一段时间再发 Show HN；
  awesome list 提交与评论区应答不能代发。V2EX 因 `$VEX` 代币门槛已暂缓，首发改为掘金。
- `rea` / `rea-java-integration` 是另一条工作线（向 `morluto/rea` 贡献反编译能力），不要在
  anydecoder 会话里混线推进；该线目前阻塞于上游对 issue #1558 的表态。

## 8. 历史时间线

- **2026-10-13**：门禁在纯净恢复基准上压误报；引入 waivers；修掉分类器的空循环豁免、
  合成方法比对、路径敏感字段存储判据等自身缺陷；确立"类别解释更多就能隐藏更多"。
- **2026-10-15**：确认 Apache-2.0，公开仓库；`master` 改名 `main`；CI Actions 升级；
  `claude-plugin/` 改名 `agents/`；README 从 CLI 说明书改成 agent-first。
- **2026-10-16**：发布 v0.1.0/v0.1.1；生成 demo GIF/PNG 与社交预览图；上线 Pages；
  用 Commons Lang 做真实演示；补 issue 模板、徽章、Discussions；推广 Phase 0/1 完成。
- **2026-10-21**：追溯确认"合并到 rea"的请求出自 rea 项目自己的会话，不在 anydecoder；
  两条工作线相互独立。anydecoder 侧停在推广等待用户动手的状态。

## 9. 与新会话的交接约定

- 默认用中文回复；代码注释、文档、解释也用中文，除非用户明确要求英文。
- 改动要外科手术式：只碰必须碰的；不要顺手重构相邻代码；不要删除既有死代码，只清理自己
  造成的孤儿。
- 除非用户明确要求，不要提交、不要推送、不要发布。
- 用户多次说过"我不看，都交给你""不要停，不要问"。在这种全权委托模式下应持续推进并自行
  验证；但涉及改全局环境、推送、发布、删除数据时仍需确认。
- 若用户粘贴凭据：不要在回复里复述；会话 jsonl 与 claude-mem 都会持久留存，先建议轮换凭据，
  再做清理。清理不能替代改密。
- 先读 `docs/verification.md` 再改比较器；先读 `docs/pitfalls.md` 再改反编译/编译器相关逻辑。
