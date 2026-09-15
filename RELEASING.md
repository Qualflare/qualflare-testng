# Releasing

## The thing to internalise first

**Maven Central is immutable.** A published version can never be edited, replaced or
withdrawn — only superseded by a higher one. This is not npm, where a bad publish can be
deprecated and a fixed one shipped; it is not a container tag you can move. A wrong
`0.1.0` is `0.1.0` forever.

That is why `autoPublish` is `false` and why the whole gate runs before anything uploads.

## Cutting a release

1. Make sure `main` is green. CI covers Java 11/17/21 and the integration fixture against
   TestNG **7.4.0** (the floor) and **7.12.0**. The floor is the one to care about:
   `ITestResult.wasRetried()` does not exist before 7.4.0, and the entire retry story
   rests on it — so a release that skipped the floor could ship a jar whose headline
   feature is silently dead for anyone pinned low.

2. Set the version. Central rejects `-SNAPSHOT`, and the release workflow refuses it too:

   ```bash
   mvn versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
   ```

3. **Reconcile the README.** The install snippet carries a literal version, so it goes
   stale the moment the pom moves. Check it names the version you are about to publish:

   ```bash
   grep -n '<version>' README.md
   ```

   Add the Maven Central badge at the same time — it is deliberately absent until the
   first release, because shields.io renders "not found" for an unpublished artifact:

   ```markdown
   [![Maven Central](https://img.shields.io/maven-central/v/com.qualflare/qualflare-testng.svg)](https://central.sonatype.com/artifact/com.qualflare/qualflare-testng)
   ```

4. Update `CHANGELOG.md`.

5. Commit and tag. The tag must match the pom version exactly; the workflow asserts it.

   ```bash
   git commit -am "chore: release v0.1.0"
   git tag v0.1.0 && git push origin main v0.1.0
   ```

6. The workflow runs the gate, signs, and uploads a **validated but unpublished** bundle.

7. **Publish it by hand** at
   [central.sonatype.com/publishing/deployments](https://central.sonatype.com/publishing/deployments).
   Look at what you are about to make permanent first: the coordinates, the version, and
   that the sources and javadoc jars are both present.

8. Verify at the source, never at a green checkmark:

   ```bash
   # the artifact itself, not a dashboard
   curl -sI https://repo1.maven.org/maven2/com/qualflare/qualflare-testng/0.1.0/qualflare-testng-0.1.0.jar
   ```

   Propagation to `repo1` takes a few minutes and the search index takes longer — an
   artifact missing from search is not yet a failed release.

   A green workflow is not evidence of a publish. `@qualflare/cli` once printed
   `OK Test results collected successfully` for eleven consecutive runs while nothing
   reached the server, which is why every check in this repo looks at the artifact rather
   than at an exit code.

9. Confirm the published jar reports a real version. `Version.VALUE` reads
   `Implementation-Version` from the manifest, and falls back to `0.0.0-dev` when it is
   absent — which shipped once already, in `qualflare-junit5` 0.1.0, putting wrong
   metadata on every report:

   ```bash
   curl -sO https://repo1.maven.org/maven2/com/qualflare/qualflare-testng/0.1.0/qualflare-testng-0.1.0.jar
   unzip -p qualflare-testng-0.1.0.jar META-INF/MANIFEST.MF | grep Implementation-Version
   ```

10. Open the next development version:

    ```bash
    mvn versions:set -DnewVersion=0.2.0-SNAPSHOT -DgenerateBackupPoms=false
    git commit -am "chore: open 0.2.0-SNAPSHOT"
    ```

## What the repo already has

Four secrets, all account-level and shared with the other JVM reporter — the same values
as `qualflare-junit5`, and the GPG key must be the same one, since its public half is
already on the keyservers Central checks:

| Secret | Consumed as |
|---|---|
| `CENTRAL_TOKEN_USERNAME` | `server-username` against `server-id: central` |
| `CENTRAL_TOKEN_PASSWORD` | `server-password` |
| `GPG_PRIVATE_KEY` | `setup-java`'s `gpg-private-key` (ASCII-armored, whole block) |
| `GPG_PASSPHRASE` | exported as `MAVEN_GPG_PASSPHRASE` — note the rename |

Plus a `maven-central` deployment environment, with no protection rules. The human gate is
`autoPublish: false`, not a required reviewer.
