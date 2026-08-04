plugins { id("java-library") }

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

// Source/target 8 is pinned for the life of the project - the Glass device is
// API 22 - so javac's "obsolete" advice to move off it is not actionable.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.compilerArgs.add("-Xlint:-options")
}

dependencies { testImplementation("junit:junit:4.13.2") }
