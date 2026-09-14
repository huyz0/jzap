# Getting started

jzap is not published to Maven Central or the Gradle Plugin Portal yet, so today it is built
from source. Everything below is tested; [Status](status.md) records what is not built.

```bash
git clone https://github.com/huyz0/jzap.git
cd jzap
./gradlew build                 # all unit and end-to-end tests
./gradlew :jzap-cli:installDist # the CLI, at jzap-cli/build/install/jzap/bin/jzap
```

## Gradle

```groovy
plugins {
    id 'java'
    id 'io.github.huyz0.jzap'
}

jzap {
    threads = 4
    threshold = 80
    cacheDir = layout.buildDirectory.dir("jzap-cache")   // optional, off by default
}
```

```bash
./gradlew mutationTest        # the whole module
./gradlew mutationTestDiff    # only lines changed since HEAD, including uncommitted work
./gradlew mutationTestAll     # a whole multi-module reactor in one invocation
```

The plugin computes the project model from your source sets and toolchain, forks the engine, and
declares its inputs and outputs so Gradle can skip the task when nothing relevant changed. It is
configuration-cache compatible.

`engineVersion` sets which engine build to fork, independently of the plugin's own version — the
way `pitestVersion` works for gradle-pitest-plugin. To run a local engine build instead, point
`engineClasspath` at its jars:

```groovy
jzap {
    engineClasspath.setFrom(fileTree("path/to/jzap/lib") { include("*.jar") })
}
```

??? note "Every extension property"
    `engineVersion`, `engineClasspath`, `threads`, `reporters`, `from`, `to`, `scope`,
    `includeClasses`, `excludeClasses`, `mutators`, `mutateLoopCounters`, `failOnSurvivors`,
    `jvmArgs`, `threshold`, `cacheDir`.

!!! tip "`threshold` in a Groovy build script"
    Write `threshold = 80`, not `threshold = "80"`. The setter takes a `Number`, so an integer
    literal is fine and a string is not.

### `mutationTestAll` and why per-module is the wrong default

A library module whose tests live elsewhere reports every mutant as uncovered when analysed on
its own, and the score then means nothing. `mutationTestAll` emits one model listing every
module, so a test in one module can kill a mutant in another.

It can be diff-scoped like any other run — see [the range table below](#in-ci) — which is what
makes it useful on a pull request touching several modules at once. Given no range it analyses
every module in full.

Maven has no reactor-wide equivalent yet.

## Maven

```xml
<plugin>
  <groupId>io.github.huyz0</groupId>
  <artifactId>jzap-maven-plugin</artifactId>
  <version>0.1.0</version>
  <configuration>
    <threshold>80</threshold>
  </configuration>
</plugin>
```

```bash
mvn verify                      # bound to the verify phase
mvn jzap:mutationCoverage       # or on its own
```

Every parameter is also settable from the command line: `jzap.threads`, `jzap.threshold`,
`jzap.reporters`, `jzap.from`, `jzap.to`, `jzap.scope`, `jzap.mutators`, `jzap.includeClasses`,
`jzap.excludeClasses`, `jzap.cacheDir`, `jzap.failOnSurvivors`, `jzap.jvmArgs`, `jzap.skip`.

A reactor-wide single invocation is not built for Maven yet; the Gradle plugin has one.

## The CLI

The engine knows nothing about build tools. It takes a **project model** — a versioned JSON
document listing classpaths, source roots and toolchain — which an adapter normally produces.
The sample fixture's own build writes one, which is enough to try the tool:

```bash
./gradlew :jzap-cli:installDist :fixtures:sample-java:writeProjectModel
./jzap-cli/build/install/jzap/bin/jzap run -m build/fixture-model.json -o build/reports/jzap
```

```
Surviving mutants (2):
  sample/Discount.java
    line 10: changed conditional boundary: <= became < [CONDITIONALS_BOUNDARY]
      if (percent > 50) {
    line 17: replaced boolean return with true [TRUE_RETURNS]
      return price == 0;

  killed           7
  survived         2
  no coverage      3
  ------------------
  total           12

Mutation score 58.3%  (test strength 77.8%, ignoring uncovered mutants)
```

Each survivor is the actionable output, which is why the report leads with them rather than with
the score.

### Commands

```bash
jzap run           -m model.json [options]
jzap list-mutants  -m model.json          # the inventory, without running a single test
jzap mutators                             # the available mutators
jzap daemon        -m model.json          # start a resident engine for this model
jzap daemon        -m model.json --status # is one running?
jzap daemon        -m model.json --stop   # stop it
```

### Options

| Option | What it does |
|---|---|
| `-m`, `--project-model FILE` | The project model. Required. |
| `-o`, `--report-dir DIR` | Where reports go. Default `build/reports/jzap`. |
| `-r`, `--reporters ID,...` | Which reporters to run. Default: whatever the model asks for. |
| `-t`, `--threads N` | Analysis threads. Default: from the model. |
| `--from REF` / `--to REF` | Scope to a git range. `-Local-` means the working tree, `-Empty-` the empty tree. |
| `--patch FILE` | Scope to a unified diff, with no repository present. |
| `--scope line\|class` | Diff granularity. Line by default. |
| `--all` | Analyse everything, ignoring any diff scoping in the model. |
| `--mutators ID,...` | Restrict the mutator set. `EXTREME` selects whole-body replacement. |
| `--include GLOB` / `--exclude GLOB` | Class filters, matched against binary class names. |
| `--cache-dir DIR` | Reuse verdicts from a previous run stored here, and update it. |
| `--daemon` | Hand the work to a resident jzap, starting one if needed. |
| `--engine schemata\|naive` | `naive` is the slow reference implementation, kept for differential testing. |
| `--threshold PERCENT` | Exit 1 if the mutation score falls below this. |
| `--fail-on-survivors` | Exit 1 if any mutant survives. |
| `--dry-run` | Resolve the model and scope, print what would be analysed, and stop. |
| `-q`, `--quiet` | Suppress progress output. |

Four more switch on mutant reduction, all off by default and all measured — see
[Mutators and filters](mutators.md): `--dedup`, `--arid`, `--one-per-line`,
`--mutate-loop-counters`, `--mutate-kotlin-internals`.

## Scoping to a diff

This is the part that makes mutation testing viable on a pull request. A full run analyses every
mutant in the module; a diff-scoped run analyses only the lines the branch changed.

```bash
jzap run -m model.json --from origin/main --to -Local-   # branch, including uncommitted work
jzap run -m model.json --patch pr.diff                   # no repository needed
```

!!! important "The range selects, it does not check out"
    Analysis always runs against the currently compiled code. The git range only decides which
    mutants are in scope; nothing is checked out, and no commit is built. This is the semantic
    both Mull and arcmutate document as a source of confusion, so jzap prints it on every
    diff-scoped dry run.

Renames are not followed. A renamed file's every line looks changed, which would flood a pull
request with mutants for code nobody touched.

## Reading the result

Five reporters, selectable with `-r`:

| Id | Output |
|---|---|
| `console` | The terminal summary, survivors first |
| `json` | `jzap-result.json` — jzap's own schema, with the phase timings |
| `elements` | `mutation-test-elements.json` — the mutation-testing-elements schema, for Stryker's viewer and the dashboards built on it |
| `html` | A self-contained page. No scripts, no external fetches |
| `annotations` | One pull-request comment per survivor |

`elements` exists so an existing team can point their current dashboard at jzap without writing
anything.

### Verdicts

| Status | Meaning |
|---|---|
| `KILLED` | A test failed with the mutant active: the suite detects this fault |
| `SURVIVED` | Tests covered the mutated code and all passed: an undetected fault |
| `NO_COVERAGE` | No test executes the mutated code |
| `TIMED_OUT` | Tests hung with the mutant active. Counted as detected; never cached |
| `NON_VIABLE` | The mutated class failed verification or linkage: not a real fault |
| `RUN_ERROR` | The analysis itself failed. A jzap bug or an environment problem |

**Mutation score** is detected over every scored mutant. **Test strength** is the same ratio over
covered mutants only, which is what PIT calls test strength.

`NON_VIABLE` and `RUN_ERROR` are in neither. A mutant the JVM refused to load was never run
against a test, and one whose analysis broke was never judged either — so counting it as
undetected would blame your suite for jzap's problem, and counting it as detected (which is what
PIT does) would credit your suite for a fault it never saw. Both are reported, and the console and
HTML reports name them so the totals reconcile. This also makes jzap's own score agree with the
`elements` report it writes, where the two are `CompileError` and `RuntimeError` and the score is
defined over valid mutants only.

### Exit codes

| Code | Meaning |
|---|---|
| `0` | The run met its bar |
| `1` | It did not — `--threshold` or `--fail-on-survivors` |
| `2` | Usage error: a bad option, a missing model, an unreadable one |
| `3` | The analysis itself failed |

`1` and `3` are kept apart deliberately: one says your tests are weak and the other says there is
no result, and CI treats them differently. A `RUN_ERROR` exits `3` even when the remaining mutants
met the threshold, because a run that broke over most of its scope must not hand CI a green build
and a good percentage.

## Speed, once it works

Each of these is measured in [Measured results](performance.md).

```bash
jzap run -m model.json --threads 4                       # partition by class across JVMs
jzap run -m model.json --cache-dir .jzap/cache           # reuse verdicts; 0.42s on the bench fixture
jzap run -m model.json --daemon                          # skip jzap's own JVM startup; 0.17s
jzap run -m model.json --from origin/main --to -Local-   # analyse the branch, not the repository
```

The cache is opt-in rather than on by default: a cache whose whole question is whether reuse is
sound should not start reusing without being asked. It is keyed on bytecode, mutators, filters,
engine version and toolchain, and prints why it discarded anything it discarded.

## In CI

`mutationTestDiff` defaults to `from = HEAD`, `to = -Local-`: work that is changed but not
committed yet, which is the right range on a developer machine and the wrong one in CI, where the
interesting range is against the base branch. `JZAP_FROM` and `JZAP_TO` supply it without an edit
to the build script:

```yaml
- uses: actions/checkout@v5
  with:
    fetch-depth: 0        # a shallow clone has no base ref to diff against
- run: ./gradlew mutationTestDiff
  env:
    JZAP_FROM: origin/${{ github.base_ref }}
    JZAP_TO: -Local-
```

The `fetch-depth: 0` is not optional. `actions/checkout` clones one commit by default, and a
diff-scoped run against a ref that is not in the repository resolves to nothing in scope — which
reports a clean run rather than an error.

Both are read as tracked Gradle inputs, so changing the range invalidates the configuration cache
and re-runs the task rather than leaving it up to date with the previous range's verdicts. A value
set in the `jzap` block wins over the environment: the build script is the explicit statement, and
a project that has committed to a range should not have it changed by whatever is exported into
the shell.

| Task | Range |
|---|---|
| `mutationTest` | none — a full run by definition, and it ignores both |
| `mutationTestDiff` | `jzap` block, else `JZAP_FROM`/`JZAP_TO`, else `HEAD`..`-Local-` |
| `mutationTestAll` | `jzap` block, else `JZAP_FROM`/`JZAP_TO`, else every module in full |

Maven needs nothing extra: `jzap.from` and `jzap.to` are ordinary parameters, so
`-Djzap.from=origin/main` or a `${env.JZAP_FROM}` in the POM both work.

Two things matter for a build that will be cached or compared:

- **Results are deterministic.** Identical model plus identical bytecode produces identical
  reports, asserted at 1, 2 and 8 threads. Thread count leaking into a result would break
  Gradle's build cache.
- **Hang detection is deterministic too.** A mutant that never returns is caught by counting loop
  iterations, not by watching the clock, so a one-millisecond wall-clock budget and a thirty-second
  one produce byte-identical reports. The wall-clock timeout remains only as a backstop for mutants
  that block rather than loop.

When something does not work, [Troubleshooting](troubleshooting.md) is ordered by how often each
failure actually comes up. The fastest diagnostic is almost always:

```bash
jzap run -m model.json --dry-run
```

which prints the resolved scope, classpaths and module list without analysing anything.
