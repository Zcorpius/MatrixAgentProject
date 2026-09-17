pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MatrixAgent"

include(":matrix-agent-service")
include(":matrix-agent-service-lib")
include(":matrix-agent-launcher")
include(":matrix-agent-test")
include(":ondevice")
