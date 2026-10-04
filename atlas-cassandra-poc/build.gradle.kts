plugins { java }
repositories { mavenCentral() }
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
dependencies {
    implementation("org.apache.cassandra:java-driver-core:4.19.0")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
val integrationTest by sourceSets.creating
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
integrationTest.compileClasspath += sourceSets.main.get().output
integrationTest.runtimeClasspath += sourceSets.main.get().output
integrationTest.compileClasspath += sourceSets.test.get().output
integrationTest.runtimeClasspath += sourceSets.test.get().output
tasks.withType<Test>().configureEach { useJUnitPlatform(); testLogging { events("passed", "failed", "skipped") } }
val gradeCassandra by tasks.registering(Test::class) {
    description = "Run real Cassandra protocol graders; requires make up"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    outputs.upToDateWhen { false }
}
tasks.register("gradeModel") { dependsOn(tasks.test) }
tasks.register("grade") { dependsOn(tasks.test, gradeCassandra) }
