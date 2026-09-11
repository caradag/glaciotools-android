pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google(); mavenCentral()
        // usb-serial-for-android se publica en JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "glaciotools"

// :core y :transport son Kotlin JVM puro y se prueban sin emulador ni dispositivo.
include(":core")
include(":transport")
// :transport-android es la capa fina que toca las APIs de Android: BLE y USB. La
// logica que se puede probar sin telefono se queda en :transport.
include(":transport-android")
include(":app")
