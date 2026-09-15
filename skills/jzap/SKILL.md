---
name: jzap
description: Run mutation testing on Java or Kotlin with jzap to find tests that execute code without asserting anything. Use when asked to check whether tests are meaningful, find weak or missing assertions, verify a change is actually tested, or act on surviving mutants. Also covers reading jzap output and its exit codes.
---

# Mutation testing with jzap

Coverage says a line ran. Mutation testing says whether anything was **asserted** about it. jzap
changes compiled code in small ways — flips a comparison, swaps an operator, drops a call — and
reports each change the test suite fails to notice. Every survivor is a specific, fixable gap.

## Always scope to the change

A full run analyses every mutant in the module. Diff-scoped runs analyse only changed lines, which
is faster and produces output you can actually act on.

```bash
./gradlew mutationTestDiff                                  # uncommitted work (from=HEAD)
JZAP_FROM=origin/main JZAP_TO=-Local- ./gradlew mutationTestDiff   # a whole branch
```

The git range only *selects* what to analyse. Analysis always runs against the currently compiled
code; nothing is checked out. So compile first, then run.

## Always use `-r agent`

The default reporters are shaped for a human with a browser. Use the `agent` reporter: it prints
only the findings, to stdout, one per line, with no source snippets, no killed mutants and no
timings. On the 1080-mutant benchmark fixture it emits 16 KB where the JSON report is 509 KB —
roughly 4,000 tokens instead of 130,000, for the same actionable content.

```groovy
jzap { reporters = ['agent'] }
```

```bash
jzap run -m model.json -r agent -q
```

Add `json` alongside it only if you need to post-process with `jq`; don't read the JSON directly.

## Reading the output

```
jzap: 2 survived, 3 uncovered of 12 mutants (score 58.3%, strength 77.8%)

survived:
sample/Discount.java
  10 CONDITIONALS_BOUNDARY changed conditional boundary: <= became <
  17 TRUE_RETURNS replaced boolean return with true

uncovered:
sample/Strings.java
  7 EMPTY_RETURNS replaced String return with ""
```

- **`survived`** is the actionable list, and the only section worth acting on first. A test
  executes that line and would not notice the change described. Open the file at that line and
  add or strengthen an assertion.
- **`uncovered`** means no test runs the line at all. That is a coverage gap; a coverage tool
  reports it more cheaply, so treat it as lower priority.
- **`baseline-failures`**, if present, comes first and invalidates everything below it. A test
  already fails with no mutant applied, so verdicts are unreliable. Fix those tests first and
  re-run. Do not act on the findings.
- **`not scored`** in the summary counts mutants the tests never judged — see Verdicts below.

## How to fix a survivor

Read the description, then assert the thing that would distinguish the original from the mutant.

| Finding | What the test is missing |
|---|---|
| `CONDITIONALS_BOUNDARY` `<=` became `<` | A case exactly *at* the boundary |
| `NEGATE_CONDITIONALS` | A case that takes the other branch |
| `MATH` | An assertion on the computed value, not just that it ran |
| `TRUE_RETURNS` / `FALSE_RETURNS` | An assertion on the returned boolean in both states |
| `PRIMITIVE_RETURNS` replaced with 0 | An assertion the value is not zero |
| `EMPTY_RETURNS` | An assertion the returned collection or string has content |
| `VOID_METHOD_CALLS` | An assertion on the call's *effect* — the call is removable unnoticed |
| `INCREMENTS` | An assertion on the counter's final value |

The fix is nearly always an assertion in an existing test, not a new test. If a survivor has no
sensible assertion — the mutated code is genuinely unobservable — say so rather than writing a
test that asserts an implementation detail to silence the tool.

Re-run after changing tests to confirm the mutant is now killed.

## Verdicts

| Status | Meaning |
|---|---|
| `KILLED` | A test failed: the suite detects this fault. Nothing to do |
| `SURVIVED` | Covered, all tests passed: an undetected fault |
| `NO_COVERAGE` | No test executes the line |
| `TIMED_OUT` | Tests hung with the mutant active. Counted as detected |
| `NON_VIABLE` | The JVM refused the mutated class. Not a real fault, not your problem |
| `RUN_ERROR` | The analysis itself failed. A jzap bug or an environment problem |

`NON_VIABLE` and `RUN_ERROR` are in neither percentage, because the tests were never given the
chance to detect them. A summary saying `40 not scored of 50 mutants` is a score over ten
mutants, not fifty — read the counts, not just the percentage.

**Mutation score** is detected over all scored mutants. **Test strength** is the same over covered
mutants only. Test strength is the better number when judging test quality, since it excludes code
no test reaches.

## Exit codes

| Code | Meaning | What to do |
|---|---|---|
| `0` | Met its bar | Nothing |
| `1` | Below `--threshold`, or survivors with `--fail-on-survivors` | Act on the findings |
| `2` | Usage error | Fix the command or the project model |
| `3` | The analysis failed | Not a test-quality problem; report it |

Exit `1` and exit `3` mean opposite things: `1` is "your tests are weak", `3` is "there is no
result". Never treat `3` as a finding about the tests.

## When output is empty or wrong

Run `--dry-run` first; it prints the resolved scope and classpaths without analysing anything, and
most configuration problems are visible there.

- **No mutants found.** Usually the code was not compiled, or everything is out of scope. On a
  diff-scoped run this is the normal healthy case: the changed lines carry no mutants.
- **Everything is `NO_COVERAGE`.** If zero tests were discovered, `junit-platform-launcher` is
  probably missing from the test runtime classpath. If tests were discovered but cover nothing,
  the module's tests likely live in another module — analyse them together with
  `mutationTestAll`.
- **In CI, nothing in scope.** `actions/checkout` clones one commit by default, so the base ref
  does not exist. Needs `fetch-depth: 0`.

## Cost

Mutation testing runs the test suite many times. It is fast — measured between 7.9x and 9.6x
PIT depending on the machine — but a full
run on a large module is still minutes. Do not run it speculatively:

- Use `mutationTestDiff`, not `mutationTest`, unless a full picture was asked for.
- `--cache-dir` makes a re-run several times faster; use the same directory across runs.
- `--threads` defaults to 1 and can make a run slower, not faster: an extra analysis JVM helps a
  suite that waits on I/O and hurts fast CPU-bound tests. Raise it only after measuring.

## Further reading

Full documentation: https://huyz0.github.io/jzap/ — `usage.md` for every option, `mutators.md` for
what each mutator changes and every rule that suppresses one.
