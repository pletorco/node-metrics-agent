import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import java.io.File
import java.time.Instant
import org.cyclonedx.Version
import org.cyclonedx.gradle.CyclonedxDirectTask

buildscript {
    dependencies {
        // Patch the plugins' shared classpath: CycloneDX brings Jackson and Maven's Guava.
        classpath(platform("com.fasterxml.jackson:jackson-bom:2.22.3"))
        classpath("com.google.guava:guava:33.7.2-jre") {
            because("CVE-2026-102554: unbounded allocation during deserialization")
        }
    }
}

plugins {
    // Core Java support
    java

    // Build a shaded (fat) JAR
    id("com.gradleup.shadow") version "9.6.1"

    // SonarQube code quality analysis
    id("org.sonarqube") version "7.5.0.8588"

    // JaCoCo test coverage
    jacoco

    // SBOM generation
    id("org.cyclonedx.bom") version "3.5.1"

    // Checkstyle for linting
    checkstyle

    // Formatting (google-java-format): `spotlessCheck` verifies, `spotlessApply` fixes
    id("com.diffplug.spotless") version "8.11.0"
}

// -----------------------------------------------------------------------------
// Project metadata
// -----------------------------------------------------------------------------
group = "kr.pletor"
version = "0.10.1"
description = "JVM agent for exposing host and container node metrics through JMX"

// -----------------------------------------------------------------------------
// Dependency versions
// -----------------------------------------------------------------------------
val junitJupiterVersion = "5.10.2"
val mockitoVersion = "5.24.0"
val googleJavaFormatVersion = "1.25.2"

// -----------------------------------------------------------------------------
// Java / toolchain configuration
//   - Build with Java 21 toolchain
//   - Target Java 11 bytecode for compatibility with Kafka runtimes
// -----------------------------------------------------------------------------
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }

    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11

    // Publish -sources and -javadoc jars (useful for IDEs and repositories)
    withSourcesJar()
    withJavadocJar()
}

// -----------------------------------------------------------------------------
// Repositories
// -----------------------------------------------------------------------------
repositories {
    mavenCentral()
}

// -----------------------------------------------------------------------------
// Metadata Generation
// -----------------------------------------------------------------------------
val generateVersionProperties by tasks.registering {
    group = "build"
    description = "Generates version.properties containing build metadata"

    // Declare inputs so Gradle knows when to re-run this task
    val ciCommitSha = providers.environmentVariable("CI_COMMIT_SHA")
    val githubSha = providers.environmentVariable("GITHUB_SHA")
    inputs.property("version", project.version.toString())
    inputs.property("ciCommitSha", ciCommitSha.orElse(""))
    inputs.property("githubSha", githubSha.orElse(""))

    val outputFile = layout.buildDirectory.file("generated/resources/version.properties")
    outputs.file(outputFile)

    doLast {
        var commitId = ciCommitSha.getOrNull() ?: githubSha.getOrNull()

        if (commitId == null || commitId.isEmpty()) {
            commitId = try {
                // Try to handle "dubious ownership" in containers
                ProcessBuilder("git", "config", "--global", "--add", "safe.directory", "*").start().waitFor()
                
                val process = ProcessBuilder("git", "rev-parse", "HEAD").start()
                val result = process.inputStream.bufferedReader().readText().trim()
                if (process.waitFor() == 0 && result.isNotEmpty()) result else null
            } catch (e: Exception) {
                null
            }
        }

        val finalCommitId = commitId?.take(7) ?: "unknown"
        logger.lifecycle(">> [Metadata] version=${project.version}, commitId=$finalCommitId")

        outputFile.get().asFile.parentFile.mkdirs()
        outputFile.get().asFile.writeText(
            """
            version=${project.version}
            commitId=$finalCommitId
            buildTime=${System.currentTimeMillis()}
            """.trimIndent()
        )
    }
}

sourceSets {
    main {
        resources {
            srcDir(layout.buildDirectory.dir("generated/resources"))
        }
    }
}

tasks.processResources {
    dependsOn(generateVersionProperties)
}

tasks.named("sourcesJar") {
    dependsOn(generateVersionProperties)
}

// -----------------------------------------------------------------------------
// Dependencies
// -----------------------------------------------------------------------------
dependencies {
    // Spotless resolves the formatter in a detached configuration. A component metadata rule
    // also applies there, unlike a resolution strategy on the project's named configurations.
    components.withModule("com.google.googlejavaformat:google-java-format") {
        allVariants {
            withDependencies {
                add("com.google.guava:guava:33.7.2-jre")
            }
        }
    }

    // YAML parser used by the agent
    implementation("org.yaml:snakeyaml:2.7")

    // JUnit 5 (BOM + API)
    testImplementation(platform("org.junit:junit-bom:$junitJupiterVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")

    // Gradle 9.x: JUnit Platform launcher must be added explicitly
    // to avoid "Failed to load JUnit Platform" errors.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Mockito (includes static mocking support)
    testImplementation("org.mockito:mockito-core:$mockitoVersion")
}

// -----------------------------------------------------------------------------
// Test task configuration
//   - Use JUnit 5
//   - Run tests sequentially
//   - Always generate JaCoCo coverage report after tests
// -----------------------------------------------------------------------------
// Run the tests on another JVM to verify runtime compatibility, for example:
//   ./gradlew test -PtestJavaVersion=11
// The build itself always uses the Java 21 toolchain.
val testJavaVersion = providers.gradleProperty("testJavaVersion").map { it.toInt() }

tasks.test {
    useJUnitPlatform()

    if (testJavaVersion.isPresent) {
        javaLauncher.set(
            javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(testJavaVersion.get()))
            }
        )
    }

    // Similar to Maven Surefire parallel = none
    maxParallelForks = 1

    // Generate JaCoCo report after test execution
    finalizedBy(tasks.jacocoTestReport)
}

// -----------------------------------------------------------------------------
// JaCoCo configuration
//   - Gradle 9 already ships a recent JaCoCo
//   - Override toolVersion here if you need a specific version
// -----------------------------------------------------------------------------
jacoco {
    // toolVersion = "0.8.13"
}

// -----------------------------------------------------------------------------
// JaCoCo report configuration
//   - Produce XML (for SonarQube) and human-readable HTML report
// -----------------------------------------------------------------------------
tasks.jacocoTestReport {
    // Tests must run before coverage report generation
    dependsOn(tasks.test)

    reports {
        xml.required.set(true)
        csv.required.set(false)
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacocoHtml"))
    }
}

// Protect host-isolation and input-handling code with per-class floors. Do not use a blanket
// target: proc/cgroup fallbacks and platform-only branches have different reachable baselines.
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        val minimums = listOf(
            Triple("kr.pletor.nodemetrics.agent.ConfigLoader", "0.95", "0.90"),
            Triple("kr.pletor.nodemetrics.agent.ConfigReloader", "0.80", "0.75"),
            Triple("kr.pletor.nodemetrics.agent.MetricsRefreshEngine", "0.90", "0.80"),
            Triple("kr.pletor.nodemetrics.metrics.CpuMetrics", "0.85", "0.75"),
            Triple("kr.pletor.nodemetrics.metrics.LinuxProcFs", "0.85", "0.75"),
            Triple("kr.pletor.nodemetrics.metrics.CgroupCpuLimit", "0.90", "0.90")
        )
        minimums.forEach { (className, lines, branches) ->
            rule {
                element = "CLASS"
                includes = listOf(className)
                limit {
                    counter = "LINE"
                    minimum = lines.toBigDecimal()
                }
                limit {
                    counter = "BRANCH"
                    minimum = branches.toBigDecimal()
                }
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// -----------------------------------------------------------------------------
// Java compiler options
//   - UTF-8 encoding
// -----------------------------------------------------------------------------
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"

    // Compile against the Java 11 API, not just Java 11 bytecode. With only
    // source/targetCompatibility, code built on JDK 21 could call newer APIs that
    // then fail at runtime on the Java 11+ JVMs (for example Kafka brokers).
    options.release.set(11)

    // If you need extra compiler arguments in the future, add here:
    // options.compilerArgs.addAll(
    //     listOf(
    //         "-Alog4j.graalvm.groupId=${project.group}",
    //         "-Alog4j.graalvm.artifactId=${project.name}",
    //         "--add-modules", "java.management"
    //     )
    // )
}

// -----------------------------------------------------------------------------
// Artifact versioning configuration
//   - Force filenames to use x.x.x versioning even if project version is x.x.x.x
// -----------------------------------------------------------------------------
val deploymentRuntimeConfigName = "runtimeClasspath"
val deploymentRuntimeConfiguration = configurations.named(deploymentRuntimeConfigName)

tasks.withType<Jar>().configureEach {
    val v = project.version.toString()
    archiveVersion.set(v.split(".").take(3).joinToString("."))
}

// -----------------------------------------------------------------------------
// JAR manifest configuration
//   - Thin JAR for environments like Kafka 4.x where dependencies are provided
// -----------------------------------------------------------------------------
tasks.jar {
    manifest {
        attributes(
            // CLI entrypoint
            "Main-Class" to "kr.pletor.nodemetrics.agent.ConfigCli",
            // Java agent premain entrypoint
            "Premain-Class" to "kr.pletor.nodemetrics.agent.AgentLauncher",
            "Can-Redefine-Classes" to "false",
            "Can-Retransform-Classes" to "false"
        )
    }
    // Keep this JAR lean; the distribution uses the shaded JAR
}

// -----------------------------------------------------------------------------
// Shaded (fat) agent JAR with relocated dependencies to avoid clashes
// -----------------------------------------------------------------------------
tasks.shadowJar {
    // Tests are not a prerequisite of packaging: run `check` (tests + checkstyle) separately.
    // CI and the release workflow do so explicitly.
    dependsOn(tasks.cyclonedxBom)
    // Classifier "all" -> artifact name: *-all.jar
    archiveClassifier.set("all")
    // Keep Shadow inputs explicit so SBOM scope can match packaged dependencies.
    configurations = listOf(deploymentRuntimeConfiguration.get())

    // SnakeYAML creates its logger while constructing a parser. Redirect both base and Java 9+
    // logger references before the general relocation so neither JUL nor System.Logger is used.
    exclude("org/yaml/snakeyaml/internal/Logger*.class")
    exclude("META-INF/versions/*/org/yaml/snakeyaml/internal/Logger*.class")
    relocate("org.yaml.snakeyaml.internal.Logger", "kr.pletor.nodemetrics.agent.SnakeYamlLog")
    // Relocate snakeyaml to avoid conflicts with application's classpath
    relocate("org.yaml.snakeyaml", "kr.pletor.nodemetrics.shaded.snakeyaml")

    // Merge META-INF/service files from dependencies
    mergeServiceFiles()

    manifest {
        attributes(
            "Main-Class" to "kr.pletor.nodemetrics.agent.ConfigCli",
            "Premain-Class" to "kr.pletor.nodemetrics.agent.AgentLauncher",
            "Can-Redefine-Classes" to "false",
            "Can-Retransform-Classes" to "false"
        )
    }

    // Try to minimize the shaded JAR (remove unused classes)
    minimize()

    // Include only the SBOM file in the shaded JAR.
    from(layout.buildDirectory.file("reports/bom.json")) {
        into("META-INF/cyclonedx")
    }
}

// Convenience task: build only the shaded agent JAR
tasks.register("dist") {
    dependsOn(tasks.shadowJar)
    group = "build"
    description = "Build shaded agent JAR"
}

// -----------------------------------------------------------------------------
// SonarQube configuration
//   - Host URL and token are injected via JVM system properties
//     (this allows different settings for local vs. CI environments)
// -----------------------------------------------------------------------------
sonarqube {
    properties {
        property("sonar.projectKey", "node-metrics-agent")
        property("sonar.projectName", "node metrics agent")

        // Optional local/CI settings:
        //   -Dsonar.host.url=... -Dsonar.token=...
        System.getProperty("sonar.host.url")?.let { property("sonar.host.url", it) }
        System.getProperty("sonar.token")?.let { property("sonar.token", it) }

        property("sonar.qualitygate.wait", "true")

        property("sonar.sources", "src/main/java")
        property("sonar.tests", "src/test/java")
        property("sonar.sourceEncoding", "UTF-8")

        property("sonar.java.coveragePlugin", "jacoco")
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            "build/reports/jacoco/test/jacocoTestReport.xml"
        )

        property("sonar.junit.reportPaths", "build/test-results/test")

        // Exclude MBean interfaces from coverage
        property("sonar.coverage.exclusions", "**/*MBean.java")
    }
}

tasks.named("sonar") {
    dependsOn(tasks.jacocoTestReport)
}

// -----------------------------------------------------------------------------
// CycloneDX SBOM Configuration
// -----------------------------------------------------------------------------
// In cyclonedx-gradle-plugin 3.x, `cyclonedxDirectBom` generates the BOM of this project and
// `cyclonedxBom` aggregates the direct BOMs (of one project here). Which configurations end up in
// the BOM is decided by the direct task; without `includeConfigs` it would list every configuration
// (test and build tooling included), not just what is packaged in the shaded jar.
tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
    // Scope the SBOM to runtime dependencies that are packaged in the shaded artifact.
    // This excludes compileOnly/provided/test-only dependencies.
    includeConfigs.set(listOf(deploymentRuntimeConfigName))
    // Use the standard CycloneDX format (the plugin default is newer).
    schemaVersion.set(Version.VERSION_15)
}

tasks.cyclonedxBom {
    schemaVersion.set(Version.VERSION_15)
    // Consumers (shadowJar, Trivy, the release workflow) read build/reports/bom.json.
    jsonOutput.set(layout.buildDirectory.file("reports/bom.json"))
}

// -----------------------------------------------------------------------------
// Security inventory and scans (separate from the deployment SBOM)
// -----------------------------------------------------------------------------
val securityFormatter = configurations.create("securityFormatter") {
    isCanBeConsumed = false
    isCanBeResolved = true
    description = "Resolve the same formatter and metadata rules as Spotless for security auditing"
}
dependencies {
    add(securityFormatter.name, "com.google.googlejavaformat:google-java-format:$googleJavaFormatVersion")
}

val securityInventory = tasks.register("securityInventory") {
    group = "verification"
    description = "Exports resolved runtime, test and build-tool components with their scopes"
    // Resolution and the commit identity can change without a source-file change.
    doNotTrackState("Security inventories must describe the current resolved dependencies")
    doLast {
        val reportDir = layout.buildDirectory.dir("reports/security").get().asFile
        reportDir.mkdirs()
        // Dependency resolution can fail before the Python scanner starts. Invalidate old success
        // here too, so a reused workspace cannot upload a previous clean summary for a failed run.
        File(reportDir, "summary.json").writeText(JsonOutput.toJson(mapOf(
            "scanStatus" to "incomplete", "phase" to "inventory",
            "startedAt" to Instant.now().toString()
        )))
        val scopes = linkedMapOf(
            "runtimeClasspath" to configurations.getByName("runtimeClasspath"),
            "testRuntimeClasspath" to configurations.getByName("testRuntimeClasspath"),
            "checkstyle" to configurations.getByName("checkstyle"),
            "jacocoAgent" to configurations.getByName("jacocoAgent"),
            "jacocoAnt" to configurations.getByName("jacocoAnt"),
            "formatter" to securityFormatter,
            "buildscript" to buildscript.configurations.getByName("classpath")
        )
        val components = sortedMapOf<String, MutableMap<String, Any>>()
        scopes.forEach { (scope, configuration) ->
            val artifacts = configuration.resolvedConfiguration.resolvedArtifacts
            check(artifacts.isNotEmpty()) { "No security inventory artifacts for $scope" }
            artifacts.forEach { artifact ->
                val id = artifact.moduleVersion.id
                val key = "${id.group}:${id.name}:${id.version}"
                val component = components.getOrPut(key) {
                    mutableMapOf(
                        "group" to id.group, "name" to id.name, "version" to id.version,
                        "scopes" to sortedSetOf<String>()
                    )
                }
                @Suppress("UNCHECKED_CAST")
                (component.getValue("scopes") as MutableSet<String>).add(scope)
            }
        }
        val git = ProcessBuilder("git", "rev-parse", "HEAD").directory(rootDir).start()
        val commit = git.inputStream.bufferedReader().readText().trim()
        check(git.waitFor() == 0 && commit.matches(Regex("[0-9a-f]{40}"))) {
            "Cannot determine the security inventory's commit"
        }
        val inventory = mapOf(
            "schemaVersion" to 1,
            "commit" to commit,
            "gradleVersion" to gradle.gradleVersion,
            "formatterVersion" to googleJavaFormatVersion,
            "components" to components.values.toList()
        )
        val output = File(reportDir, "inventory.json")
        output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(inventory)))
        logger.lifecycle("Security inventory: ${components.size} components across ${scopes.size} scopes")
    }
}

fun registerSecurityScan(name: String, severity: String) = tasks.register<Exec>(name) {
    group = "verification"
    description = "Scans runtime and tooling dependencies plus Gradle vendor advisories ($severity)"
    dependsOn(securityInventory, tasks.cyclonedxBom)
    doNotTrackState("Vulnerability intelligence and DB freshness change independently of the SBOM")
    commandLine(
        "python3", "scripts/security_scan.py",
        "--inventory", "build/reports/security/inventory.json",
        "--runtime-bom", "build/reports/bom.json",
        "--output-dir", "build/reports/security",
        "--severity", severity
    )
}

registerSecurityScan("trivyScan", "HIGH,CRITICAL")
registerSecurityScan("trivyScanAll", "UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL")

// -----------------------------------------------------------------------------
// Utility task: print project version (useful for scripts/CI)
// -----------------------------------------------------------------------------
tasks.register<DefaultTask>("printVersion") {
    group = "help"
    description = "Prints the project version to stdout"

    doLast {
        val v = project.version.toString()
        println(v.split(".").take(3).joinToString("."))
    }
}

// -----------------------------------------------------------------------------
// Checkstyle Configuration
// -----------------------------------------------------------------------------
checkstyle {
    toolVersion = "14.4.0"
    // Use Google Checks by default
    // Gradle's default config location is config/checkstyle/checkstyle.xml
    // if not found, it falls back to built-in sun_checks.xml or google_checks.xml if explicitly configured
}

tasks.withType<Checkstyle>().configureEach {
    reports {
        xml.required.set(false)
        html.required.set(true)
    }
}

// The code base is checkstyle-clean: any warning fails the build.
tasks.named<Checkstyle>("checkstyleMain") { maxWarnings = 0 }
tasks.named<Checkstyle>("checkstyleTest") { maxWarnings = 0 }

// -----------------------------------------------------------------------------
// Formatting
//   - The code base is formatted with google-java-format; keep it that way with
//     `./gradlew spotlessApply` (CI runs `spotlessCheck`).
// -----------------------------------------------------------------------------
spotless {
    java {
        target("src/**/*.java", "scripts/soak/*.java", "scripts/LauncherFailureProbe.java", "scripts/LateGlobalSetup.java")
        googleJavaFormat(googleJavaFormatVersion)
    }
}
