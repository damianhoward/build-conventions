package com.damianhoward.conventions.runtime

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

import javax.inject.Inject

/**
 * Links the service's runtime with jlink, from the JDK toolchain the build compiles and tests with.
 *
 * Temurin 25 ships no jmods (JEP 493), so jlink links from the JDK it runs in and can only produce
 * that JDK's platform: CI's Linux runner links the image the boxes run, and a Windows build links a
 * Windows one for local use. jlink also refuses a JDK whose files have been edited, so an image
 * takes its certificate fixes from the vendor build or not at all -- which is why the floor below
 * is the first release carrying one.
 */
@DisableCachingByDefault(because = 'A 60 MB platform-specific image that jlink links in seconds; storing it in the build cache costs more than relinking it')
abstract class RuntimeImage extends DefaultTask {
    @Input abstract ListProperty<String> getModules()

    // The JDK's identity rather than its tree: the release file names the exact build, and hashing
    // a 300 MB directory on every build would cost more than linking does.
    @Input abstract Property<String> getJdkRelease()

    @Input abstract Property<String> getMinimumJdk()

    @Internal abstract DirectoryProperty getJdkHome()

    @OutputDirectory abstract DirectoryProperty getImage()

    @Inject abstract ExecOperations getExec()

    @TaskAction
    void link() {
        // The image is the JDK it is linked from, so an old JDK is an old runtime in production. On
        // 2026-11-21 CI linked risk-engine's from the runner's cached 25.0.5+8 and the release
        // shipped without the CVE-2026-91403 blocked.certs fix; refusing to link is how that fails.
        def line = jdkRelease.get().readLines().find { it.startsWith('JAVA_RUNTIME_VERSION=') }
        if (line == null) {
            throw new GradleException("${jdkHome.get()}/release names no JAVA_RUNTIME_VERSION, so the JDK cannot be checked against the floor")
        }
        def linking = Runtime.Version.parse(line.substring(line.indexOf('"') + 1, line.lastIndexOf('"')))
        def floor = Runtime.Version.parse(minimumJdk.get())
        if (linking.compareToIgnoreOptional(floor) < 0) {
            throw new GradleException("Refusing to link the runtime from JDK ${linking}: the floor is ${floor}.")
        }

        File out = image.get().asFile
        out.deleteDir() // jlink refuses an output directory that already exists
        // No --generate-cds-archive: on risk-engine it grew the image from 49 to 77 MB with no
        // measurable change in startup. No --compress: it shrinks the image on disk but gzips worse
        // in the deploy bundle, which is what crosses the wire.
        exec.exec {
            commandLine Tools.of(jdkHome.get().asFile, 'jlink'), '--add-modules', modules.get().join(','),
                '--strip-debug', '--no-header-files', '--no-man-pages', '--output', out.path
        }
    }
}
