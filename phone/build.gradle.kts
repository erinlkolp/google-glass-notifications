plugins { id("com.android.application") }

android {
    namespace = "dev.erinlkolp.glassnotify.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.erinlkolp.glassnotify.phone"
        minSdk = 26
        targetSdk = 28
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies {
    implementation(project(":wire"))
    testImplementation("junit:junit:4.13.2")
}

// Source/target 8 is pinned for the life of the project - the Glass device is
// API 22 - so javac's "obsolete" advice to move off it is not actionable.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-options")
}
