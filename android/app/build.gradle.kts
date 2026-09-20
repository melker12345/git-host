plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.localgithub.githost"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.localgithub.githost"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        // Debug builds install side by side with the release app as "git-host2",
        // so a development build never replaces the app that holds your repos.
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "git-host2")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            // Avoid duplicate META-INF files from MINA SSHD / JGit transitive deps
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // JGit — server-side git smart protocol (UploadPack / ReceivePack)
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.8.0.202311291450-r") {
        exclude(group = "org.apache.httpcomponents")
        exclude(group = "org.slf4j")
    }

    // Apache MINA SSHD — embedded SSH server
    implementation("org.apache.sshd:sshd-core:2.12.1") {
        exclude(group = "org.slf4j", module = "slf4j-jdk14")
        exclude(group = "ch.qos.logback")
    }

    // SLF4J: API + simple binding (stderr → logcat on Android, required by MINA SSHD)
    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("org.slf4j:slf4j-simple:1.7.36")

    // BouncyCastle — Ed25519 and X25519 crypto
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
    implementation("org.bouncycastle:bcpkix-jdk15on:1.70")

    // QR scanning for "Add device -> Scan QR code"
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // DataStore (whitelist + config persistence)
    implementation("androidx.datastore:datastore-preferences:1.0.0")
}
