plugins { kotlin("multiplatform") }

kotlin {
    jvmToolchain(21)

    // -------------------------------- los objetivos --------------------------------
    //
    // Hoy solo JVM, que es lo que consumen :transport y :app. Los objetivos de iOS se
    // anaden con una linea cada uno, pero SOLO compilan en macOS, asi que declararlos
    // aqui rompería el build en esta maquina. El valor de ser multiplataforma ya se
    // cobra sin ellos: `commonMain` se compila contra la biblioteca estandar COMUN, de
    // modo que un `import java.*` ahi no compila. Eso convierte "esto es portable" en
    // algo que verifica el compilador en cada build, y no en una afirmacion de un
    // comentario que envejece.
    jvm()

    sourceSets {
        // Codigo sin ninguna dependencia de la plataforma: geometria, estadistica,
        // angulos, la proyeccion UTM, el horizonte, el apantallamiento, Intel HEX.
        val commonMain by getting

        // Lo que todavia usa java.time, java.io.File, java.util.Locale o BigDecimal.
        // Cada fichero que se libere de eso sube a commonMain sin mas tramite.
        val jvmMain by getting

        val jvmTest by getting {
            dependencies { implementation(kotlin("test")) }
        }
    }
}

tasks.named<Test>("jvmTest") { useJUnitPlatform() }
