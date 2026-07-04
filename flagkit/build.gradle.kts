plugins {
    alias(mihonx.plugins.android.library)
    kotlin("android")
}

android {
    namespace = "com.murgupluoglu.flagkit"
}

dependencies {
    implementation(projects.core.common)
}
