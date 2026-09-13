# Versioning policy

jzap's version applies to more than the jar. Four things are public contracts, and each has its own
answer to "what happens when it changes".

## The project model schema

The document a build-tool adapter hands the engine. It carries an explicit `schemaVersion`.

- **Adding a field** is not a breaking change. An older engine ignores it with a warning, which is
  deliberate: a tolerant reader that silently dropped a field the user meant to set would be worse
  than one that complains.
- **Changing or removing a field** increments `schemaVersion`. An engine that does not recognise the
  version refuses to run and says which version it understands, rather than guessing at a document
  it may misread.
- A plugin and an engine may therefore differ in version, which is the point: the engine version is
  configurable precisely so it can move independently.

## The cache format

Plain text, with a format string in its first line.

- Any change to the format or to the meaning of a key **changes the format string**, and an older
  or newer file is discarded rather than misread. A cache that half-parses is worse than no cache.
- The header also carries the engine id and version, the mutator set, the filter set and the
  toolchain fingerprint. Any mismatch discards the file, because verdicts recorded under one of
  those say nothing about another.
- Cache files are **not** portable between machines with different JDKs. Bytecode differs between
  `javac` versions and platforms; the toolchain fingerprint enforces this rather than trusting it.

## Mutator ids

`MATH`, `NEGATE_CONDITIONALS` and the rest are public.

- They appear in mutant keys, which appear in the cache, in every report, and in the parity mapping
  against PIT. **Renaming one is a breaking change**: it invalidates caches and turns every mutant
  of that kind into an unexplained difference against the correctness oracle.
- Adding a mutator changes the default inventory and therefore scores. New mutators go in the
  default set only on a minor version, and the release notes say what the inventory change is.

## Mutant keys

`class::method+descriptor::line::mutator#ordinal`.

- Stable across recompilation when the enclosing method is unchanged. That is what makes the cache
  and the parity baseline work.
- Keys shift when a method's mutation points change, which includes adding a filter. That is why
  the enabled filter set is part of the cache key rather than a detail.

## The daemon protocol

Not yet versioned. An incompatible daemon left running after an upgrade is not detected; stop it
with `jzap daemon --stop` after upgrading. This is a known gap rather than a design.

## Version numbers

Ordinary semantic versioning, with the above as the definition of "breaking":

| Change | Version |
|---|---|
| Model schema version increment | major |
| Mutator id rename or removal | major |
| Mutator added to the default set | minor |
| Cache format change | minor (caches are discarded, not misread) |
| New filter, off by default | minor |
| Filter turned on by default | minor, with the inventory change in the notes |
| Bug fix that changes a verdict | patch, with the verdict change in the notes |

The last row is the awkward one and is stated deliberately: a fix that makes a wrong verdict right
changes scores, and pretending otherwise would make every such fix look like a regression.
