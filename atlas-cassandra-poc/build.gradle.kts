plugins { java }
repositories { mavenCentral() }
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
dependencies {
    implementation("org.apache.cassandra:java-driver-core:4.19.0")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}
val integrationTest by sourceSets.creating
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
integrationTest.compileClasspath += sourceSets.main.get().output
integrationTest.runtimeClasspath += sourceSets.main.get().output
integrationTest.compileClasspath += sourceSets.test.get().output
integrationTest.runtimeClasspath += sourceSets.test.get().output
tasks.withType<Test>().configureEach { doFirst { systemProperty("atlas.test.classpath", classpath.asPath) }; systemProperty("junit.jupiter.extensions.autodetection.enabled", "true"); useJUnitPlatform(); testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }
val gradeCassandra by tasks.registering(Test::class) {
    description = "Run real Cassandra protocol graders; requires make up"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    outputs.upToDateWhen { false }
    useJUnitPlatform { excludeTags("fault", "fullHa", "three", "phase") }
}
val gradeFaults by tasks.registering(Test::class) {
    description = "Real protocol-frame response loss and Docker coordinator restart"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform { includeTags("fault") }
    shouldRunAfter(gradeCassandra)
    outputs.upToDateWhen { false }
}
tasks.register<Test>("gradeFullHa") {
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform { includeTags("fullHa") }
    outputs.upToDateWhen { false }
}
// Compile integration fixtures before provisioning hosted Docker clusters.
tasks.register("gradeModel") { dependsOn(tasks.test, integrationTest.classesTaskName) }
tasks.named("build") { dependsOn(integrationTest.classesTaskName) }
tasks.register("grade") { dependsOn(tasks.test, gradeCassandra, gradeFaults) }
tasks.register<Test>("gradeRecoveryPhase") {
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform { includeTags("phase") }
    outputs.upToDateWhen { false }
}
listOf("Partition", "CoordinatorCrash", "Repair", "History", "Retention", "FaultWitness", "Archive").forEach { scenario ->
    tasks.register<Test>("grade$scenario") {
        testClassesDirs = integrationTest.output.classesDirs
        classpath = integrationTest.runtimeClasspath
        useJUnitPlatform { includeTags(scenario) }
        outputs.upToDateWhen { false }
    }
}
tasks.register("gradeTransactions") { dependsOn(tasks.test, gradeCassandra) }
tasks.register<Exec>("gradeMaintainer") {
    description = "Pinned upstream Cassandra 4.0.5 phase tests in a separate JDK11/Ant process"
    commandLine("bash", "scripts/maintainer.sh")
    outputs.upToDateWhen { false }
}
