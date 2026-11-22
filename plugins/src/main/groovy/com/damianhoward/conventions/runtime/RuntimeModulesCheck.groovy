package com.damianhoward.conventions.runtime

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

import javax.inject.Inject

/**
 * Fails when the code links against a JDK module the image lacks. jdeps cannot see reflection, but
 * it does see every module the code compiles against, and each one it reports that the image does
 * not include is a NoClassDefFoundError waiting in production.
 *
 * The comparison is with the linked image's own module list, not with the names the image was
 * asked for: jlink resolves an aggregator such as java.se, and every transitive requirement, into
 * modules the request never names.
 */
@DisableCachingByDefault(because = 'jdeps answers in about a second, and its answer depends on the JDK, which is not an input')
abstract class RuntimeModulesCheck extends DefaultTask {
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) abstract DirectoryProperty getImage()

    @Classpath abstract ConfigurableFileCollection getJars()

    @Internal abstract DirectoryProperty getJdkHome()

    @OutputFile abstract RegularFileProperty getReport()

    @Inject abstract ExecOperations getExec()

    @TaskAction
    void verify() {
        def stdout = new ByteArrayOutputStream()
        exec.exec {
            commandLine([Tools.of(jdkHome.get().asFile, 'jdeps'), '--multi-release', '25', '--ignore-missing-deps', '--print-module-deps'] + jars.files*.path)
            standardOutput = stdout
        }
        def used = stdout.toString().trim().split(',').collect { it.trim() }.findAll { it }
        def line = new File(image.get().asFile, 'release').readLines().find { it.startsWith('MODULES=') }
        if (line == null) {
            throw new GradleException("${image.get()}/release lists no MODULES, so the image cannot be checked")
        }
        def held = line.substring(line.indexOf('"') + 1, line.lastIndexOf('"')).split(' ') as List
        def missing = used - held
        if (missing) {
            throw new GradleException("The code uses ${missing.join(', ')}, which the runtime image does not include. Add them to the runtime-image convention's module set.")
        }
        report.get().asFile.text = used.join('\n') + '\n'
    }
}
