# FineLock: Automatically Refactoring Coarse-grained Locks into Fine-grained Locks

This work was published at [**ISSTA 2020 Tool Demonstration**](https://dl.acm.org/doi/abs/10.1145/3395363.3404368) ([paper.pdf](paper.pdf)).

```latex
@inproceedings{zhang2020finelock,
  title={FineLock: automatically refactoring coarse-grained locks into fine-grained locks},
  author={Zhang, Yang and Shao, Shuai and Zhai, Juan and Ma, Shiqing},
  booktitle={Proceedings of the 29th ACM SIGSOFT International Symposium on Software Testing and Analysis},
  pages={565--568},
  year={2020}
}
```

FineLock converts `synchronized` methods and blocks into `ReentrantReadWriteLock` locks: read locks,
write locks, **splitting locks**, and **downgrading locks**.

> FineLock 2 is a re-implementation from the paper. The original Eclipse/WALA prototype's source was
> lost (what was left is in [`legacy-eclipse/`](legacy-eclipse)). The engine no longer depends on an
> IDE. It ships with a command-line tool and an IntelliJ IDEA plugin.

## Modules

| module | what |
|---|---|
| [`finelock-core`](finelock-core) | the analysis and transformation engine (JavaParser + symbol solver), no IDE dependency |
| [`finelock-cli`](finelock-cli) | `finelock` command line tool: batch refactoring, reports, diffs |
| [`finelock-intellij`](finelock-intellij) | IntelliJ IDEA plugin (2025.3+): *Refactor → Convert to Fine-Grained Locks (FineLock)…* |
| [`examples/demo`](examples/demo) | small Maven project with the paper's Figure 1 example, for trying the tool |
| [`legacy-eclipse`](legacy-eclipse) | what was left of the original Eclipse plugin |

## Quick start

Requirements: **JDK 21** (`java -version`). Everything else is downloaded by the Gradle wrapper.

### 1. Build and test

```bash
./gradlew :finelock-core:test :finelock-cli:installDist
```

### 2. Command line

The CLI is installed at `finelock-cli/build/install/finelock/bin/finelock` (`finelock.bat` on Windows).
Try it on the bundled demo first. Nothing is written unless you pass `--apply` or `--out`.

```bash
finelock-cli/build/install/finelock/bin/finelock --diff examples/demo/src/main/java
```

Expected summary:

```
FineLock: 6 synchronized lock(s): 1 downgrading, 1 splitting, 1 read, 1 write, 0 merged, 2 unchanged
```

On your own project, point it at the source root (the directory that contains the package folders):

```bash
F=finelock-cli/build/install/finelock/bin/finelock
$F --report report.md /path/to/project/src/main/java          # Markdown report (like Table 1 of the paper)
$F --out /tmp/refactored /path/to/project/src/main/java       # write refactored copies elsewhere
$F --apply /path/to/project/src/main/java                     # rewrite the files in place (commit first!)
```

Library types are resolved more precisely if you pass the project's classpath. For a Maven project:

```bash
(cd /path/to/project && mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt)
$F --cp "$(cat /path/to/project/cp.txt)" --diff /path/to/project/src/main/java
```

All options (`finelock --help`):

| option | meaning |
|---|---|
| `--diff` | print a unified diff of all changes |
| `--report <file>` | write the Markdown report |
| `--apply` | rewrite the files in place |
| `--out <dir>` | write the changed files under `<dir>` instead |
| `--cp <jars>` | classpath used to resolve library types (`:`-separated, `;` on Windows) |
| `--only <glob>` | only refactor matching files, e.g. `'**/cache/*.java'` |
| `--no-downgrade`, `--no-split` | do not infer downgrading / splitting locks |
| `--fair` | create `new ReentrantReadWriteLock(true)` |
| `--read-only <Type#method>` | treat a library method as read-only, e.g. `com.google.common.collect.ImmutableMap#*` |

### 3. IntelliJ IDEA plugin

```bash
./gradlew :finelock-intellij:runIde        # sandbox IDE with the plugin, opens examples/demo
./gradlew :finelock-intellij:buildPlugin   # plugin zip in finelock-intellij/build/distributions/
```

The first run downloads IntelliJ IDEA 2025.3 (about 5 GB in `~/.gradle`) to build against.

To install into your own IDE (2025.3 or newer): *Settings → Plugins → ⚙ → Install Plugin from Disk…*,
then pick `finelock-intellij/build/distributions/finelock-intellij-2.0.0.zip` and restart.

Usage:

1. Right-click a Java file, a package or the project in the Project view, or open the **Refactor** menu,
   and choose **Convert to Fine-Grained Locks (FineLock)…**.
2. Pick the options and click **Analyse**. The table lists every synchronized method/block with its
   pattern, the inferred lock, and why a weaker lock was chosen. Double-click a row to jump to the code.
3. **Show Diff** previews all files; **Apply** writes them as one command, so one Undo reverts everything.

The source roots and library jars come from the IDE's project model, so open a project whose Maven or
Gradle import has finished.

## How it works (Section 3 of the paper)

1. **Lock Locator** finds every synchronized method and block and its monitor.
2. **Lockset Analysis** maps every monitor to one `ReentrantReadWriteLock` field. Sections on the same
   monitor share a lock: `this` of a class and its superclasses, `X.class`, and final fields that hold
   a new object.
3. **Read-Write Identifier** classifies each statement as read or write using a whole-program
   side-effect analysis (fixpoint over the call graph, including overriding methods).
4. **Pattern Generator** encodes the section as a string over `r w c e`. For example, Figure 1(a) is `cwer`.
5. **Pushdown Automaton** (Figure 3) infers the lock from the pattern:

| final state | lock | generated code |
|---|---|---|
| q1 | read lock | `readLock` around the body |
| q2 / halted (V) | write lock | `writeLock` around the body |
| q3 | splitting (read → write) | reads under `readLock`, then writes under `writeLock` |
| q4 | splitting (conditional write) | `readLock`; when the condition holds: upgrade, re-check, write |
| q5 | splitting (write → read) | `writeLock`, then atomic downgrade to `readLock` |
| q6 | downgrading lock | Figure 1(c): read, upgrade + re-check + write, downgrade, read |

6. **Transformation** rewrites the code. Every unlock is in a `finally` block, the import and lock
   field are added, and code that is not touched keeps its formatting and comments.

### Differences from the paper

FineLock 2 keeps the automaton of Figure 3 and adds the safety checks a real refactoring needs:

- **No upgrade deadlocks.** `ReentrantReadWriteLock` cannot upgrade, so a read-locked caller that
  enters a write-locked method on the same lock would deadlock. Such calls are writes (rule 2), so
  the caller holds the write lock at that point.
- **Figure 1(c) fix.** In the figure, the read lock is not re-acquired when the re-check at line 47
  fails, so line 58 would throw `IllegalMonitorStateException`. FineLock re-acquires the read lock in
  the inner `finally`, before releasing the write lock.
- **Stale reads.** A splitting or downgrading lock is used only when nothing computed under the read
  lock before the lock changes is used afterwards: no locals that flow across it, and no early
  `return`/`throw`. Re-evaluated conditions must have no side effects at all. Otherwise FineLock falls
  back to the write lock and says why in the report.
- **All or nothing per monitor.** If any use of a monitor cannot be converted (`wait`/`notify`,
  `Thread.holdsLock`, `synchronized` on the same object through a parameter, a non-final field, or
  from another class), no section of that monitor is changed.
- **Library methods.** Rule 3 (unknown library code is a write) is kept, but a curated list of JDK
  read-only methods (`String`, `Map.get`, `List.size`, streams, …) avoids treating every
  `map.get()` as a write. Add your own with `--read-only`.
- Alias analysis is syntactic instead of WALA-based. It is conservative: monitors it cannot resolve
  are left as `synchronized`.

On HSQLDB 2.7.4 (one of the paper's subjects), FineLock 2 analyses 726 synchronized locks in about
8 s: 15 downgrading, 94 splitting, 64 read, 477 write, and 76 left unchanged. The refactored HSQLDB
compiles without errors.
