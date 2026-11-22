package com.damianhoward.conventions.runtime

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/**
 * What a service declares about the runtime its release carries. Everything else -- the
 * modules, the launcher change, the checks -- is the convention's, so two services
 * cannot differ in whether their image can be diagnosed or whether their launcher uses it.
 */
abstract class RuntimeImageExtension {
    /**
     * Paths the smoke test requires a 200 from, after `/healthz`. A path that depends on something
     * CI does not run -- an upstream, a database -- does not belong here.
     */
    abstract ListProperty<String> getSmokePaths()

    /** Environment the service needs to start in the smoke test, beyond PORT and JAVA_HOME. */
    abstract MapProperty<String, String> getSmokeEnvironment()

    /**
     * Whether the service can start in CI. One that migrates a database before it listens cannot,
     * and its smoke test is skipped. The deploy's health gate is then the first start on the image:
     * it requires the service's own readiness, database included, before the release stays live.
     */
    abstract Property<Boolean> getStartsInCi()

    /** The oldest JDK the image may be linked from. Defaults to the convention's security floor. */
    abstract Property<String> getMinimumJdk()
}
