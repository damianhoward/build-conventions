package com.damianhoward.conventions.runtime

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Starts the installed distribution through its own launcher and requires a 200 from each path.
 *
 * JAVA_HOME points at a JDK that does not exist, as the units point it at a box's: the launcher must
 * ignore it for the bundled runtime, and if it ever stops doing so the server never comes up and
 * this fails. The service's tests cannot run on the image -- JUnit, Playwright and Testcontainers
 * need modules the service does not -- so this is what proves the image itself runs the service.
 */
@DisableCachingByDefault(because = 'It proves a process starts and answers on this machine; a cached pass would prove nothing')
abstract class DistributionSmokeTest extends DefaultTask {
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) abstract DirectoryProperty getInstall()

    @Input abstract Property<String> getLauncher()

    @Input abstract ListProperty<String> getPaths()

    @Input abstract MapProperty<String, String> getEnvironment()

    @OutputFile abstract RegularFileProperty getLog()

    @TaskAction
    void probe() {
        int port = new ServerSocket(0).withCloseable { it.localPort }
        boolean windows = System.getProperty('os.name').startsWith('Windows')
        File bin = new File(install.get().asFile, 'bin')
        def command = windows ? ['cmd', '/c', new File(bin, "${launcher.get()}.bat").path] : [new File(bin, launcher.get()).path]
        def builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.get().asFile)
        builder.environment().putAll(environment.get())
        builder.environment().put('PORT', port.toString())
        builder.environment().put('JAVA_HOME', new File(temporaryDir, 'no-such-jdk').path)
        Process process = builder.start()
        try {
            String base = "http://127.0.0.1:${port}"
            long deadline = System.nanoTime() + 30_000_000_000L
            while (status("${base}/healthz") != 200) {
                if (!process.alive) {
                    throw new GradleException("the distribution exited with ${process.exitValue()}; see ${log.get().asFile}")
                }
                if (System.nanoTime() > deadline) {
                    throw new GradleException("the distribution did not answer /healthz in 30s; see ${log.get().asFile}")
                }
                Thread.sleep(250)
            }
            paths.get().each { path ->
                int code = status("${base}${path}")
                if (code != 200) {
                    throw new GradleException("${path} answered ${code} on the distribution's own runtime; see ${log.get().asFile}")
                }
            }
        } finally {
            process.descendants().forEach { it.destroy() }
            process.destroy()
            process.waitFor()
        }
    }

    static int status(String url) {
        try {
            def connection = (HttpURLConnection) new URL(url).openConnection()
            connection.connectTimeout = 1000
            connection.readTimeout = 5000
            return connection.responseCode
        } catch (IOException ignored) {
            return -1
        }
    }
}
