import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val cartoKey = providers.environmentVariable("CARTO_API_KEY").orElse(local.getProperty("CARTO_API_KEY", "")).get()
val releaseKeystore = file(providers.environmentVariable("FREEDAR_KEYSTORE_PATH")
    .orElse("${System.getProperty("user.home")}/.local/share/freedar/signing/freedar-release.jks").get())
val releaseKeyPassword = providers.environmentVariable("FREEDAR_KEY_PASSWORD").orNull
val validateReleaseConfiguration = tasks.register("validateReleaseConfiguration") {
    doLast {
        check(releaseKeystore.isFile) { "Release keystore not found. Set FREEDAR_KEYSTORE_PATH or create the Freedar release key." }
        check(!releaseKeyPassword.isNullOrEmpty()) { "Set FREEDAR_KEY_PASSWORD before building a release." }
        check(cartoKey.isNotBlank()) { "Set CARTO_API_KEY before building a release with live basemaps." }
    }
}
android {
    namespace = "com.freedar"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.freedar"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "CARTO_API_KEY", "\"${cartoKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }
    signingConfigs {
        create("release") {
            storeFile = releaseKeystore
            storePassword = releaseKeyPassword
            keyAlias = "freedar"
            keyPassword = releaseKeyPassword
            storeType = "JKS"
        }
    }
    buildTypes {
        getByName("release") { signingConfig = signingConfigs.getByName("release") }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    androidResources { noCompress += "gzip" }
}
tasks.matching { it.name in setOf("packageRelease", "packageReleaseBundle", "validateSigningRelease") }.configureEach {
    dependsOn(validateReleaseConfiguration)
}
dependencies {
    implementation(project(":radar"))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.maplibre.gl:android-sdk-opengl:11.13.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    // Use the public InputManager service on recent Android versions (including API 37).
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// Input to tools/licenses/generate.py; only shipped runtime artifacts, not test/build tools.
tasks.register("exportLicenseArtifacts") {
    val output = layout.buildDirectory.file("reports/licenses/artifacts.tsv")
    outputs.file(output)
    outputs.upToDateWhen { false }
    doLast {
        val artifacts = configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
            .filter { it.moduleVersion.id.group != rootProject.name }
            .distinctBy { it.moduleVersion.id.toString() to it.file }
            .sortedBy { it.moduleVersion.id.toString() }
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(artifacts.joinToString("\n", postfix = "\n") {
                "${it.moduleVersion.id}\t${it.file.absolutePath}"
            })
        }
    }
}
