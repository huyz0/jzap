# Troubleshooting

The failures people actually hit, what each one means, and what to do about it. Ordered by how
often they come up rather than by severity.

## "no mutants found"

jzap analysed the classes it was given and none of them yielded a mutant. Usually one of:

- **The code was not compiled.** `mutableCodePaths` points at a directory that does not exist or is
  empty. `jzap run --dry-run` prints the resolved paths; check them before anything else.
- **Everything is out of scope.** On a diff-scoped run this is the normal, healthy case: the
  changed lines carry no mutants. The scope line in the report says how many lines were in scope.
- **The include/exclude globs exclude everything.** `--include com.example.*` matches binary class
  names, so a typo in a package name silently matches nothing.
- **The classes carry no line numbers.** Compiled without `-g` or with `-g:none`, a class has no
  line information, and a mutant that cannot be pointed at any source line is not reported. Compile
  with debug information, which is the default for both Gradle and Maven.

## Every mutant says NO_COVERAGE

No test executed the mutated code. Distinguish two cases from the report's `tests discovered` line:

- **Zero tests discovered.** Test discovery found nothing. The usual causes are a missing
  `junit-platform-launcher` on the test runtime classpath, or `testClassPaths` pointing at the wrong
  directory. Both show up in `--dry-run`.
- **Tests discovered, but nothing covered.** The tests run but do not reach the mutated classes,
  which for a library module means its tests live in another module. Analyse the modules together:
  `mutationTestAll` in Gradle, or one project model listing every module.

## "test already fails before any mutant is applied"

Exactly what it says, and it matters more than it looks. A mutant covered by an already-failing test
would be reported as killed by a failure that has nothing to do with it, so those tests are excluded
from selection. Fix the test suite first; until then the score understates coverage, because
mutants only those tests reach come back as uncovered.

## The analysis JVM does not start, or dies immediately

The message includes the exact command line. Run it by hand: it is an ordinary `java` invocation and
fails the same way outside jzap. Common causes are a classpath entry that no longer exists after a
`clean`, and a `javaHome` pointing at a JRE without the tools jzap needs.

## Scores differ between runs

They should not. If they do, in order of likelihood:

1. **A test in the project is not deterministic.** Run the suite twice on its own before blaming
   jzap; a test whose result varies makes every verdict that depends on it vary too.
2. **Wall-clock timeouts.** A mutant that blocks rather than loops is caught by a wall-clock
   backstop, and that is not reproducible. Loop-based hangs are detected by counting iterations and
   are deterministic. `TIMED_OUT` is never cached for exactly this reason.
3. **A stale cache.** `--cache-dir` reuse is keyed on bytecode, mutators, filters and toolchain, so a
   mismatch discards it — but a cache shared between machines with different JDKs is refused rather
   than trusted, and the reason is printed.

## The cache is not being reused

The run prints why it was discarded. The usual answers:

- **The toolchain changed.** Bytecode differs between `javac` versions and platforms, so a cache
  records the toolchain that wrote it and refuses to be read under another.
- **The filter or mutator set changed.** Both change the inventory, so verdicts recorded under one
  set say nothing about another.
- **The engine version changed.** Cached verdicts are only meaningful for the engine that produced
  them.

## Kotlin: mutants in code nobody wrote

Should not happen: property accessors, data class members, null-check intrinsics and for-each loop
scaffolding are filtered by default. If you see one, it is a gap in those rules and worth reporting
with the mutant key. `--mutate-kotlin-internals` turns the filters off, which is occasionally useful
for seeing what is being suppressed.

## Kotest: nothing is discovered

Kotest builds its test tree when a spec runs rather than when the platform asks what it contains, so
jzap takes its runnable unit from whatever the engine exposes — a spec, not a leaf test. If nothing
at all is found, check that `kotest-runner-junit5` is on the test runtime classpath, since without it
the engine is not registered with the JUnit Platform.

## The Gradle task is UP-TO-DATE when you expected it to run

It is doing what it was asked. The task's inputs are the compiled classes, the test classpath and the
configuration; if none changed, the previous report still stands. `--rerun-tasks` forces it.

## `threshold = 80` fails to configure in a Groovy build script

Use `threshold = 80` and not `threshold = "80"`. If you see a `BigDecimal` type error, you are on a
version before the `Number` setter; upgrade, or write `threshold = 80 as double`.

## Getting more out of a failure

- `jzap run --dry-run` prints the resolved scope, classpaths and module list without analysing
  anything. Most configuration problems are visible there.
- `jzap list-mutants --format table` prints the inventory without running a single test, which
  separates "no mutants" from "no coverage".
- The project model is a plain JSON file. Attach it to a bug report and the problem reproduces
  without your build tool in the loop.
