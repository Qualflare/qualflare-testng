# Configuration

Precedence, highest first:

1. **System property** — `-Dqualflare.outputDir=...`, or `<systemPropertyVariables>` in the
   Surefire configuration
2. **Environment variable** — `QUALFLARE_OUTPUT_DIR=...`
3. **Default**

An **empty value falls through rather than winning**. A declared-but-unset property is the
common case in build files, and treating `""` as a choice would override the environment
with nothing.

| Option | System property | Environment | Default |
|---|---|---|---|
| Report directory | `qualflare.outputDir` | `QUALFLARE_OUTPUT_DIR` | `qualflare-results` |
| Environment | `qualflare.environment` | `QUALFLARE_ENVIRONMENT` | `development` |
| Language | `qualflare.language` | `QUALFLARE_LANGUAGE` | `en-US` |
| Platform | `qualflare.platform` | `QUALFLARE_PLATFORM` | `api` |
| Enabled | `qualflare.enabled` | `QUALFLARE_ENABLED` | `true` |
| Branch | `qualflare.branch` | `QUALFLARE_BRANCH` | *(null)* |
| Commit | `qualflare.commit` | `QUALFLARE_COMMIT` | *(null)* |

`enabled` accepts `0`, `false`, `no` and `off` (case-insensitive) as false. Anything else
is true — an unrecognised value enables rather than silently disabling reporting, because
a typo that turns your reporting off is harder to notice than one that leaves it on.

Disabling suppresses the **report file**: the listener stays attached and keeps
accumulating, and nothing is written. It is read at write time, not at startup, so setting
it from a `@BeforeSuite` still takes effect.

`branch` and `commit` default to **null, not to a guess**. The wire contract distinguishes
"not detected" from "absent", and the server groups history by branch — a wrong branch name
is worse than no branch name.

## `environment` is matched by uid, not display name

`environment` is matched against the environment's **uid (slug)**, not its display name, so
**Staging** in the UI is `staging` here.

This one fails late and deserves the callout. A wrong value cannot fail at test time,
because the reporter makes no requests — the run succeeds, and `qf collect` 404s afterwards.

## Setting options from Maven

```xml
<plugin>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <systemPropertyVariables>
      <qualflare.environment>staging</qualflare.environment>
      <qualflare.outputDir>target/qualflare-results</qualflare.outputDir>
    </systemPropertyVariables>
  </configuration>
</plugin>
```

## Setting options from Gradle

```kotlin
tasks.test {
    systemProperty("qualflare.environment", "staging")
    systemProperty("qualflare.outputDir", "build/qualflare-results")
}
```

## There is no token option

The reporter makes no network calls, so it holds no credential. `qf login` owns that, and
it lives with the CLI rather than in your build file. There is also no config file: the two
mechanisms above are the whole surface.
