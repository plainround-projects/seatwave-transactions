plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
}

group = "io.plainround"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(libs.result4j)
    implementation(libs.hikari)
    // Compile-time too: SqlStates reads the constraint name from the driver's error message.
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    // Waits for "the second hold is blocked on a lock" without sleeping a guessed time.
    testImplementation(libs.awaitility)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Transitive versions are written to gradle.lockfile, so two builds can never resolve different jars.
dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // Every warning is an error: a warning that is allowed to stay is a defect nobody owns.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        endWithNewline()
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

// The tests tagged "stack" need PostgreSQL; ./gradlew check never runs them, so a reader without
// Docker can still build and test the rest. PostgreSQL's isolation is the subject, so no
// embedded stand-in replaces it: H2 implements none of the behavior these tests prove.
tasks.test {
    useJUnitPlatform {
        excludeTags("stack")
    }
    testLogging {
        events("passed", "failed", "skipped")
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.register<Test>("stackTest") {
    group = "verification"
    description = "Runs the tests against the lab database; scripts/stack.sh test starts and stops it."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("stack")
    }
    systemProperty("seatwave.stackSettings", file("config/stack.properties").absolutePath)
    // The database is outside Gradle's view, so these tests are never up to date.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// Code that only runs against PostgreSQL is measured by the stack run, not by ./gradlew check.
val databaseOnly = listOf(
    "**/store/HoldStore*",
    "**/store/CheckThenWriteHolds*",
    "**/store/Transactions*",
    "**/store/Database*",
    "**/store/SqlStates*",
    "**/labs/**",
    "**/*Cli*",
)

tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching { exclude(databaseOnly) })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

// The same floor over everything but the programs, with both test runs' data; scripts/stack.sh test
// runs it. The programs (*Cli) are run by hand, and their output is printed in the README.
val programs = listOf("**/*Cli*")

tasks.register<JacocoReport>("stackCoverageReport") {
    group = "verification"
    description = "Writes the line coverage of the unit and stack test runs together."
    // Both runs: the unit tests' data and the stack tests' data.
    dependsOn(tasks.test, tasks.named("stackTest"))
    executionData(fileTree(layout.buildDirectory.dir("jacoco")).include("*.exec"))
    sourceDirectories.setFrom(sourceSets.main.get().allSource.srcDirs)
    classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching { exclude(programs) })
    reports {
        xml.required = true
    }
}

tasks.register<JacocoCoverageVerification>("stackCoverage") {
    group = "verification"
    description = "Checks line coverage of all main code but the programs over both test runs."
    dependsOn(tasks.named("stackCoverageReport"))
    executionData(fileTree(layout.buildDirectory.dir("jacoco")).include("*.exec"))
    classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching { exclude(programs) })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// One command per example.
tasks.register<JavaExec>("raceDemo") {
    group = "application"
    description = "Runs the in-memory races of Section 8 (no database needed)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.plainround.seatwave.transactions.demo.RaceDemoCli"
}

tasks.register<JavaExec>("holdTiming") {
    group = "application"
    description = "Times the hold transaction and two fans racing for one seat on the lab database."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.plainround.seatwave.transactions.labs.HoldTimingCli"
    workingDir = rootDir
    args("config/stack.properties")
}

tasks.register<JavaExec>("priceRace") {
    group = "application"
    description = "Runs Lab 2's throughput comparison against the lab database."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.plainround.seatwave.transactions.labs.PriceRaceCli"
    workingDir = rootDir
    args("config/stack.properties")
}
