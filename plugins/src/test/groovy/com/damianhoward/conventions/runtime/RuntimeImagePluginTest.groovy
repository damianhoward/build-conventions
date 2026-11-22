package com.damianhoward.conventions.runtime

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class RuntimeImagePluginTest {
    @TempDir
    File project

    // A server that uses jdk.httpserver, so the module check has something real to find.
    private static final String MAIN = '''\
        package fixture;

        import com.sun.net.httpserver.HttpServer;
        import java.net.InetSocketAddress;

        public class Main {
            public static void main(String[] args) throws Exception {
                int port = Integer.parseInt(System.getenv("PORT"));
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
                for (String path : new String[] {"/healthz", "/hello"}) {
                    server.createContext(path, exchange -> {
                        exchange.sendResponseHeaders(200, -1);
                        exchange.close();
                    });
                }
                server.start();
            }
        }
        '''.stripIndent()

    @BeforeEach
    void fixture() {
        new File(project, 'settings.gradle').text = "rootProject.name = 'fixture'\n"
        new File(project, 'gradle.properties').text = """\
            org.gradle.java.installations.paths=${System.getProperty('fixture.jdk').replace('\\', '/')}
            org.gradle.java.installations.auto-detect=false
            org.gradle.java.installations.auto-download=false
            """.stripIndent()
        def source = new File(project, 'src/main/java/fixture')
        source.mkdirs()
        new File(source, 'Main.java').text = MAIN
        build(smokePaths: "['/hello']")
    }

    private void build(Map settings) {
        new File(project, 'build.gradle').text = """\
            plugins {
                id 'java'
                id 'application'
                id 'com.damianhoward.runtime-image'
            }
            java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
            application {
                applicationName = 'fixture'
                mainClass = 'fixture.Main'
            }
            runtimeImage {
                smokePaths = ${settings.smokePaths}
                ${settings.minimumJdk ? "minimumJdk = '${settings.minimumJdk}'" : ''}
                ${settings.startsInCi != null ? "startsInCi = ${settings.startsInCi}" : ''}
            }
            """.stripIndent()
    }

    private GradleRunner runner(String... tasks) {
        GradleRunner.create().withProjectDir(project).withPluginClasspath().withArguments(tasks + ['--stacktrace'] as List)
    }

    @Test
    void 'links a runtime with the diagnostic tools and no compiler, and the distribution runs on it'() {
        BuildResult result = runner('check').build()

        assertEquals(TaskOutcome.SUCCESS, result.task(':verifyRuntimeModules').outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(':smokeTestDistribution').outcome)
        def runtime = new File(project, 'build/install/fixture/runtime')
        def modules = new File(runtime, 'release').readLines().find { it.startsWith('MODULES=') }
        assertTrue(modules.contains('jdk.httpserver'), modules)
        // Resolved through java.se, and the modules a JDBC driver and Logback load by name.
        assertTrue(modules.contains('java.sql'), modules)
        assertTrue(modules.contains('java.logging'), modules)
        assertTrue(modules.contains('jdk.naming.dns'), modules)
        assertTrue(modules.contains('jdk.jcmd'), modules)
        assertTrue(modules.contains('jdk.jfr'), modules)
        assertFalse(modules.contains('jdk.compiler'), modules)
        def launcher = new File(project, 'build/install/fixture/bin/fixture').text
        assertTrue(launcher.contains('[ -x "$APP_HOME/runtime/bin/java" ] && JAVA_HOME=$APP_HOME/runtime'))
    }

    @Test
    void 'fails when the code uses a module the image leaves out'() {
        // The compiler is the module every image leaves out on purpose.
        new File(project, 'src/main/java/fixture/UsesCompiler.java').text = '''            package fixture;

            public class UsesCompiler {
                public static Object tree() { return com.sun.source.tree.Tree.Kind.CLASS; }
            }
            '''.stripIndent()

        BuildResult result = runner('verifyRuntimeModules').buildAndFail()

        assertTrue(result.output.contains('The code uses jdk.compiler, which the runtime image does not include'), result.output)
    }

    @Test
    void 'a service that cannot start in CI skips the smoke test'() {
        build(smokePaths: "['/hello']", startsInCi: false)

        BuildResult result = runner('check').build()

        assertEquals(TaskOutcome.SUCCESS, result.task(':verifyRuntimeModules').outcome)
        assertEquals(TaskOutcome.SKIPPED, result.task(':smokeTestDistribution').outcome)
    }

    @Test
    void 'refuses to link from a JDK below the floor'() {
        build(smokePaths: "[]", minimumJdk: '99')

        BuildResult result = runner('runtimeImage').buildAndFail()

        assertTrue(result.output.contains('Refusing to link the runtime from JDK'), result.output)
        assertTrue(result.output.contains('the floor is 99'), result.output)
    }

    @Test
    void 'fails when a smoke path does not answer 200 on the image'() {
        build(smokePaths: "['/missing']")

        BuildResult result = runner('smokeTestDistribution').buildAndFail()

        assertTrue(result.output.contains('/missing answered 404'), result.output)
    }
}
