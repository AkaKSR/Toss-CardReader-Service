import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from keystore.properties (not committed); see README.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Throwaway key for the "foreign" flavor: a client signed by someone else, which the service must refuse.
// Generated on first use into the build directory, never committed.
val foreignKeystore = layout.buildDirectory.file("foreign-key/foreign.p12").get().asFile
val makeForeignKey by tasks.registering(Exec::class) {
    outputs.file(foreignKeystore)
    onlyIf { !foreignKeystore.exists() }
    doFirst { foreignKeystore.parentFile.mkdirs() }
    commandLine(
        File(System.getProperty("java.home"), "bin/keytool").path, "-genkeypair", "-keystore", foreignKeystore.path, "-storetype", "PKCS12",
        "-storepass", "foreign-test", "-keypass", "foreign-test", "-alias", "foreign", "-keyalg", "RSA",
        "-keysize", "2048", "-validity", "3650", "-dname", "CN=Foreign Test Client",
    )
}

android {
    namespace = "com.toss.cardreader.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.toss.cardreader.sample"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
        create("foreign") {
            storeFile = foreignKeystore
            storePassword = "foreign-test"
            keyAlias = "foreign"
            keyPassword = "foreign-test"
        }
    }

    flavorDimensions += "signer"
    productFlavors {
        // Signed with the service's key: may bind.
        create("trusted") {
            dimension = "signer"
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        // A second app with the same key, to see BUSY between two allowed apps.
        create("second") {
            dimension = "signer"
            applicationIdSuffix = ".second"
            versionNameSuffix = "-second"
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        // Signed with an unrelated key: must be refused.
        create("foreign") {
            dimension = "signer"
            applicationIdSuffix = ".foreign"
            versionNameSuffix = "-foreign"
            signingConfig = signingConfigs.getByName("foreign")
        }
    }

    buildTypes {
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
}

tasks.matching { it.name.startsWith("validateSigningForeign") || it.name.startsWith("packageForeign") }
    .configureEach { dependsOn(makeForeignKey) }

dependencies {
    implementation(project(":client"))
}
