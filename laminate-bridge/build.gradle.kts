/**
 * java + Lombok + toolchain(21) + mavenCentral() are already applied via the root `subprojects`
 * block. LaminateBridge itself has no dependencies beyond the JDK (see task-1-brief.md); the
 * dependencies below are test-only.
 */
dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
    testImplementation("org.assertj:assertj-core:3.27.7")
}

tasks.test {
    useJUnitPlatform()
}
