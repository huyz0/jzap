# The jzap agent skill

Teaches a coding agent to use [jzap](https://github.com/huyz0/jzap) — when to run mutation
testing, how to read the findings, and what to change for each kind of surviving mutant.

```bash
npx skills add huyz0/jzap
```

That installs it for whichever agents you have configured. To pick them explicitly:

```bash
npx skills add huyz0/jzap -a claude-code -a cursor
```

The skill is a single [SKILL.md](SKILL.md) with no supporting files, so it is worth reading before
installing it — it is short, and it is the whole thing.

## What it covers

- Scope to the change (`mutationTestDiff`) rather than running a whole module speculatively.
- Use the `agent` reporter, which emits the findings and nothing else: 16 KB where the JSON
  report is 509 KB on jzap's benchmark fixture.
- Read each section in the right order — a red baseline invalidates everything under it, and
  survivors matter more than uncovered lines.
- For each mutator, the assertion the test is actually missing.
- Verdict and exit-code semantics, including that exit 3 is a broken analysis rather than a
  finding about the tests.
- The failure modes that produce empty or misleading output.

It also tells an agent two things it would otherwise get wrong: not to write an assertion on an
implementation detail merely to silence a survivor, and that raising `--threads` can make a run
slower rather than faster, so it is a measurement rather than an optimisation.
