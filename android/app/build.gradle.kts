/*
 * Mobile Agent: an on-device phone agent. The fine-tuned LFM3-VL-2B (LFM3-VL-2B-MobileAgent) runs through Google's LiteRT-LM
 * on the phone's GPU, looks at the screen, picks one action, and an accessibility service performs it.
 */
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.experimentalmachines.mobileagent"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.experimentalmachines.mobileagent"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk.abiFilters += setOf("arm64-v8a")
        buildConfigField("String", "LITERT_LM_VERSION", "\"${libs.versions.litertlm.get()}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("com.google.ai.edge.litertlm.ExperimentalApi")
    }
}

dependencies {
    implementation(libs.litertlm.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
