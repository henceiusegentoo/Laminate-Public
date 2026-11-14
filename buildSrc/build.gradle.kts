/**
 * buildSrc hosts the `downloadPaperclip` task implementation (Task 3) as a proper Gradle custom
 * task class, since a project's own `src/main/java` is not visible to that same project's own
 * `build.gradle.kts` at script-compile time (buildSrc is the standard way around that without
 * resorting to an ad hoc `doLast` block). No external dependencies are added here: the Fill
 * v3/legacy v2 clients use only `java.net.http.HttpClient` (built into the JDK 21 toolchain) plus
 * hand-rolled JSON field extraction - see PaperclipResolver's javadoc for why. If a real
 * dependency is ever needed here, add it via `gradle/libs.versions.toml`, not inline coordinates.
 */
plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}
