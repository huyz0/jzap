# Releasing

What a release needs, and what is already automated. Everything here has been exercised except
the two uploads, which need credentials — so those steps say exactly what to expect rather than
claiming to be verified.

## What goes where

| | Destination | Why there |
|---|---|---|
| `jzap-model`, `jzap-core`, `jzap-agent`, `jzap-wire`, `jzap-minion`, `jzap-git`, `jzap-report`, `jzap-cli` | Maven Central, `io.github.huyz0` | Ordinary libraries; the Maven plugin resolves the engine from here |
| `io.github.huyz0.jzap` (the Gradle plugin) | Gradle Plugin Portal | Where `plugins { id ... }` resolves from |
| `io.github.huyz0:jzap-maven-plugin` | Maven Central, published by Maven | Built by Maven because plugin descriptors are |
| Fixtures, `tools/`, `jzap-e2e` | Nowhere | They are how jzap is tested, not what it is |

The namespace is `io.github.huyz0`, verified by the GitHub account of that name. Java packages
match it — `io.github.huyz0.jzap.*` — so a coordinate can be guessed from an import and the other
way round. Central verifies only the groupId, so the two were briefly allowed to differ; making
them agree cost one mechanical change and removed a question every new reader would have asked.

## One-time setup

Four accounts-and-keys tasks, none of which can be automated from here.

### 1. Central Portal namespace

Register at [central.sonatype.com](https://central.sonatype.com), add the namespace
`io.github.huyz0`, and verify it by creating the public repository it asks you to create. Then
generate a **user token** (Account → Generate User Token) — the token, not your password, is what
CI uses.

### 2. A signing key

Central requires a detached signature on every artefact.

```bash
gpg --full-generate-key                  # RSA 4096, no expiry or a long one
gpg --list-secret-keys --keyid-format=long
gpg --armor --export-secret-keys <KEYID> # this whole block is the GPG_SIGNING_KEY secret
gpg --keyserver keys.openpgp.org --send-keys <KEYID>   # Central checks a public keyserver
```

Publishing the public key is not optional: Central validates the signature against a keyserver,
and a key it cannot find fails the deployment after upload.

### 3. Gradle Plugin Portal

Create an account at [plugins.gradle.org](https://plugins.gradle.org), then **claim the
`io.github.huyz0` namespace** — the Portal verifies it against the same GitHub account. Take the
API key and secret from your profile.

### 4. Repository secrets

In the repository's Settings → Secrets and variables → Actions:

| Secret | What |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Central Portal user token username |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user token password |
| `GPG_SIGNING_KEY` | The ASCII-armoured private key, whole block including the header lines |
| `GPG_SIGNING_KEY_PASSWORD` | That key's passphrase |
| `GRADLE_PUBLISH_KEY` | Gradle Plugin Portal API key |
| `GRADLE_PUBLISH_SECRET` | Gradle Plugin Portal API secret |

## Releasing

```bash
git tag v0.1.0
git push origin v0.1.0
```

That runs [`.github/workflows/release.yml`](https://github.com/huyz0/jzap/blob/main/.github/workflows/release.yml), which:

1. Runs `./gradlew build` — the whole suite, the module-boundary invariant and the coverage floor.
2. Runs the PIT parity gate and the Maven plugin smoke test.
3. Validates the Gradle plugin against the Portal **without publishing it**, so a bad key or a
   rejected plugin id fails before anything has been uploaded anywhere.
4. Builds and signs the Central bundle.
5. Uploads it to the Central Portal as a **staged** deployment.
6. Publishes the Gradle plugin to the Plugin Portal.

Every gate runs before anything leaves the machine, because **a published version cannot be
withdrawn** — Central is immutable by design. The parity gate is part of that on purpose: shipping
a build whose verdicts disagree with PIT would be shipping a wrong answer confidently.

### The last step is yours

The Central upload is `publishingType=USER_MANAGED`, so it validates and stages but does not go
live. Open [central.sonatype.com/publishing/deployments](https://central.sonatype.com/publishing/deployments)
and press publish. It costs one click and it is the only remaining chance to not publish.

Switching to `AUTOMATIC` in the workflow removes that step. Consider what it removes with it.

## Dry-running it locally

The bundle is the part worth checking by hand, and it needs no accounts:

```bash
export GPG_SIGNING_KEY="$(gpg --armor --export-secret-keys <KEYID>)"
export GPG_SIGNING_KEY_PASSWORD=...
./gradlew centralBundle -PjzapVersion=0.1.0
unzip -l build/central/jzap-0.1.0-bundle.zip
```

Each module should show, for every one of the jar, sources jar, javadoc jar, POM and Gradle module
file: the file itself, `.asc`, `.md5`, `.sha1`, `.sha256` and `.sha512`. Verify one for real:

```bash
cd $(mktemp -d) && unzip -q /path/to/jzap-0.1.0-bundle.zip
gpg --verify io/github/huyz0/jzap-model/0.1.0/jzap-model-0.1.0.jar{.asc,}
```

`./gradlew publishToMavenLocal -PjzapVersion=0.1.0` installs into `~/.m2` instead, which is how to
try the Maven plugin against a real project before anything is published.

Two guards will stop you rather than letting a bad bundle reach the Portal:

- **A snapshot version.** The release endpoint does not take snapshots, and a snapshot's filenames
  carry a build timestamp, so the rejection would arrive after the upload. Pass `-PjzapVersion`.
- **No signing key.** An unsigned bundle is rejected after upload too.

## The Maven plugin

Built by Maven, so it is released by Maven:

```bash
cd jzap-maven
mvn -B clean deploy -DskipTests   # needs a <server> for Central and gpg configured in settings.xml
```

Not yet part of the release workflow. Its version tracks the engine's but is set in
`jzap-maven/pom.xml` by hand, which is the next thing to automate here.

## After a release

- Bump the default in `build.gradle.kts` if you want the next snapshot to read differently; the
  version is a property with a snapshot default, so nothing needs resetting.
- The Gradle plugin's `engineVersion` convention comes from the plugin's own version, so a
  released plugin resolves the matching engine without configuration.
- Central takes up to a few hours to appear on `search.maven.org`, and about fifteen minutes to be
  resolvable from `repo1.maven.org`. Resolvable first, searchable later — do not assume a failure
  from a search that comes up empty.
