plugins { kotlin("jvm") }

kotlin { jvmToolchain(21) }

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

dependencies { implementation(project(":core")) }

dependencies { testImplementation(project(":core")) }
