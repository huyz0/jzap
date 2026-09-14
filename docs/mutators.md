# Mutators and filters

A mutator decides what changes. A filter decides which of those changes are not worth seeding.
Both halves matter: an unfiltered mutation tool produces mutants that cannot be killed by any
test, and every one of those is a permanent false alarm in the report.

```bash
jzap mutators                            # the default set
jzap list-mutants -m model.json --format table   # the inventory, without running a test
```

## The default set

Ten mutators, matching PIT's `DEFAULTS` set and **using the same ids**, so that an inventory can
be compared against PIT's mutant for mutant. The ids are treated as public: renaming one
invalidates caches and breaks the parity mapping.

| Id | What it changes |
|---|---|
| `CONDITIONALS_BOUNDARY` | A relational operator becomes the one differing only at the boundary: `<` becomes `<=` |
| `NEGATE_CONDITIONALS` | A conditional is inverted |
| `MATH` | An arithmetic or bitwise operator is swapped for a different one |
| `INCREMENTS` | A local variable increment is negated, so `i++` becomes `i--` |
| `INVERT_NEGS` | An arithmetic negation is removed, so `-x` becomes `x` |
| `VOID_METHOD_CALLS` | A call to a void method is removed, arguments and receiver discarded |
| `TRUE_RETURNS` | A boolean-returning method returns `true` |
| `FALSE_RETURNS` | A boolean-returning method returns `false` |
| `PRIMITIVE_RETURNS` | A numeric-returning method returns zero |
| `EMPTY_RETURNS` | A reference-returning method returns an empty value of its own type |

Restrict the set with `--mutators`:

```bash
jzap run -m model.json --mutators MATH,NEGATE_CONDITIONALS
```

### Deliberate exclusions inside those ten

Each of these was a decision, not an oversight:

- **`PRIMITIVE_RETURNS` skips `boolean`.** `TRUE_RETURNS` and `FALSE_RETURNS` already cover it, and
  mutating it here as well would produce a duplicate mutant.
- **`EMPTY_RETURNS` is restricted to types with an unambiguous empty form** — `List`, `Set`,
  `Map`, `Collection`, `Iterable`, `Optional` and its primitive forms, `Stream`, `String`, and the
  boxed primitives. Returning `null` from an arbitrary method
  is a different and much noisier mutation. That is PIT's `NULL_RETURNS`, which is not in its
  default set either.
- **`VOID_METHOD_CALLS` excludes constructor calls.** Removing one leaves an uninitialised object
  on the stack and the class fails verification, producing a `NON_VIABLE` mutant instead of a
  useful one.

## EXTREME

```bash
jzap run -m model.json --mutators EXTREME
```

Descartes-style whole-body replacement — `REMOVE_METHOD_BODY` and `CONSTANT_RETURN` — rather than
operator-level changes. Far fewer mutants, each a much larger change, which answers "is this
method tested at all" rather than "is this expression tested precisely". It is a different
question, not a cheaper version of the same one.

## Mutants that are never seeded

These rules are on by default and are not configurable, because in each case the mutant they
suppress is **the original program**. Such a mutant cannot be killed by any test, so it would be
reported as surviving forever.

- **A return value replaced with the value already returned.** A method that already ends
  `return true` gets no `TRUE_RETURNS` mutant. PIT suppresses the same case; the exact rule was
  established by running PIT's mutators over a probe class rather than assumed.
- **An increment that cannot be negated.** `IINC` carries a signed 16-bit operand, so negating
  `-32768` gives `32768`, which does not fit — and ASM writes the low sixteen bits without
  complaint, so the mutant comes out as `-32768` again. Zero negates to itself. Both would be
  byte-identical to the original.
- **An already-empty return.** `return List.of()` gets no `EMPTY_RETURNS` mutant, nor does
  `return Optional.empty()`, `return Boolean.FALSE`, or `return 0` from a method returning
  `Integer`.

!!! warning "One case jzap still gets wrong"
    The already-empty rule only recognises a value that is returned *directly*. Assigned to a
    local first, it is still mutated:

    ```java
    List<String> r = List.of();
    return r;                      // still mutated, and the mutant can never be killed
    ```

    jzap decides this from the instruction immediately before the return, which cannot see through
    a local. PIT's `EquivalentReturnMutationFilter` does handle it, by matching the store and the
    load as a sequence over the whole method — so this is a jzap-only false survivor rather than a
    shared limitation. Recorded in [Status](status.md) with what closing it would take.

## Filters that are on by default

Both are judgements rather than facts, so both can be switched off.

### Loop counters — `--mutate-loop-counters` to disable

Negating the counter that drives a loop rarely tells you anything: the mutant either hangs the
test or crashes it immediately, so it dies for a reason unrelated to what the test checks.

Seeding them on the bench fixture produced 80 extra mutants of which **79 were killed**, and the
slowest ran to integer wraparound — roughly two billion iterations — before dying. A large slice
of run time for almost no information.

PIT filters the same case. That was established by running PIT's `INCREMENTS` mutator alone over a
probe class with a `for` loop, a `while` loop, an indexed loop and a standalone `value++`: PIT
produced a mutant only for the standalone one.

An ordinary `count++` inside a loop body is still mutated. Only the variable the loop's exit test
reads is suppressed.

### Kotlin compiler output — `--mutate-kotlin-internals` to disable

Property accessors, data class members, null-check intrinsics and for-each loop scaffolding are
code nobody wrote, and a mutant in one is noise. Every rule is gated on the `kotlin.Metadata`
annotation, so Java classes in a mixed module behave exactly as before.

## Reduction techniques, all off by default

Four techniques that drop mutants which *could* be killed, in exchange for speed. They are off by
default for one reason: PIT does none of them, so every dropped mutant becomes a difference
against the correctness oracle.

| Flag | What it drops |
|---|---|
| `--one-per-line` | Every mutant on a line beyond the first |
| `--dedup` | Mutants trivially equivalent to another (`TCE`) |
| `--arid` | Mutants in methods that only log or report (`ARID`) |
| `--mutators EXTREME` | Operator-level mutants, in favour of whole-body replacement |

`./gradlew :tools:bench:reduction` reports speed and **detection loss** together, which is the
only honest way to report them. On the bench fixture:

- `--one-per-line` halves the mutant count for a **1.4x** speedup and stops reporting **43 of 125
  genuine gaps**.
- `--dedup` drops **nothing at all** on javac output.

That 1.4x was 1.9x before the schemata engine landed. A reduction technique pays in proportion to
how much of a run is per-mutant cost, and schemata made a mutant cheap — so the trade of a third
of the findings for a shrinking speedup keeps getting worse. See
[Measured results](performance.md).

## What is never mutated

- **Test classes.** Only a module's `mutableCodePaths` are scanned. This is structural, not a
  filter that can be switched off.
- **Classes with no line numbers.** Compiled with `-g:none`, a class has no line information, and
  a mutant that cannot be pointed at a source line is not reported. Both Gradle and Maven compile
  with debug information by default.

Narrow the scope further with class filters, matched against binary class names:

```bash
jzap run -m model.json --include 'com.example.core.*' --exclude '*.generated.*'
```

## How a mutant is identified

```
ex.Calc::add(II)I::4::MATH#0
```

Class, method with descriptor, line, mutator id, and an ordinal distinguishing several mutants of
the same mutator on the same line. That key is what the cache, the parity harness and every
reporter agree on, and it is stable across runs — which is what makes a diff of two reports
meaningful.
