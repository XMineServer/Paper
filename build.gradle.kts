import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    id("io.papermc.paperweight.core") version "2.0.0-beta.21" apply false
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = Charsets.UTF_8.name()
        options.release = 25
        options.isFork = true
        options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-removal"))
    }
    tasks.withType<Javadoc>().configureEach {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources>().configureEach {
        filteringCharset = Charsets.UTF_8.name()
    }
    tasks.withType<Test>().configureEach {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
    }

    extensions.configure<PublishingExtension> {
        repositories {
            maven("https://artifactory.papermc.io/artifactory/releases/") {
                name = "paperReleases"
                credentials(PasswordCredentials::class)
            }
            // XMine start - свой Reposilite
            //
            // Раздел выбирается по версии, а не задаётся руками: снапшот в
            // releases отвергнется репозиторием, релиз в snapshots потеряет
            // неизменяемость адреса. Ошибиться тут нечем.
            //
            // Имена свойств учётки - те же, что у остальных проектов XMine
            // (XMinePlugins, XDiscovery, XMineTrafficTracker): локально
            // ~/.gradle/gradle.properties, в CI - переменные окружения. Здесь
            // НЕ credentials(PasswordCredentials::class): та форма потребовала
            // бы своих xmineUsername/xminePassword и развела бы форк с
            // остальными репозиториями по учёткам.
            maven {
                name = "xmine"
                val base = providers.gradleProperty("xmineMavenUrl")
                    .getOrElse("https://maven.xmine.world")
                val section = if (project.version.toString().endsWith("-SNAPSHOT")) {
                    "snapshots"
                } else {
                    "releases"
                }
                url = uri("$base/$section")
                credentials {
                    username = providers.gradleProperty("xmineMavenUsername")
                        .orElse(providers.environmentVariable("XMINE_MAVEN_USERNAME"))
                        .orNull
                    password = providers.gradleProperty("xmineMavenPassword")
                        .orElse(providers.environmentVariable("XMINE_MAVEN_PASSWORD"))
                        .orNull
                }
            }
            // XMine end - свой Reposilite
        }
    }
}

tasks.register("printMinecraftVersion") {
    val mcVersion = providers.gradleProperty("mcVersion")
    doLast {
        println(mcVersion.get().trim())
    }
}

tasks.register("printPaperVersion") {
    val paperVersion = provider { project.version }
    doLast {
        println(paperVersion.get())
    }
}
