plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// The reader driver shared by the reader service and the card app: stock Sunmi service first, else the direct
// link over /dev/ttyS3, plus the public EMV read and the one-session rules.
android {
    namespace = "com.toss.cardreader.reader"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":client"))
    testImplementation("junit:junit:4.13.2")
}
