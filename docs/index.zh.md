# 反编译出来的代码永远看起来是对的。怎么证明它不是。

[English](index.md) · [仓库](https://github.com/anyanyna/anydecoder)

源码是会丢的。构建产物进了归档，仓库没进，某一天你手上只剩一个 `.class` 目录，没有回头路。

于是你跑一个反编译器，它交给你一份看起来就是那个程序的东西。这就是陷阱 —— 而且值得把原因说准：
**反编译器的输出永远看起来是对的。** 它是合法的 Java，在调试信息还在的地方变量名还挺像样，读起来
像人写的，因为它重建的那个形状本来就是人写的。

问题在于，「看起来对」和「是对的」在一个你读代码读不出来的地方分开了。

## 代价有多大：拿一个你肯定用过的库来说

我把工具跑在了 Apache Commons Lang 3.14.0 上 —— 404 个 class 文件，一个几乎存在于所有 Java 应用
里的库。大约三十秒。

它恢复出的 247 个源文件里，**有 9 个根本编译不过。** 不是「编译出来的字节码不一样」，是编译不过。
`javac` 的原话：

```
ArrayUtils.java                    method getComponentType in class ClassUtils cannot be applied
                                     to given types; required: Class<T[]>, found: Class<T>
EnumUtils.java                     incompatible types: Enum cannot be converted to E
ObjectUtils.java                   clone() has protected access in java.lang.Object
ThreadUtils.java                   reference to findThreads is ambiguous; both
                                     findThreads(Predicate<Thread>) and
                                     findThreads(ThreadUtils.ThreadPredicate) match
FieldUtils.java                    reference to readField is ambiguous; both
                                     readField(Field, Object, boolean) and
                                     readField(Object, String, boolean) match
StrBuilder.java                    method does not override or implement a method from a supertype
AbstractConcurrentInitializer.java unreported exception capture#1 of ? extends Exception
ExceptionUtils.java                unreported exception java.lang.Throwable
module-info.java                   modules are not supported in -source 8
```

只用反编译器，这九个问题你一个都不会知道。文件名是对的，包结构是对的。你要等到开始构建才发现 ——
而如果你正在恢复的正是那个**已经丢掉了构建**的项目，那可能是很久以后。

这九个文件占整棵树的 3.6%。这个数字没人会写在包装盒上。

## 能编译的，也不等于没问题

九个文件编译不过，这是响的那一声。安静的那一声更麻烦，因为没有任何东西会提醒你。

在那些编译得干干净净的文件里，判据点名了 `org.apache.commons.lang3.reflect.TypeUtils`。恢复出的
源码在这里，请把它当 Java 读：

```java
if (isAssignable(midClass, superClass) && isAssignable(genericInterface, midClass)) {
```

它编译得过，读起来逻辑通顺，做的就是你看到的事。

它还调用了和原著**不同的方法**。下面是 Commons Lang 3.14.0 的原始源码：

```java
if (isAssignable(midClass, superClass)
        && isAssignable(genericInterface, (Type) midClass)) {
```

答案在这里：`(Type) midClass`。作者写了一个显式强制转换，因为 `midClass` 的声明类型是 `Class<?>`，
不加转换 `javac` 会选择更窄的那个重载。**反编译器把这个转换丢掉了。**

这件事你可以亲眼看到。反汇编原始 class，调用目标就在那儿：

```
109: invokestatic  // Method isAssignable:(Ljava/lang/reflect/Type;Ljava/lang/Class;)Z
118: invokestatic  // Method isAssignable:(Ljava/lang/reflect/Type;Ljava/lang/reflect/Type;)Z
```

相邻两个偏移，两个不同的方法。重建后的代码调了第一个两次。

### 接下来才是关键

我不会告诉你 Commons Lang 坏了，因为我不知道，工具也不知道。在这个具体例子里两个重载行为一致：
`isAssignable(Type, Type)` 转派给 `isAssignable(Type, Type, Map)`，后者在目标是 `Class` 时又直接转派
回 `isAssignable(Type, Class)`。所以恢复出的源码很可能行为完全相同 —— 而且这一点你可以从上面的源码
自己看，不必信我。

工具报的东西更窄，而且它是一个事实而不是判断：**恢复出的源码调用了和原著不同的方法。**

值得为这件事出一份报告而不是耸耸肩，原因是：字节码里没有任何东西能区分

> 转换被丢掉了，而且恰好无害

和

> 转换被丢掉了，程序现在做的是另一件事

这两者看起来一模一样。而后者不是假想 —— 它就是上面那份清单里的 `ThreadUtils` 和 `FieldUtils`，
同一类类型信息丢失，把两个重载变成了连编译都过不了的歧义。

反编译器没法告诉你自己站在哪一边。这不是在批评反编译器，这是在陈述一个关于 class 文件里有什么
信息的事实：**用来分辨这两者的信息，从来就不在里面。**

## 判据

所以别问反编译器。去问编译器。

> **反编译 → 重新编译 → 拿重建的字节码与原 class 比对**

把反编译器产出的源码重新编译，然后逐方法、逐类地与它来源的那个 class 文件比对：异常表、调用目标、
字段访问、常量、rethrow 次数。如果重建结果做的是原 class 做的那些事，那么它来源的源码就是忠实的。
这是机械判据，不是观点问题，也不依赖任何人去读什么。

我把它做成了一个叫 anydecoder 的工具，上面那个运行就是它打印出来的。那次 commons-lang3 的运行：
404 个类里覆盖 366 个，357 个判为安全，9 个需要复核，38 个判据根本没能覆盖到。

**那 38 个不是通过，工具就是这么写的。** 它们是重建里没有对应产物的类，全部追溯到那 9 个编译不过的
文件。覆盖率永远和判定结果并排打印，因为**一个没有覆盖率的通过率是没有意义的数字**。

## 它不声称什么

这部分我最在意写准，因为一个夸大的检查器比没有检查器更糟 —— 你会不再去看它。

- **它不声称源码就是作者写的那个样子。** 变量名、注释、格式、循环写成 `for` 还是 `while`，都不在
  字节码里，不作任何声称。所谓「还原原始源码」是一件做不到的事。
- **它不声称没覆盖到的类是好的。** 见上。
- **它不为解释不了的差异开脱。** 这是整个设计所依赖的规矩。它认得一批编译器行为 —— 某个编译器怎么
  物化布尔值、另一个怎么切分异常保护区间 —— 但每一类都必须**指出产生该差异的结构模式，并证明这个
  模式确实存在**。没有「大概无害」的兜底，没有信心阈值，没有因为两个数字接近就触发的类别。识别不出
  来的差异仍然是差异，会到达一个人手上。
- **它比对的是结构，不是行为。** 两条同类型独立语句交换顺序，字节码信号完全一样。有一个可选的动态
  差分测试会真的把两个版本跑起来比对，在有条件驱动的方法上补上这个缺口；它默认关闭，因为它会执行
  真实代码。

## 怎么用

它以编码 Agent 的 skill 形式发布，因为机械判据给你的是**一份待办清单**，不是一份做完的工作。那次
commons-lang3 的运行产出了九个要看的类，每一个都带着触发它的信号和证据 —— 而把这份清单走完意味着
读反汇编、下判断、再试一次。这正好是 Agent 擅长而 shell 脚本做不了的事。

两步。把下面这段粘贴给 Claude Code、Codex、Cursor、Trae，或者任何读
[Agent Skills](https://agentskills.io) 规范的工具：

```
安装 anydecoder。

1. 下载 CLI。反编译器已经打包在里面，不需要构建：
     mkdir -p ~/.anydecoder
     curl -L -o ~/.anydecoder/anydecoder.jar \
       https://github.com/anyanyna/anydecoder/releases/latest/download/anydecoder.jar
2. 把 skill 装进你自己的 skills 目录，让当前和以后每个会话都能用上它。它就是一个文件：
     mkdir -p <你的-skills-目录>/anydecoder
     curl -L -o <你的-skills-目录>/anydecoder/SKILL.md \
       https://github.com/anyanyna/anydecoder/releases/latest/download/SKILL.md
3. 确认能跑：java -jar ~/.anydecoder/anydecoder.jar --version
```

然后直接说你要什么：

```
把 build/classes 的源码恢复出来，并证明它是忠实的。
```

运行它需要一个 JDK 17+，没有别的。

**https://github.com/anyanyna/anydecoder** —— Apache 2.0。

---

*这套方法来自一次为期九天的生产环境 Java Web 应用源码恢复：2984 个 class 文件，恢复出 2451 个源
文件，原始产物由 Eclipse 编译器 ecj 编译，用 javac 重建。那次工作找出了 6 个用编译器比对分辨不出的
真实缺陷。把同一份语料重新过一遍现在这套判据，找到的是 8 个 —— 多出的包括一段被截断的日志文本
（`集合深拷贝错误:` 成了 `深拷贝错误:`），以及一个被反编译器从 `synchronized` 监视表达式里提升出来的
死局部变量。它还暴露了检查器自身的 4 个 bug，其中 2 个是静默的 —— 检查器自己也需要被检查。这些全部
保留在仓库里。*
