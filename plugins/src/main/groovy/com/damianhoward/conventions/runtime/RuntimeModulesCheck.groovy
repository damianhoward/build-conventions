package com.damianhoward.conventions.runtime

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

import javax.inject.Inject

/**
 * Fails when the code links against a JDK module the image lacks. jdeps cannot see reflection, but
 * it does see every module the code compiles against, and each one it reports that the image does
 * not include is a NoClassDefFoundError waiting in production.
 */
@DisableCachingByDefault(because = 'jdeps answers in about a second, and its answer depends on the JDK, which is not an input')
abstract class RuntimeModulesCheck extends DefaultTask {
    @Input abstract ListProperty<String> getModules()

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
        def missing = used - modules.get()
        if (missing) {
            throw new GradleException("The code uses ${missing.join(', ')}, which the runtime image does not include. Add them to runtimeImage.modules.")
        }
        report.get().asFile.text = used.join('\n') + '\n'
    }
}
