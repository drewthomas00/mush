plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from the environment (CI secrets); without it the release build is unsigned.
val keystorePath: String? = System.getenv("MUSH_KEYSTORE")

// Donation links shown under Setup → Support Mush (gradle.properties). Empty hides the button.
val donateVenmo = providers.gradleProperty("mush.donate.venmo").getOrElse("")
val donatePaypal = providers.gradleProperty("mush.donate.paypal").getOrElse("")

android {
    namespace = "app.mush.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.drewthomas00.mush"
        minSdk = 33 // Android 13+
        targetSdk = 35
        // Bump both for every release; F-Droid reads them from here and from the vX.Y.Z tag.
        versionCode = 10
        versionName = "1.0.0"
        buildConfigField("String", "DONATE_VENMO", "\"$donateVenmo\"")
        buildConfigField("String", "DONATE_PAYPAL", "\"$donatePaypal\"")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("MUSH_KEYSTORE_PASSWORD")
                keyAlias = "mush"
                keyPassword = System.getenv("MUSH_KEYSTORE_PASSWORD")
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false }

    // No Google dependency info block: F-Droid can't verify it and doesn't want it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

/**
 * The web pages (public/) and the detection rules (rules/android.json) ship inside the app, so it
 * works on its own: pages are served from assets at https://mush.local (see LocalWeb.kt).
 */
abstract class MushAssets : DefaultTask() {
    @get:InputDirectory abstract val web: DirectoryProperty
    @get:InputFile abstract val rules: RegularFileProperty
    @get:OutputDirectory abstract val out: DirectoryProperty

    @TaskAction
    fun copy() {
        val o = out.get().asFile
        o.deleteRecursively()
        web.get().asFile.copyRecursively(File(o, "web"), overwrite = true)
        rules.get().asFile.copyTo(File(o, "android.json"), overwrite = true)
    }
}

val mushAssets = tasks.register<MushAssets>("mushAssets") {
    web.set(file("../../public"))
    rules.set(file("../../rules/android.json"))
    out.set(layout.buildDirectory.dir("generated/mushAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(mushAssets, MushAssets::out)
    }
}
