plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.benjamin.factura"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.benjamin.factura"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_CURRENCY", "\"KES\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Apache POI (Excel export) and iText (PDF export) both ship licensing/module
    // metadata under overlapping META-INF paths, which causes duplicate-file build
    // failures without these excludes.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.kotlin_module",
                "META-INF/INDEX.LIST"
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // Image loading
    implementation(libs.coil.compose)

    // Credentials & Google Sign-In
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    // Gson
    // NOTE: project convention serializes via org.json through each model's own
    // toMap()/fromMap() — Gson is not actually used anywhere. Left in place since
    // Gradle files are locked after initial setup; remove only on explicit instruction.
    implementation(libs.gson)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.messaging)
    implementation(libs.play.services.coroutines)

    // Gemini
    implementation(libs.generative.ai)

    // PDF export (invoices, PDF-format reports) — Android-native iText artifact;
    // do NOT use plain com.itextpdf:itext7-core, it pulls in java.awt/Swing classes
    // that don't exist on the Android runtime.
    implementation(libs.itext.core.android)

    // Excel export (ReportsScreen formatted exports)
    implementation(libs.poi.ooxml)
    // NOTE: poi-ooxml has a history of Android runtime issues (java.awt.geom classes
    // used by XSSF drawing code aren't part of the Android runtime). Test export
    // early. If it breaks, the community fork com.github.SUPERCILEX:poi-android
    // (via JitPack) is the common fallback.

    // Receipt OCR — bundled model (offline-capable, no Play Services download wait)
    implementation(libs.mlkit.text.recognition)

    // Offline-first sync engine ("Via Appia") + Smart Reminders (WorkManager + Gemini)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // CSV export (SettingsScreen raw-data export) — no dependency needed;
    // CsvExporter writes plain delimited text directly.

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Navigation
    implementation("androidx.navigation:navigation-runtime-ktx:2.8.5")
    implementation("androidx.navigation:navigation-compose:2.8.5")
}