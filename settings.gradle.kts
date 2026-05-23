pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

if (!file(".git").exists()) {
    val errorText = """
        
        =====================[ ERROR ]=====================
         The Laminate project directory is not a properly cloned Git repository.
         
         In order to build Laminate from source you must clone
         the Laminate repository using Git, not download a code
         zip from GitHub.
         
         See https://github.com/henceiusegentoo/Laminate/blob/main/CONTRIBUTING.md
         for further information on building and modifying Laminate.
        ===================================================
    """.trimIndent()
    error(errorText)
}

rootProject.name = "laminate"

for (name in listOf("paper-api", "paper-server", "laminate-api")) {
    include(name)
    file(name).mkdirs()
}

optionalInclude("test-plugin")
optionalInclude("paper-generator")

fun optionalInclude(name: String, op: (ProjectDescriptor.() -> Unit)? = null) {
    val settingsFile = file("$name.settings.gradle.kts")
    if (settingsFile.exists()) {
        apply(from = settingsFile)
        findProject(":$name")?.let { op?.invoke(it) }
    } else {
        settingsFile.writeText(
            """
            // Uncomment to enable the '$name' project
            // include(":$name")

            """.trimIndent()
        )
    }
}

if (providers.gradleProperty("paperBuildCacheEnabled").orNull.toBoolean()) {
    val buildCacheUsername = providers.gradleProperty("paperBuildCacheUsername").orElse("").get()
    val buildCachePassword = providers.gradleProperty("paperBuildCachePassword").orElse("").get()
    if (buildCacheUsername.isBlank() || buildCachePassword.isBlank()) {
        println("The Paper remote build cache is enabled, but no credentials were provided. Remote build cache will not be used.")
    } else {
        val buildCacheUrl = providers.gradleProperty("paperBuildCacheUrl")
            .orElse("https://gradle-build-cache.papermc.io/")
            .get()
        val buildCachePush = providers.gradleProperty("paperBuildCachePush").orNull?.toBoolean()
            ?: System.getProperty("CI").toBoolean()
        buildCache {
            remote<HttpBuildCache> {
                url = uri(buildCacheUrl)
                isPush = buildCachePush
                credentials {
                    username = buildCacheUsername
                    password = buildCachePassword
                }
            }
        }
    }
}
