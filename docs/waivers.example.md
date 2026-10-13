# Known divergences — template

Copy this beside the class files you are gating, as `anydecoder-waivers.md`, and fill it in. The
gate picks it up without being told; `--waivers <path>` points elsewhere.

Every entry is a difference between the recovered source and the original class files that has
been investigated and accepted. Delete entries freely — the gate reports any that no longer match
anything, which means the difference is gone.

**Both the reason and the evidence are required.** An entry missing either is not applied and is
reported under *Waivers refused*. A list of class names saying "we looked at this" reads exactly
like one saying "we gave up", and only one of those is worth having.

- `class` — `com/example/Foo` or `com/example/Foo`, either notation
- `signal` — `excp`, `athrow`, `invokes`, `fields`, `consts`, `smallConsts`, `instrCount`, `lvt`,
  `throws`, `api`, `members`. Several, comma separated. `*` for every signal on the class, which
  hides the most and should be a deliberate choice.

| class | signal | reason | evidence |
|---|---|---|---|
| com/example/Foo | excp | local authentication added after recovery; the difference is the feature, not the decompiler | commit `03433271` adds the branch; the original class has no exception table for that method |
| com/example/Bar | consts | adjacent string literals folded by the newer compiler | `javap -c`: `ldc "a"; ldc "b"; append; append` in the original becomes `ldc "ab"; append` |
| com/example/Baz | fields | a field is stored once per branch of a conditional by the older compiler and once after the join by javac | `javap -c`: 5 `putfield x` in the original, 4 in the rebuild; same value, field never read back |