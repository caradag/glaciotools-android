plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cl.umag.glaciertemp"
    compileSdk = 36

    defaultConfig {
        applicationId = "cl.umag.glaciertemp"
        minSdk = 26
        targetSdk = 36
        // Primera version que se distribuye para instalar. El versionCode tiene que subir
        // en cada APK que se publique, o Android se niega a instalarlo encima del anterior.
        versionCode = 30
        versionName = "2.9"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // El transporte TCP de depuracion solo existe en debug: habla con
            // tools/fake_glaciertemp.py, que el emulador alcanza en 10.0.2.2.
            buildConfigField("boolean", "ENABLE_TCP_TRANSPORT", "true")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "ENABLE_TCP_TRANSPORT", "false")
        }
    }

    /**
     * `-g` al instalar para los tests: concede los permisos declarados.
     *
     * NO es una comodidad. `MainActivity.onCreate` pide la localizacion, y el dialogo del
     * sistema (GrantPermissionsActivity) se abre ENCIMA de la app: el ComposeTestRule mira
     * entonces una ventana que no es la suya y falla con "No compose hierarchies found" antes
     * de ejecutar una sola asercion. Por eso estos tests no habian corrido nunca aqui.
     *
     * La regla de no usar `-g` sigue vigente para lo que la motivo --que la app se instale y
     * arranque como lo hace en un telefono de verdad-- y ese camino hay que probarlo a mano
     * al menos una vez por entrega, porque este banco ya no lo cubre.
     */
    testOptions { installation { installOptions("-g") } }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["androidTest"].kotlin.srcDir("src/androidTest/kotlin")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":transport"))
    implementation(project(":transport-android"))

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Espresso < 3.7 usa reflexion sobre InputManager.getInstance(), que ya no existe en
    // Android moderno: el test instrumentado moria con NoSuchMethodException en API 37.
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
