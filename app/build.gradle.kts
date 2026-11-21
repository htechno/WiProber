plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.wiprober"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.wiprober"
        minSdk = 30
        targetSdk = 36
        versionCode = 5
        versionName = "3.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    // Opt-in isolated package for testing the same R8 rules on a device.
    if (providers.gradleProperty("r8Validation").isPresent) {
        buildTypes.create("r8Validation") {
            initWith(buildTypes.getByName("release"))
            applicationIdSuffix = ".r8validation"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            proguardFile("r8-validation-rules.pro")
        }
        testBuildType = "r8Validation"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
    }

    // Universal APK configuration - includes all ABIs
    splits {
        abi {
            isEnable = false // Disable split APKs to create universal APK
        }
    }

}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.github.chrisbanes:PhotoView:2.3.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.activity:activity-ktx:1.11.0")
}
