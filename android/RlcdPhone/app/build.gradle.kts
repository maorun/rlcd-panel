plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersionCode = (project.findProperty("versionCode") as? String)?.toIntOrNull() ?: 1
val appVersionName = (project.findProperty("versionName") as? String) ?: "1.0.0"

android {
    namespace = "com.rlcdpanel.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rlcdpanel.phone"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${rootDir}/../../android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
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
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(layout.buildDirectory.dir("generated/assets"))
        }
    }
}

val copySharedConfig by tasks.registering(Copy::class) {
    val configFile = file("${rootDir}/../../docs/config.ini")
    val sourceFile = if (configFile.exists()) configFile else file("${rootDir}/../../docs/config.example.ini")
    from(sourceFile) {
        rename { "config.ini" }
    }
    into(layout.buildDirectory.dir("generated/assets"))
}

tasks.named("preBuild") {
    dependsOn(copySharedConfig)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui:1.7.5")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.5")
    implementation("androidx.compose.foundation:foundation:1.7.5")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.glance:glance-appwidget:1.1.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
