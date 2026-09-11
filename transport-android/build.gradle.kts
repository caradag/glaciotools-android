plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cl.umag.glaciertemp.transport.android"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
}

dependencies {
    api(project(":core"))
    api(project(":transport"))

    // Los cuatro chips USB-serie habituales (CH34x, CP21xx, FTDI, PL2303) y CDC-ACM. La
    // placa saca un header tipo FTDI, asi que el chip vive en el cable del usuario y hay
    // que soportarlos todos. Escribirlos a mano seria codigo que no se puede probar sin
    // tener cada cable delante.
    implementation("com.github.mik3y:usb-serial-for-android:3.9.0")
}
