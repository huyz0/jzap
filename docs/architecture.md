# jzap architecture: build-tool-agnostic core

Decision: the engine knows nothing about Gradle, Maven, or Bazel. Build tools are thin
adapters whose only job is to compute a project model and hand it to the engine. Gradle
and Maven integrations come after the core is usable from the CLI.

This is the proven shape for this problem. PIT splits into `pitest` (core),
`pitest-entry`, and
[`pitest-command-line`](https://github.com/hcoles/pitest/blob/master/pitest-command-line/src/main/java/org/pitest/mutationtest/commandline/MutationCoverageReport.java)
(a `main` taking `--targetClasses`, `--targetTests`, `--sourceDirs`, `--reportDir`, and
extra classpath entries), with `pitest-maven` as a mojo wrapper and
[`gradle-pitest-plugin`](https://github.com/szpak/gradle-pitest-plugin) forking a JVM
that runs that CLI. The Gradle plugin computes `taskClasspath`, `mutableCodePaths`, and
`sourceDirs` automatically, and passes the classpath as a file by default so Windows
command-line length limits don't bite. We take the same seam and make it explicit and
versioned rather than implicit in a CLI's argument list.

## Module layout

Nine modules with production code, in dependency order. Nothing below depends on anything
above it, and `ModuleBoundariesTest` asserts that against the imports rather than against the
build files, so the shape cannot drift one hurried import at a time.

    jzap-model      the project model and result schemas: the single seam. Depends on
                    nothing, and does not export Jackson -- a data module that pinned
                    every consumer to a serialiser's version would be a poor seam.

    jzap-wire       the controller/minion protocol, and the framing under it.
    jzap-agent      the java agent: class overrides, coverage probes, the loop guard,
                    and the schemata dispatch methods.
                    Both are dependency-free on purpose. They are loaded into the JVM
                    running the user's tests, where anything they dragged in could clash
                    with the project's own dependencies, and a test enforces it by
                    walking the agent jar's constant pool.

    jzap-minion     the forked JVM that runs the user's tests. Speaks the wire, uses the
                    agent, and knows nothing of the engine -- which is what lets the
                    engine treat it as a process to be killed.

    jzap-core       the engine: discovery, mutators, schemata, coverage, scheduling, the
                    cache, and the controller's half of the protocol. Reaches the agent
                    for compile-time constants only, and never git or reporting.

    jzap-git        a diff to changed line ranges, via JGit.
    jzap-report     console, jzap's own JSON, the mutation-testing-elements schema, HTML,
                    and PR annotations.
                    Siblings of the engine rather than layers of it: both see only the
                    model. That is what lets a CI system hand the engine a patch file
                    instead of a repository, and keeps reports independent of how
                    analysis works.

    jzap-cli        the composition root, and the only module that knows all of them.
                    Also hosts the resident daemon, which reuses the wire's framing.

    jzap-gradle     adapter: source sets, configurations, toolchains -> model.
    jzap-maven      adapter: reactor, dependency resolution -> model.
                    Neither links the engine; both fork the CLI. That keeps ASM off a
                    buildscript classpath and lets the engine's version move
                    independently of the plugin's.

### How much each module exposes

The rule is that a type is public only when something outside its own package needs it.
`jzap-core` is nine public types of thirty-six; `jzap-cli` exposes one, its `Main`. Where a
number looks high it is because the module *is* an interface: `jzap-model` is twelve of twelve,
since the vocabulary is the whole point, and `jzap-agent` is seven of eight because instrumented
bytecode calls its statics directly.

`jzap-core` deliberately keeps thirty-six classes in one package rather than splitting into
`cache`, `bytecode`, `engine` and `filter`. In Java that trade goes the wrong way: package-private
is the only real encapsulation, so the split would force six of those types public to gain four
directories. The `mutator` subpackage exists because it is the one boundary with a real contract.
See that package's `package-info.java` for the grouping and the reasoning.

## The seam: a project model

One versioned JSON document that any adapter can emit and the CLI/daemon consumes:

```jsonc
{
  "schemaVersion": 1,
  "modules": [{
    "id": ":service:orders",
    "mutableCodePaths": ["build/classes/kotlin/main", "build/classes/java/main"],
    "sourceRoots":      ["src/main/kotlin", "src/main/java"],
    "testClassPaths":   ["build/classes/kotlin/test"],
    "testClasspath":    ["...jars and dirs..."],
    "analysisClasspath":["...jars and dirs..."],
    "modulePath":       [],
    "javaHome": "/opt/jdk-21",
    "jvmArgs": ["-Xmx2g"],
    "kotlinVersion": "2.2.0"
  }],
  "scope":   { "kind": "diff", "from": "origin/main", "to": "-Local-", "granularity": "line" },
  "cache":   { "dir": ".jzap/cache", "shared": false },
  "reporters": ["json", "gitci"]
}
```

Why a file rather than flags:

- Adapters stay dumb and testable. The Gradle plugin's whole job becomes "produce this
  document", which is unit-testable without running a mutation analysis.
- Bug reports become reproducible: attach the model, run the CLI, done. No Gradle in the
  loop.
- Model version is independent of engine version, so users can pin a newer engine under
  an older plugin — the way `pitestVersion` works for gradle-pitest-plugin.
- Sidesteps command-line length limits by construction.

**Multi-module in one run.** The model takes a *list* of modules deliberately. One
invocation covering the whole reactor lets the daemon warm once and lets cross-module
test selection work — the thing that is only partially supported in PIT, and only with
explicit configuration, since 1.17.1. Gradle's per-project task model pushes the other
way (one task per subproject); the adapter should therefore offer both a per-project task
and an aggregate root task that emits one multi-module model. Deciding this late would
force a rewrite of the scheduler, so it belongs in the model from day one.

## What genuinely leaks, and where it's allowed to

Four things resist abstraction. All four live entirely in the adapters:

1. **Classpath and source-set computation.** Gradle source sets, configurations, test
   fixtures, Android variants, Maven reactor ordering and scopes, shaded jars, JPMS module
   path versus classpath. This is where nearly all real-world pain lives, and none of it
   belongs in the core.
2. **Toolchain selection.** Tests must run on the JVM the build chose. The adapter
   resolves it and puts `javaHome` in the model; the core just obeys.
3. **Up-to-date checks and build caching.** Gradle wants declared inputs/outputs on the
   task; Maven wants nothing. The adapter declares them. The corollary is a hard
   requirement on the core: **identical model plus identical class bytecode must produce
   identical results**, or Gradle's cache will lie. That rules out timing-dependent
   behaviour leaking into reported results (see risks).
4. **Daemon lifecycle ownership.** See below.

## Daemon ownership is the real decision

The warm-JVM daemon must be owned by jzap, not by the build tool — keyed by a hash of
(module set, classpath, toolchain, engine version), reachable over a local socket, with an
idle timeout.

If the daemon were tied to the Gradle daemon, Maven users (no daemon) and CI (cold every
time) would get none of the speedup, and the "ultra fast" premise would only hold for one
build tool. Owning it means the CLI, both build plugins, IDE runs, and a future
file-watcher all hit the same warm process and share the same coverage cache.

## Where the core is allowed to be opinionated

- Test execution goes through the JUnit Platform launcher, not a build tool's test task.
  Adapters pass the test classpath; the core discovers and selects individual tests
  itself, which is a prerequisite for both per-test coverage and kill-test-first ordering.
- Git is a plugin (`jzap-git`), not a dependency of the core. The core only understands
  "these line ranges in these files are in scope". That keeps diff scoping usable from a
  CI system that hands over a patch file, and keeps the core testable without a repo.
- Engine dependencies must be shaded/relocated. PIT relocates its minion dependencies to
  avoid clashing with the user's; anything sharing a classloader with user code has the
  same obligation.

## Build order

1. **`jzap-model` + `jzap-core` + `jzap-cli`.** Nothing else can be validated until the
   engine runs from a hand-written model JSON. Write the model schema first; it is the
   contract every later module codes against.
2. **`jzap-git` + `jzap-report`.** Diff scoping and PR-shaped output are the product, not
   a feature. Without them there is no reason to prefer jzap over PIT.
3. **`jzap-gradle`.** First, because Kotlin lives on Gradle and because free line-level
   diff mutation testing does not exist there today
   ([gradle-pitest-plugin #165](https://github.com/szpak/gradle-pitest-plugin/issues/165)).
   That is the underserved audience.
4. **`jzap-maven`.** Easier of the two, and PIT already serves those users acceptably via
   `scmMutationCoverage`, so the marginal gain is smaller. Do it second.
5. **`jzap-kotlin`** compiler-plugin frontend and **Bazel** support after the above, both
   strictly optional. The compiler plugin is the one piece that would breach the seam
   (it has to be wired into compile tasks), so it must degrade to bytecode+filters when
   absent.

## Risks this structure creates

- **Determinism versus timeouts.** Hang detection based on wall-clock timings makes
  results non-deterministic, which breaks Gradle caching. Mitigation: use hit-count probes
  as the primary infinite-loop signal and report timeouts as a distinct, non-cached
  status rather than folding them into killed/survived.
- **Model drift.** Two adapters computing "the classpath" slightly differently produces
  bugs that look like engine bugs. Mitigation: a shared conformance test suite that runs
  the same fixture project through each adapter and diffs the emitted models.
- **Over-abstraction.** An SPI for every seam costs more than it returns. Keep extension
  points to exactly three: mutators, test frameworks, reporters.
- **Daemon staleness.** A warm JVM holding stale classes is the classic source of
  phantom results. Mitigation: the daemon keys its state on class bytecode hashes and
  drops any module whose hash moved, rather than trying to patch in place.

See [prior-art.md](prior-art.md) for the research these choices rest on.

## Decision records

- [ADR 0001: Implementation language — Java for the engine, not Rust](adr/0001-implementation-language.md)

## Delivery

See [delivery-plan.md](delivery-plan.md) for the delivery plan and
[parity-and-benchmarks.md](parity-and-benchmarks.md) for the PIT comparison harness that
gates it.
