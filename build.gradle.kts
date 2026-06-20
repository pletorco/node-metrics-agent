import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskProvider
import java.io.File

plugins {
    // Core Java support
    java

    // Build a shaded (fat) JAR
    id("com.gradleup.shadow") version "9.3.0"

    // SonarQube code quality analysis
    id("org.sonarqube") version "7.1.0.6387"

    // JaCoCo test coverage
    jacoco

    // SBOM generation
    id("org.cyclonedx.bom") version "1.10.0"

    // Checkstyle for linting
    checkstyle
}

// -----------------------------------------------------------------------------
// Project metadata
// -----------------------------------------------------------------------------
group = "co.pletor"
version = "0.8.0"
description = "JVM agent for exposing host and container node metrics through JMX"

// -----------------------------------------------------------------------------
// Dependency versions
// -----------------------------------------------------------------------------
val junitJupiterVersion = "5.10.2"
val mockitoVersion = "5.14.2"

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
    // YAML parser used by the agent
    implementation("org.yaml:snakeyaml:2.5")

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
tasks.test {
    useJUnitPlatform()

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

// -----------------------------------------------------------------------------
// Java compiler options
//   - UTF-8 encoding
// -----------------------------------------------------------------------------
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"

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
            "Main-Class" to "co.pletor.nodemetrics.agent.ConfigCli",
            // Java agent premain entrypoint
            "Premain-Class" to "co.pletor.nodemetrics.agent.MetricsAgent",
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
    dependsOn(tasks.test)
    dependsOn(tasks.jacocoTestReport)
    dependsOn(tasks.cyclonedxBom)
    // Classifier "all" -> artifact name: *-all.jar
    archiveClassifier.set("all")
    // Keep Shadow inputs explicit so SBOM scope can match packaged dependencies.
    configurations = listOf(deploymentRuntimeConfiguration.get())

    // Relocate snakeyaml to avoid conflicts with application's classpath
    relocate("org.yaml.snakeyaml", "co.pletor.nodemetrics.shaded.snakeyaml")

    // Merge META-INF/service files from dependencies
    mergeServiceFiles()

    manifest {
        attributes(
            "Main-Class" to "co.pletor.nodemetrics.agent.ConfigCli",
            "Premain-Class" to "co.pletor.nodemetrics.agent.MetricsAgent",
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
tasks.cyclonedxBom {
    // Scope the SBOM to runtime dependencies that are packaged in the shaded artifact.
    // This excludes compileOnly/provided/test-only dependencies.
    includeConfigs.set(listOf(deploymentRuntimeConfigName))
    // Use the standard CycloneDX format
    schemaVersion.set("1.5")
    val outputFile = layout.buildDirectory.file("reports/bom.json")
    destination.set(outputFile.map { it.asFile.parentFile })
    outputName.set("bom")
    outputFormat.set("json")
}

// -----------------------------------------------------------------------------
// Trivy Security Scan
// -----------------------------------------------------------------------------
data class TrivyVulnerability(
    val severity: String,
    val cve: String,
    val pkg: String,
    val version: String
)

fun severityPriority(severity: String): Int = when (severity.uppercase()) {
    "CRITICAL" -> 5
    "HIGH" -> 4
    "MEDIUM" -> 3
    "LOW" -> 2
    "UNKNOWN" -> 1
    else -> 0
}

fun collectTrivyVulnerabilities(reportFile: File): List<TrivyVulnerability> {
    val report = JsonSlurper().parse(reportFile) as? Map<*, *> ?: return emptyList()
    val results = report["Results"] as? List<*> ?: return emptyList()

    return results.flatMap { result ->
        val resultMap = result as? Map<*, *> ?: return@flatMap emptyList()
        val vulnerabilities = resultMap["Vulnerabilities"] as? List<*> ?: return@flatMap emptyList()
        vulnerabilities.mapNotNull { vulnerability ->
            val vulnerabilityMap = vulnerability as? Map<*, *> ?: return@mapNotNull null
            val severity = (vulnerabilityMap["Severity"] as? String)?.uppercase() ?: "UNKNOWN"
            val cve = (vulnerabilityMap["VulnerabilityID"] as? String).orEmpty().ifBlank { "N/A" }
            val pkg = (vulnerabilityMap["PkgName"] as? String).orEmpty().ifBlank { "unknown" }
            val version = (vulnerabilityMap["InstalledVersion"] as? String).orEmpty().ifBlank { "unknown" }

            TrivyVulnerability(severity, cve, pkg, version)
        }
    }
}

fun summarizeTrivy(vulnerabilities: List<TrivyVulnerability>): Map<String, Int> {
    val summary = linkedMapOf(
        "TOTAL" to vulnerabilities.size,
        "CRITICAL" to 0,
        "HIGH" to 0,
        "MEDIUM" to 0,
        "LOW" to 0,
        "UNKNOWN" to 0
    )

    vulnerabilities.forEach { vulnerability ->
        val key = when (vulnerability.severity) {
            "CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN" -> vulnerability.severity
            else -> "UNKNOWN"
        }
        summary[key] = summary.getValue(key) + 1
    }

    return summary
}

fun registerTrivyTask(
    taskName: String,
    severityGate: String,
    taskDescription: String
): TaskProvider<DefaultTask> = tasks.register<DefaultTask>(taskName) {
    group = "verification"
    description = taskDescription
    dependsOn(tasks.cyclonedxBom)

    val bomFile = layout.buildDirectory.file("reports/bom.json")
    val reportFile = layout.projectDirectory.file("trivy-report.json")

    inputs.file(bomFile)
    outputs.file(reportFile)

    doLast {
        val bom = bomFile.get().asFile
        val report = reportFile.asFile
        val cacheDir = System.getenv("TRIVY_CACHE_DIR")
            ?.takeIf { it.isNotBlank() }
            ?: layout.projectDirectory.dir(".trivycache").asFile.absolutePath

        report.parentFile?.mkdirs()

        val command = listOf(
            "trivy",
            "sbom",
            "--exit-code", "1",
            "--severity", severityGate,
            "--format", "json",
            "--output", report.absolutePath,
            "--cache-dir", cacheDir,
            "--db-repository", "ghcr.io/aquasecurity/trivy-db:2",
            "--java-db-repository", "ghcr.io/aquasecurity/trivy-java-db:1",
            bom.absolutePath
        )

        val process = try {
            ProcessBuilder(command)
                .directory(project.rootDir)
                .redirectErrorStream(true)
                .inheritIO()
                .start()
        } catch (ex: Exception) {
            throw GradleException("Failed to execute Trivy. Install `trivy` and retry.", ex)
        }
        val exitCode = process.waitFor()

        if (!report.exists()) {
            throw GradleException("Trivy report was not generated: ${report.absolutePath}")
        }

        val vulnerabilities = collectTrivyVulnerabilities(report)
        val summary = summarizeTrivy(vulnerabilities)

        logger.lifecycle(
            "TOTAL=${summary["TOTAL"]}, CRITICAL=${summary["CRITICAL"]}, HIGH=${summary["HIGH"]}, " +
                "MEDIUM=${summary["MEDIUM"]}, LOW=${summary["LOW"]}, UNKNOWN=${summary["UNKNOWN"]}"
        )
        logger.lifecycle("Top 10 vulnerabilities (severity, cve, package@version):")

        val top10 = vulnerabilities
            .sortedWith(
                compareByDescending<TrivyVulnerability> { severityPriority(it.severity) }
                    .thenBy { it.cve }
                    .thenBy { it.pkg }
                    .thenBy { it.version }
            )
            .take(10)

        if (top10.isEmpty()) {
            logger.lifecycle("  none")
        } else {
            top10.forEachIndexed { index, vulnerability ->
                logger.lifecycle(
                    "  ${index + 1}. ${vulnerability.severity}, ${vulnerability.cve}, " +
                        "${vulnerability.pkg}@${vulnerability.version}"
                )
            }
        }

        if (exitCode != 0) {
            throw GradleException(
                "Trivy scan policy failed in `${name}` (severity gate: $severityGate). " +
                    "See ${report.absolutePath}"
            )
        }
    }
}

val trivyScan = registerTrivyTask(
    taskName = "trivyScan",
    severityGate = "HIGH,CRITICAL",
    taskDescription = "Scans SBOM and fails on HIGH/CRITICAL vulnerabilities"
)

registerTrivyTask(
    taskName = "trivyScanAll",
    severityGate = "UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL",
    taskDescription = "Scans SBOM and fails on any detected vulnerability"
)

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
    toolVersion = "10.12.5"
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
