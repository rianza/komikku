plugins {
    id("mihon.library")
    id("mihon.library.compose")

    alias(libs.plugins.metro)
}

android {
    namespace = "tachiyomi.presentation.widget"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.metro)
    implementation(projects.domain)
    implementation(projects.presentationCore)
    implementation(projects.i18n)

    implementation(compose.glance)
    implementation(libs.material)

    implementation(kotlinx.immutables)

    implementation(platform(libs.coil.bom))
    implementation(libs.coil.core)

    implementation(libs.metro.runtime)
}
