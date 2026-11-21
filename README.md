# build-conventions

The shared Gradle build platform for the `damianhoward` repositories, published to Maven Central:
convention plugins plus a shared version catalog (`:catalog`). A repository applies one
convention plugin and then declares only what is specific to it — its dependencies and, for
an application, its main class.

## Plugins

| Plugin                                | For                | Provides                                                                                                    |
| ------------------------------------- | ------------------ | ----------------------------------------------------------------------------------------------------------- |
| `com.damianhoward.kotlin-conventions` | Kotlin JVM         | JDK 25 toolchain, pinned Kotlin, 90% JaCoCo instruction gate, Spotless (ktlint + Prettier), OWASP, JUnit 6. |
| `com.damianhoward.java-conventions`   | plain Java         | The same, with Spotless JDK-agnostic Java hygiene in place of ktlint.                                       |
| `com.damianhoward.root-conventions`   | multi-module root  | Repo-wide Spotless (Gradle scripts, CI config, docs) and the OWASP aggregate; modules apply a plugin above. |
| `com.damianhoward.runtime-image`      | a deployed service | A jlinked runtime inside the distribution, a launcher that prefers it, and checks that it runs the service. |

The Kotlin serialization compiler plugin ships on the same classpath, so a repository that needs it
applies it with no version and gets the compiler the conventions pin — the two must match exactly.
In a module of a multi-module build, whose root has already put this classpath in place, that is
`id 'org.jetbrains.kotlin.plugin.serialization'` in the plugins block. A single-module build
resolves its plugins block before the classpath exists, so it applies the plugin after it:

```groovy
plugins {
    id 'com.damianhoward.kotlin-conventions' version '…'
}
apply plugin: 'org.jetbrains.kotlin.plugin.serialization'
```

Writing a version instead would work and would let the compiler plugin drift from the compiler.

A service applies `runtime-image` beside `application` and declares the modules it uses:

```groovy
runtimeImage {
    modules = ['java.base', 'java.management', 'jdk.httpserver']
    smokePaths = ['/readyz', '/']
}
```

The image adds `jcmd` and JFR and leaves out the compiler. `check` fails if `jdeps` finds the code
using a module the image lacks, refuses to link from a JDK older than the catalog's
`runtime-jdk-floor`, and starts the installed distribution with `JAVA_HOME` pointing at a JDK that
does not exist, requiring a 200 from `/healthz` and each smoke path. Temurin 25 ships no `jmods`, so
an image is linked on the platform it runs on: CI's Linux runner for the boxes.

Front-end tooling in `kotlin-conventions` is content-driven: web assets under
`src/main/resources/web` are Prettier-formatted under `spotlessCheck`, and a `package.json`
wires `npm run lint` (ESLint) into `check`. A repository is different only because of what it
holds — the CI pipeline stays identical.

## How this repository is checked

This is the one repository whose bytecode every other repository executes: its plugins are on
each consumer's buildscript classpath, and the shared CI calls those builds with
`secrets: inherit`. It carries the same gates as its consumers, minus one that does not apply:

`ci.yml` runs Spotless and `check`, which runs the TestKit tests of `runtime-image` against a small
application: the image carries the diagnostic tools and no compiler, the distribution runs on it, and
a missing module, a JDK below the floor and a failing smoke path each fail the build. `dep-review.yml` fails a pull request on a high-severity
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

Everything is on Maven Central under `com.damianhoward`, and each plugin is published with its
plugin marker, so `settings.gradle` needs only the repositories:

```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

`build.gradle`:

```groovy
plugins {
    id 'com.damianhoward.kotlin-conventions' version '0.6.1'
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
slf4j, Logback, kotlinx.serialization, the Oracle driver pair, testcontainers, flyway,
kafka-clients, commons-lang3, h2, gson, and Playwright for the browser tests every repository with
a UI carries. It also holds the versions the convention plugins apply themselves — the JUnit BOM,
the lz4 and snappy floors and the Kotlin toolchain — because Dependabot cannot read the
precompiled plugin scripts, and a version written there would never move. The plugin build copies
them into the jar.
Application-specific dependencies stay in each repo's build file. Import it in
`settings.gradle`:

```groovy
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create('deps') {
            from 'com.damianhoward:build-conventions-catalog:0.6.1'
        }
    }
}
```

Then in `build.gradle`: `testImplementation deps.hamcrest`, `runtimeOnly deps.slf4j.simple`.
