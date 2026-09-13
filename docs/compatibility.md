# Compatibility

What jzap has actually been run against, rather than what it might work with. Anything not listed
here is untested, which is not the same as unsupported.

## JDK

| | Version |
|---|---|
| Runs on | 17 and later |
| Compiled for | 17 (`--release 17`) |
| Tested on | 25 |

The JVM that runs the analysis is the one the build chose, not the one running jzap: the Gradle
plugin passes the toolchain's launcher through the project model, and the engine obeys it.

Class files are read with ASM 9.10, which understands class file versions through JDK 25.

## Build tools

| | Version |
|---|---|
| Gradle | 9.7, configuration cache compatible |
| Maven | 3.9 |

The Gradle plugin is tested through TestKit against the Gradle running the build. The Maven plugin
is tested by running a real Maven build against a generated project.

## Test frameworks

| Framework | Status |
|---|---|
| JUnit Jupiter (JUnit 5) | Tested; per-test selection |
| Kotest | Tested; selection at spec granularity, not per leaf |
| JUnit 4 | Untested. Should work through the vintage engine, at whatever granularity that engine exposes |
| TestNG | Untested |

Anything on the JUnit Platform is discovered, and jzap takes its runnable unit from whatever the
engine exposes — a leaf test where there is one, a container otherwise. That is what makes Kotest
work; it is also why an untested framework is likely to run and possibly to select coarsely.

## Languages

| | Version | Notes |
|---|---|---|
| Java | 17+ | |
| Kotlin | 2.4 | Compiler-generated constructs filtered; inline functions mutated through their call sites |

Kotlin support is keyed on the `kotlin.Metadata` annotation, so a mixed Java and Kotlin module gets
the right treatment per class. The inline-function handling reads the `SourceDebugExtension` SMAP
table, which is a stable format rather than a compiler internal — but the shapes the filters
recognise are specific to what kotlinc emits, and a much older or newer compiler may emit others.

## What is not supported

- **Android and Kotlin Multiplatform.** Untested. The Gradle plugin reads the `java` plugin's source
  sets, which an Android module does not have in the same form.
- **JPMS module path.** The analysis JVM is started with a classpath. A project that must run on the
  module path is not covered.
- **Scala, Groovy and other JVM languages.** They compile to bytecode so mutation works, but their
  compiler-generated constructs have no filters, so expect junk mutants of the kind Kotlin had
  before M15.

## Versioning

The version applies to more than the jar:

| What | Breaking change means |
|---|---|
| Project model schema | `schemaVersion` increments; an older engine refuses a newer model with a message saying so |
| Cache format | The format string changes and old caches are discarded rather than misread |
| Mutator ids | A rename invalidates caches and breaks the parity mapping, so ids are treated as public |
| Daemon protocol | An incompatible daemon is not detected yet; stop it after upgrading |
