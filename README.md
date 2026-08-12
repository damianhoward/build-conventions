# build-conventions

The shared Gradle build platform for the `damianhoward` repositories, published via JitPack:
convention plugins plus a shared version catalog (`:catalog`). A repository applies one
convention plugin and then declares only what is specific to it — its dependencies and, for
an application, its main class.

## Plugins

| Plugin                                | For               | Provides                                                                                                    |
| ------------------------------------- | ----------------- | ----------------------------------------------------------------------------------------------------------- |
| `com.damianhoward.kotlin-conventions` | Kotlin JVM        | JDK 25 toolchain, pinned Kotlin, 90% JaCoCo instruction gate, Spotless (ktlint + Prettier), OWASP, JUnit 6. |
| `com.damianhoward.java-conventions`   | plain Java        | The same, with Spotless JDK-agnostic Java hygiene in place of ktlint.                                       |
| `com.damianhoward.root-conventions`   | multi-module root | Repo-wide Spotless (Gradle scripts, CI config, docs) and the OWASP aggregate; modules apply a plugin above. |

Front-end tooling in `kotlin-conventions` is content-driven: web assets under
`src/main/resources/web` are Prettier-formatted under `spotlessCheck`, and a `package.json`
wires `npm run lint` (ESLint) into `check`. A repository is different only because of what it
holds — the CI pipeline stays identical.

## How this repository is checked

This is the one repository whose bytecode every other repository executes: its plugins are on
each consumer's buildscript classpath, and the shared CI calls those builds with
`secrets: inherit`. It carries the same gates as its consumers, minus one that does not apply:

`ci.yml` runs Spotless and `check`. `dep-review.yml` fails a pull request on a high-severity
advisory in what the diff adds. `dependabot-automerge.yml` behaves as it does everywhere else;
merges still wait on the required checks.

`dependency-check.yml` runs `:dependencyCheckAggregate` twice weekly over `:plugins` and
`:catalog`. It matters more here than anywhere else: the Kotlin, Spotless, and
dependency-check plugin artifacts reach consumers through the buildscript classpath, which a
consumer's own scan does not read, so this is the only scan in the estate that sees them.

CodeQL is deliberately absent. The convention plugins are precompiled Gradle script plugins
(`plugins/src/main/groovy/*.gradle`), and the repository holds no Java or Kotlin source for
the `java-kotlin` analysis to read.

## Consuming

`settings.gradle` — add JitPack and map the plugin id to this module:

```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.namespace == 'com.damianhoward') {
                useModule("com.github.damianhoward.build-conventions:plugins:${requested.version}")
            }
        }
    }
}
```

`build.gradle`:

```groovy
plugins {
    id 'com.damianhoward.kotlin-conventions' version '0.4.13'
    id 'application' // if the repository is an application
}

dependencies {
    // only what this repository actually needs
}

// Class files kept out of both the coverage report and the 90% gate. Every service needs at least
// its process entry point here: main() binds a real port, or a real database, and blocks.
coverageExcludes = ['**/MainKt.class']
```

### Knobs

| Property                          | Where               | Effect                                                                                                     |
| --------------------------------- | ------------------- | ---------------------------------------------------------------------------------------------------------- |
| `coverageExcludes`                | `build.gradle`      | Class-file patterns excluded from the JaCoCo report and the coverage gate. Empty by default.               |
| `conventions.kotlinMaxLineLength` | `gradle.properties` | Relaxes the ktlint line length for mock-heavy test code (e.g. `160`). Unset keeps the default.             |
| `conventions.skipDockerCheck`     | command line        | Runs a Testcontainers project's suite knowingly without Docker, instead of failing before the tests start. |

`coverageExcludes` exists because eight repositories were each carrying the same sixteen-line
`afterEvaluate` / `fileTree` block to exclude an entry point — in two different spellings, which is
how a shared rule quietly stops being one. Keep exclusions to code a test genuinely cannot reach; a
coverage number is only worth reporting if it is measuring the code that matters.

## Version catalog

`gradle/libs.versions.toml` holds only versions two or more repos genuinely share — hamcrest,
slf4j, the Oracle driver pair, testcontainers, flyway, kafka-clients, commons-lang3, h2, gson.
Application-specific dependencies stay in each repo's build file. Import it in
`settings.gradle`:

```groovy
dependencyResolutionManagement {
    repositories {
        maven { url 'https://jitpack.io' }
    }
    versionCatalogs {
        create('deps') {
            from 'com.github.damianhoward.build-conventions:catalog:0.4.13'
        }
    }
}
```

Then in `build.gradle`: `testImplementation deps.hamcrest`, `runtimeOnly deps.slf4j.simple`.
