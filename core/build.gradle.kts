plugins { kotlin("multiplatform") }

kotlin {
    jvmToolchain(21)

    // -------------------------------- los objetivos --------------------------------
    //
    // JVM es el que consumen :transport y :app. Los de iOS se anaden con una linea cada
    // uno el dia que haya un Mac; no compilan en Linux, por eso no estan aqui.
    jvm()

    // linuxX64 NO SE DISTRIBUYE: esta aqui para que la barrera exista.
    //
    // Medido, porque yo lo habia dado por supuesto y era FALSO: con un unico objetivo,
    // Kotlin no hace compilacion comun de verdad -- compila commonMain y jvmMain juntos
    // contra la JVM, y `import java.io.File` en commonMain pasa sin una queja. Lo mismo
    // `String.format`, que es una extension solo de la JVM y estaba colada en Utm.kt.
    //
    // Con un SEGUNDO objetivo la cosa cambia: commonMain tiene que compilar tambien para
    // Kotlin/Native, donde nada de java existe. Se elige Linux y no otro porque es el
    // unico Kotlin/Native que se puede construir en esta maquina, y por ser Native cubre
    // exactamente la misma clase de problemas que iOS. Es un centinela, no un producto.
    linuxX64()

    sourceSets {
        // Codigo sin ninguna dependencia de la plataforma: geometria, estadistica,
        // angulos, la proyeccion UTM, el horizonte, el apantallamiento, Intel HEX.
        val commonMain by getting

        // Lo que todavia usa java.time, java.io.File, java.util.Locale o BigDecimal.
        // Cada fichero que se libere de eso sube a commonMain sin mas tramite.
        val jvmMain by getting

        // Las pruebas del codigo comun corren en LOS DOS objetivos. Compilar para Native
        // dice que el codigo es portable; ejecutarlo ahi dice que ademas da los mismos
        // numeros, que no es lo mismo y es lo que de verdad interesa saber antes de que
        // exista una version para iPhone.
        val commonTest by getting {
            dependencies { implementation(kotlin("test")) }
        }

        val jvmTest by getting {
            dependencies { implementation(kotlin("test")) }
        }
    }
}

tasks.named<Test>("jvmTest") { useJUnitPlatform() }
